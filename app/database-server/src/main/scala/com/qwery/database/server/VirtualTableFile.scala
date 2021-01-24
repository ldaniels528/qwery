package com.qwery.database
package server

import com.qwery.database.device.BlockDevice
import com.qwery.database.server.DatabaseFiles._
import com.qwery.database.server.QueryProcessor.commands.SelectRows
import com.qwery.models.expressions.{Expression, Field => SQLField}
import com.qwery.models.{Invokable, OrderColumn}

/**
  * Represents a virtual table file (e.g. view)
  * @param databaseName the name of the database
  * @param viewName     the name of the virtual table
  * @param device       the [[BlockDevice materialized device]]
  */
case class VirtualTableFile(databaseName: String, viewName: String, device: BlockDevice) {
  private val selector = new TableQuery(device)

  /**
    * Retrieves rows matching the given condition up to the optional limit
    * @param condition the given [[KeyValues condition]]
    * @param limit     the optional limit
    * @return the [[BlockDevice results]]
    */
  def getRows(condition: KeyValues, limit: Option[Int] = None): BlockDevice = {
    implicit val results: BlockDevice = createTempTable(device.columns)
    device.whileRow(condition, limit) { row =>
      results.writeRow(row.toBinaryRow)
    }
    results
  }

  /**
    * Executes a query
    * @param fields  the [[Expression field projection]]
    * @param where   the condition which determines which records are included
    * @param groupBy the optional aggregation columns
    * @param orderBy the columns to order by
    * @param limit   the optional limit
    * @return a [[BlockDevice]] containing the rows
    */
  def selectRows(fields: Seq[Expression],
                 where: KeyValues,
                 groupBy: Seq[SQLField] = Nil,
                 orderBy: Seq[OrderColumn] = Nil,
                 limit: Option[Int] = None): BlockDevice = {
    selector.select(fields, where, groupBy, orderBy, limit)
  }

}

/**
  * View File Companion
  */
object VirtualTableFile {

  def apply(databaseName: String, viewName: String): VirtualTableFile = {
    new VirtualTableFile(databaseName, viewName, device = getViewDevice(databaseName, viewName))
  }

  def createView(databaseName: String, viewName: String, query: Invokable, ifNotExists: Boolean): VirtualTableFile = {
    val viewFile = getViewConfigFile(databaseName, viewName)
    if (viewFile.exists() && !ifNotExists) die(s"View '$viewName' already exists")
    else {
      // create the root directory
      getTableRootDirectory(databaseName, viewName).mkdirs()

      // write the view config
      writeViewConfig(databaseName, viewName, query)
    }
    VirtualTableFile(databaseName, viewName)
  }

  def dropView(databaseName: String, viewName: String, ifExists: Boolean): Boolean = {
    val viewFile = getViewConfigFile(databaseName, viewName)
    if (!ifExists && !viewFile.exists()) die(s"View '$viewName' does not exist")
    viewFile.exists() && getViewConfigFile(databaseName, viewName).delete()
  }

  def getViewDevice(databaseName: String, viewName: String): BlockDevice = {
    import com.qwery.database.server.SQLCompiler.implicits.InvokableFacade
    readViewConfig(databaseName, viewName).compile(databaseName) match {
      case SelectRows(_, tableName, fields, where, groupBy, orderBy, limit) =>
        TableFile(databaseName, tableName).selectRows(fields, where, groupBy, orderBy, limit)
      case other => die(s"Unhandled view query model - $other")
    }
  }

}