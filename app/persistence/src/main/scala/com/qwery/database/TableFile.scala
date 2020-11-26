package com.qwery.database

import java.io.{File, PrintWriter}

import com.qwery.database.JSONSupport.{JSONProductConversion, JSONStringConversion}
import com.qwery.database.PersistentSeq.newTempFile
import com.qwery.database.TableFile._
import com.qwery.database.device.{BlockDevice, RowOrientedFileBlockDevice, TableIndexDevice}
import com.qwery.database.models.TableColumn.ColumnToTableColumnConversion
import com.qwery.database.models._
import com.qwery.models.expressions.{AllFields, BasicField, Expression}
import com.qwery.util.ResourceHelper._
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.io.Source
import scala.language.postfixOps

/**
 * Represents a database table file
 * @param databaseName the name of the database
 * @param tableName    the name of the table
 * @param config       the [[TableConfig table configuration]]
 * @param device       the [[BlockDevice block device]]
 */
case class TableFile(databaseName: String, tableName: String, config: TableConfig, device: BlockDevice) {
  private val logger = LoggerFactory.getLogger(getClass)
  private val indexFiles = TrieMap[String, TableIndexDevice]()

  // load the indices for this table
  config.indices foreach registerIndex

  /**
   * Performs an aggregation
   * @param condition  the [[RowTuple inclusion criteria]]
   * @param groupBy    the columns to group by
   * @param projection the desired [[Expression projection]]
   * @param limit      the maximum number of rows for which to return
   * @return the [[BlockDevice results]]
   */
  def aggregateRows(condition: RowTuple,
                    groupBy: Seq[String],
                    projection: Seq[Expression],
                    limit: Option[Int] = None): BlockDevice = {
    // determine the group by columns
    val groupByColumns = groupBy.map(getColumnByName).distinct

    // determine the column projection
    val projectionColumns = (groupByColumns ++ projection.map {
      case field: BasicField => getColumnByName(name = field.name)
      case unknown => die(s"Unhandled projection expression: $unknown")
    }).distinct

    // create and populate the temporary table
    val results = new RowOrientedFileBlockDevice(projectionColumns, file = newTempFile())
    _iterate(condition, limit) { row =>
      val values = row.copy(id = device.length).toRowTuple
      results.writeRow(values.toBinaryRow(results))
    }
    // TODO perform the aggregation
    results
  }

  /**
   * Closes the underlying file handle
   */
  def close(): Unit = device.close()

  def count(): ROWID = device.countRows(_.isActive)

  def countRows(condition: RowTuple, limit: Option[Int] = None): Int = _iterate(condition, limit) { _ => }

  /**
   * Creates a new binary search index
   * @param indexColumnName the name of the index [[Column column]]
   * @return a new binary search [[TableIndexDevice index]]
   */
  def createIndex(indexColumnName: String): TableIndexDevice = {
    val indexRef = TableIndexRef(databaseName, tableName, indexColumnName)
    val indexColumn = getColumnByName(indexRef.indexColumnName)
    val tableIndex = TableIndexDevice.createIndex(indexRef, indexColumn)(device)
    registerIndex(indexRef)
    writeTableConfig(databaseName, tableName, config.copy(indices = (indexRef :: config.indices.toList).distinct))
    tableIndex
  }

  def deleteField(rowID: ROWID, columnID: Int): Boolean = {
    _indexed(rowID, columnID) { _ =>
      device.updateFieldMetaData(rowID, columnID)(_.copy(isActive = false))
      true
    } { (indexDevice, searchValue) =>
      indexDevice.deleteRow(rowID, searchValue)
    }
  }

  def deleteField(rowID: ROWID, columnName: String): Boolean = deleteField(rowID, getColumnID(columnName))

  def deleteRange(start: ROWID, length: Int): Int = {
    var total = 0
    val limit: ROWID = Math.min(device.length, start + length)
    var rowID: ROWID = start
    while (rowID < limit) {
      total += deleteRow(rowID).toInt
      rowID += 1
    }
    total
  }

  def deleteRow(rowID: ROWID): Boolean = {
    _indexed(rowID) { _ =>
      device.updateRowMetaData(rowID)(_.copy(isActive = false))
      true
    } { (indexDevice, searchValue) => indexDevice.deleteRow(rowID, searchValue) }
  }

  def deleteRows(condition: RowTuple, limit: Option[Int] = None): Int = {
    _iterate(condition, limit) { row => deleteRow(row.id) }
  }

  /**
   * Exports the contents of this device as Comma Separated Values (CSV)
   * @return a new CSV [[File file]]
   */
  def exportAsCSV: File = device.exportAsCSV

  /**
   * Exports the contents of this device as JSON
   * @return a new JSON [[File file]]
   */
  def exportAsJSON: File = device.exportAsJSON

  /**
   * Atomically retrieves and replaces a row by ID
   * @param rowID the row ID
   * @param f     the update function to execute
   * @return the [[Row]] representing the updated record
   */
  def fetchAndReplace(rowID: ROWID)(f: RowTuple => RowTuple): Row = {
    val input = getRow(rowID).map(_.toRowTuple).getOrElse(RowTuple(ROWID_NAME -> rowID))
    val output = f(input)
    replaceRow(rowID, output)
    output.toBinaryRow(device).toRow(device).copy(id = rowID)
  }

  /**
   * Retrieves the first row matching the given condition
   * @param condition the given [[RowTuple condition]]
   * @return the option of a [[Row row]]
   */
  def findRow(condition: RowTuple): Option[Row] = findRows(condition, limit = Some(1)).headOption

  /**
   * Retrieves rows matching the given condition up to the optional limit
   * @param condition the given [[RowTuple condition]]
   * @param limit     the optional limit
   * @return the list of matched [[Row rows]]
   */
  def findRows(condition: RowTuple, limit: Option[Int] = None): Seq[Row] = {
    // check all available indices for the table
    val tableIndices = (for {
      (searchColumn, searchValue) <- condition.toSeq
      indexDevice <- indexFiles.get(searchColumn).toSeq
    } yield (indexDevice, searchColumn, searchValue)).headOption

    tableIndices match {
      // if an index was found use it
      case Some((indexDevice, indexColumn, searchValue)) =>
        logger.info(s"Using index '$databaseName/$tableName/$indexColumn'...")
        for {
          indexedRow <- indexDevice.binarySearch(Option(searchValue))
          dataRowID <- indexedRow.getReferencedRowID
          dataRow <- getRow(dataRowID)
        } yield dataRow
      // otherwise perform a table scan
      case _ =>
        var rows: List[Row] = Nil
        _iterate(condition, limit) { row => rows = row :: rows }
        rows
    }
  }

  /**
   * Retrieves a column by ID
   * @param columnID the column ID
   * @return the [[Column column]]
   */
  def getColumnByID(columnID: Int): Column = device.getColumnByID(columnID)

  /**
   * Retrieves a column by name
   * @param name the column name
   * @return the [[Column column]]
   */
  def getColumnByName(name: String): Column = device.getColumnByName(name)

  /**
   * Retrieves a column ID
   * @param name the column name
   * @return the [[Column column]]
   */
  def getColumnID(name: String): Int = device.columns.indexWhere(_.name == name)

  def getField(rowID: ROWID, columnID: Int): Field = device.getField(rowID, columnID)

  def getRow(rowID: ROWID): Option[Row] = {
    val row = device.getRow(rowID)
    if (row.metadata.isActive) Some(row) else None
  }

  def getRange(start: ROWID, length: Int): Seq[Row] = {
    var rows: List[Row] = Nil
    val limit = Math.min(device.length, start + length)
    var rowID: ROWID = start
    while (rowID < limit) {
      getRow(rowID).foreach(row => rows = row :: rows)
      rowID += 1
    }
    rows
  }

  def getTableMetrics: TableMetrics = TableMetrics(
    databaseName = databaseName, tableName = tableName, columns = device.columns.toList.map(_.toTableColumn),
    physicalSize = device.getPhysicalSize, recordSize = device.recordSize, rows = device.length
  )

  /**
   * Facilitates a line-by-line ingestion of a text file
   * @param file      the text [[File file]]
   * @param transform the [[String line]]-to-[[RowTuple record]] transformation function
   * @return the [[LoadMetrics]]
   */
  def ingestTextFile(file: File)(transform: String => Option[RowTuple]): LoadMetrics = {
    var records: Long = 0
    val clock = stopWatch
    Source.fromFile(file).use(src =>
      for {
        line <- src.getLines() if line.nonEmpty
        rowTuple <- transform(line) if rowTuple.nonEmpty
      } {
        insertRow(rowTuple)
        records += 1
      })
    val ingestTime = clock()
    LoadMetrics(records, ingestTime, recordsPerSec = records / (ingestTime / 1000))
  }

  def insertRow(values: RowTuple): ROWID = {
    val rowID = device.length
    _indexed(rowID, values) { _ =>
      device.writeRowAsBinary(rowID, values.toRowBuffer(device))
      rowID
    } { (indexDevice, _, newValue) => indexDevice.insertRow(rowID, newValue) }
  }

  def insertRows(columns: Seq[String], valueLists: List[List[Any]]): Seq[ROWID] = {
    for {
      values <- valueLists
      row = RowTuple(columns zip values map { case (column, value) => column -> value }: _*)
    } yield insertRow(row)
  }

  def lockRow(rowID: ROWID): Unit = {
    device.updateRowMetaData(rowID) { rmd =>
      if (rmd.isLocked) throw RowIsLockedException(rowID) else rmd.copy(isLocked = true)
    }
  }

  def replaceRange(start: ROWID, length: Int, rowTuple: RowTuple): Int = {
    var total = 0
    val rowBuf = rowTuple.toRowBuffer(device)
    val limit: ROWID = Math.min(device.length, start + length)
    var rowID: ROWID = start
    while (rowID < limit) {
      device.writeRowAsBinary(rowID, rowBuf)
      total += 1
      rowID += 1
    }
    total
  }

  def replaceRow(rowID: ROWID, values: RowTuple): Unit = {
    _indexed(rowID, values) { _ =>
      device.writeRowAsBinary(rowID, values.toRowBuffer(device))
    } { (indexDevice, oldValue, newValue) => indexDevice.updateRow(rowID, oldValue, newValue) }
  }

  /**
   * Resizes the table; removing or adding rows
   */
  def resize(newSize: ROWID): Unit = device.shrinkTo(newSize)

  def selectRows(fields: Seq[Expression], where: RowTuple, limit: Option[Int] = None): QueryResult = {
    val rows = findRows(where, limit)
    val columns = device.columns.map(_.toTableColumn)
    val fieldNames: Set[String] = (fields flatMap {
      case AllFields => columns.map(_.name)
      case f: BasicField => List(f.name)
      case expression =>
        logger.error(s"Unconverted expression: $expression")
        Nil
    }).toSet

    QueryResult(databaseName, tableName, columns, __ids = rows.map(_.id), rows = rows map { row =>
      val mapping = row.toMap.filter { case (name, _) => fieldNames.contains(name) } // TODO properly handle field projection
      columns map { column => mapping.get(column.name) }
    })
  }

  def unlockRow(rowID: ROWID): Unit = {
    device.updateRowMetaData(rowID) { rmd =>
      if (rmd.isLocked) rmd.copy(isLocked = false) else throw RowIsLockedException(rowID)
    }
  }

  def updateField(rowID: ROWID, columnID: Int, newValue: Option[Any]): Boolean = {
    _indexed(rowID, columnID) { _ =>
      device.updateField(rowID, columnID, newValue)
    } { (indexDevice, oldValue) => indexDevice.updateRow(rowID, oldValue, newValue) }
  }

  def updateRow(rowID: ROWID, values: RowTuple): Boolean = {
    _indexed(rowID, values) { row_? =>
      row_? exists { row =>
        val updatedValues = row.toRowTuple ++ values
        replaceRow(rowID, updatedValues)
        true
      }
    } { (indexDevice, oldValue, newValue) => indexDevice.updateRow(rowID, oldValue, newValue) }
  }

  def updateRows(values: RowTuple, condition: RowTuple, limit: Option[Int] = None): Int = {
    _iterate(condition, limit) { row =>
      val updatedValues = row.toRowTuple ++ values
      replaceRow(row.id, updatedValues)
    }
  }

  /**
   * Truncates the table; removing all rows
   * @return the number of rows removed
   */
  def truncate(): ROWID = {
    // shrink the table to zero
    val oldSize = device.length
    device.shrinkTo(newSize = 0)
    oldSize
  }

  @inline
  private def isSatisfied(result: => RowTuple, condition: => RowTuple): Boolean = {
    condition.forall { case (name, value) => result.get(name).contains(value) }
  }

  @inline
  private def registerIndex(indexRef: TableIndexRef): Unit = {
    indexFiles(indexRef.indexColumnName) = TableIndexDevice(indexRef)
  }

  @inline
  private def _indexed[A](rowID: Int, columnID: Int)(mutation: Option[Row] => A)(f: (TableIndexDevice, Option[Any]) => Unit): A = {
    // first get the pre-updated value
    val row_? = if (indexFiles.nonEmpty) getRow(rowID) else None

    // execute the update operation
    val result = mutation(row_?)

    // update the affected indices
    if (indexFiles.nonEmpty) {
      val oldValue = row_?.flatMap(_.fields(columnID).value)
      val columnName = getColumnByID(columnID).name
      indexFiles foreach {
        case (indexColumn, indexDevice) if columnName == indexColumn => f(indexDevice, oldValue)
        case _ =>
      }
    }
    result
  }

  @inline
  private def _indexed[A](rowID: Int)(mutation: Option[Row] => A)(f: (TableIndexDevice, Option[Any]) => Unit): A = {
    // first get the pre-updated value
    val row_? = if (indexFiles.nonEmpty) getRow(rowID) else None

    // execute the update operation
    val result = mutation(row_?)

    // update the affected indices
    if (indexFiles.nonEmpty) {
      val oldValues: Seq[(TableIndexDevice, Option[Any])] = {
        indexFiles.toSeq map { case (indexColumn, indexDevice) =>
          val indexColumnID = getColumnID(indexColumn)
          val oldValue = row_?.flatMap(_.fields(indexColumnID).value)
          (indexDevice, oldValue)
        }
      }
      oldValues foreach {
        case (indexDevice, oldValue) => f(indexDevice, oldValue)
        case _ =>
      }
    }
    result
  }

  @inline
  private def _indexed[A](rowID: Int, values: RowTuple)(mutation: Option[Row] => A)(f: (TableIndexDevice, Option[Any], Option[Any]) => Unit): A = {
    // first get the pre-updated values
    val row_? = if (indexFiles.nonEmpty) getRow(rowID) else None

    // execute the update operation
    val result = mutation(row_?)

    // update the affected indices
    if (indexFiles.nonEmpty) {
      val oldAndNewValues: Seq[(TableIndexDevice, Option[Any], Option[Any])] = {
        indexFiles.toSeq map { case (indexColumn, indexDevice) =>
          val indexColumnID = getColumnID(indexColumn)
          val oldValue = row_?.flatMap(_.fields(indexColumnID).value)
          val newValue = values.get(indexColumn)
          (indexDevice, oldValue, newValue)
        }
      }
      oldAndNewValues foreach {
        case (indexDevice, oldValue, newValue) => f(indexDevice, oldValue, newValue)
        case _ =>
      }
    }
    result
  }

  @inline
  private def _iterate(condition: RowTuple, limit: Option[Int] = None)(f: Row => Unit): Int = {
    var (matches: Int, rowID: ROWID) = (0, 0)
    val eof = device.length
    while (rowID < eof && !limit.exists(matches >= _)) {
      getRow(rowID) foreach { row =>
        if (condition.isEmpty || isSatisfied(row.toRowTuple, condition)) {
          f(row)
          matches += 1
        }
      }
      rowID += 1
    }
    matches
  }

}

