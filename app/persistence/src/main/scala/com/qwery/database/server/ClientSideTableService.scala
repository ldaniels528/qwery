package com.qwery.database.server

import com.qwery.database.ROWID
import com.qwery.database.server.JSONSupport.JSONProductConversion
import com.qwery.database.server.QweryCustomJsonProtocol._
import com.qwery.database.server.QweryWebServiceClient._
import com.qwery.database.server.TableService._
import net.liftweb.json._
import spray.json._

/**
 * Client-Side Table Service
 */
case class ClientSideTableService(host: String = "0.0.0.0", port: Int) extends TableService[TupleSet] {
  private val $http = new QweryWebServiceClient()

  override def appendRow(databaseName: String, tableName: String, values: TupleSet): UpdateResult = {
    $http.post(toUrl(databaseName, tableName), values.toJson.toString().getBytes("utf-8")).as[UpdateResult]
  }

  override def createTable(databaseName: String, ref: TableCreation): UpdateResult = {
    $http.post(toUrl(databaseName), ref.toJSON.getBytes("utf-8")).as[UpdateResult]
  }

  override def deleteRange(databaseName: String, tableName: String, start: ROWID, length: ROWID): UpdateResult = {
    $http.delete(url = s"${toUrl(databaseName, tableName)}/$start/$length").as[UpdateResult]
  }

  override def deleteRow(databaseName: String, tableName: String, rowID: ROWID): UpdateResult = {
    $http.delete(toUrl(databaseName, tableName, rowID)).as[UpdateResult]
  }

  override def dropTable(databaseName: String, tableName: String): UpdateResult = {
    $http.delete(toUrl(databaseName, tableName)).as[UpdateResult]
  }

  override def executeQuery(databaseName: String, tableName: String, sql: String): Seq[TupleSet] = {
    $http.post(s"${toUrl(databaseName, tableName)}/sql", body = sql.getBytes("utf-8")) match {
      case js: JArray => js.values.map(_.asInstanceOf[TupleSet])
      case js => throw new IllegalArgumentException(s"Unexpected type returned $js")
    }
  }

  override def findRows(databaseName: String, tableName: String, condition: TupleSet, limit: Option[Int] = None): Seq[TupleSet] = {
    $http.get(toUrl(databaseName, tableName, condition, limit)) match {
      case js: JArray => js.values.map(_.asInstanceOf[TupleSet])
      case js => throw new IllegalArgumentException(s"Unexpected type returned $js")
    }
  }

  override def getDatabaseMetrics(databaseName: String): DatabaseMetrics = {
    $http.get(toUrl(databaseName)).as[DatabaseMetrics]
  }

  override def getLength(databaseName: String, tableName: String): UpdateResult = {
    $http.get(url = s"${toUrl(databaseName, tableName)}/length").as[UpdateResult]
  }

  override def getRange(databaseName: String, tableName: String, start: ROWID, length: ROWID): Seq[TupleSet] = {
    $http.get(url = s"${toUrl(databaseName, tableName)}/$start/$length") match {
      case js: JArray => js.values.map(_.asInstanceOf[TupleSet])
      case js => throw new IllegalArgumentException(s"Unexpected type returned $js")
    }
  }

  override def getRow(databaseName: String, tableName: String, rowID: ROWID): Option[TupleSet] = {
    $http.get(toUrl(databaseName, tableName, rowID)) match {
      case js: JObject => Option(js.values)
      case js => throw new IllegalArgumentException(s"Unexpected type returned $js")
    }
  }

  override def getTableMetrics(databaseName: String, tableName: String): TableMetrics = {
    $http.get(toUrl(databaseName, tableName)).as[TableMetrics]
  }

  override def replaceRow(databaseName: String, tableName: String, rowID: ROWID, values: TupleSet): UpdateResult = {
    val (_, responseTime) = time($http.put(toUrl(databaseName, tableName, rowID), values.toJson.toString().getBytes("utf-8")))
    UpdateResult(count = 1, responseTime, __id = Some(rowID))
  }

  override def updateRow(databaseName: String, tableName: String, rowID: ROWID, values: TupleSet): UpdateResult = {
    $http.post(toUrl(databaseName, tableName, rowID), values.toJson.toString().getBytes("utf-8")).as[UpdateResult]
  }

  def toIterator(databaseName: String, tableName: String): Iterator[TupleSet] = new Iterator[TupleSet] {
    private var rowID: ROWID = 0
    private val eof: ROWID = getLength(databaseName, tableName).__id.getOrElse(0: ROWID)

    override def hasNext: Boolean = rowID < eof

    override def next(): TupleSet = {
      if (!hasNext) throw new IndexOutOfBoundsException()
      val row = getRow(databaseName, tableName, rowID)
      rowID += 1
      row.orNull
    }
  }

  private def toUrl(databaseName: String): String = s"http://$host:$port/$databaseName"

  private def toUrl(databaseName: String, tableName: String): String = s"http://$host:$port/$databaseName/$tableName"

  private def toUrl(databaseName: String, tableName: String, condition: TupleSet, limit: Option[Int]): String = {
    val keyValues = limit.toList.map(n => s"__limit=$n") ::: condition.toList.map { case (k, v) => s"$k=$v" }
    s"${toUrl(databaseName, tableName)}?${keyValues.mkString("&")}"
  }

  private def toUrl(databaseName: String, tableName: String, rowID: ROWID): String = s"${toUrl(databaseName, tableName)}/$rowID"

}