/**
 * Table File Companion
 */
object TableFile {

  /**
   * Retrieves a table by name
   * @param databaseName the name of the database
   * @param tableName    the name of the table
   * @return the [[TableFile]]
   */
  def apply(databaseName: String, tableName: String): TableFile = {
    val (configFile, dataFile) = (getTableConfigFile(databaseName, tableName), getTableDataFile(databaseName, tableName))
    assert(configFile.exists() && dataFile.exists(), s"Table '$databaseName.$tableName' does not exist")

    val config = readTableConfig(databaseName, tableName)
    val device = new RowOrientedFileBlockDevice(columns = config.columns.map(_.toColumn), dataFile)
    new TableFile(databaseName, tableName, config, device)
  }

  /**
   * Creates a new database table
   * @param databaseName the name of the database
   * @param tableName    the name of the table
   * @param columns      the table columns
   * @return the new [[TableFile]]
   */
  def createTable(databaseName: String, tableName: String, columns: Seq[Column]): TableFile = {
    val dataFile = getTableDataFile(databaseName, tableName)
    assert(!dataFile.exists(), s"Table '$databaseName.$tableName' already exists")

    // create the root directory
    getTableRootDirectory(databaseName, tableName).mkdirs()

    // create the table configuration file
    val config = TableConfig(columns = columns.map(_.toTableColumn), indices = Nil)
    writeTableConfig(databaseName, tableName, config)

    // return the table
    new TableFile(databaseName, tableName, config, new RowOrientedFileBlockDevice(columns, dataFile))
  }

  /**
   * Deletes the table
   * @param databaseName the name of the database
   * @param tableName    the name of the table
   * @param ifExists     indicates whether an existence check before attempting to delete
   * @return true, if the table was deleted
   */
  def dropTable(databaseName: String, tableName: String, ifExists: Boolean = false): Boolean = {
    val directory = getTableRootDirectory(databaseName, tableName)
    val files = directory.listFilesRecursively
    files.forall(_.delete())
  }

  //////////////////////////////////////////////////////////////////////////////////////
  //  TABLE CONFIG
  //////////////////////////////////////////////////////////////////////////////////////

  def getTableConfigFile(databaseName: String, tableName: String): File = {
    new File(new File(new File(getServerRootDirectory, databaseName), tableName), s"$tableName.json")
  }

  def getTableFile(databaseName: String, tableName: String): TableFile = TableFile(databaseName, tableName)

  def getTableRootDirectory(databaseName: String, tableName: String): File = {
    new File(new File(getServerRootDirectory, databaseName), tableName)
  }

  def getTableColumnFile(databaseName: String, tableName: String, columnID: Int): File = {
    new File(new File(new File(getServerRootDirectory, databaseName), tableName), s"$tableName-$columnID.qdb")
  }

  def getTableDataFile(databaseName: String, tableName: String): File = {
    new File(new File(new File(getServerRootDirectory, databaseName), tableName), s"$tableName.qdb")
  }

  def getTableIndices(databaseName: String, tableName: String): Seq[TableIndexRef] = {
    readTableConfig(databaseName, tableName).indices
  }

  def readTableConfig(databaseName: String, tableName: String): TableConfig = {
    Source.fromFile(getTableConfigFile(databaseName, tableName)).use(src => src.mkString.fromJSON[TableConfig])
  }

  def writeTableConfig(databaseName: String, tableName: String, config: TableConfig): Unit = {
    new PrintWriter(getTableConfigFile(databaseName, tableName)).use(_.println(config.toJSONPretty))
  }

}