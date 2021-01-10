package com.qwery.database
package jdbc

import com.qwery.database.server.DatabaseClient
import com.qwery.database.types.SerialNClob

import java.sql.{Array => SQLArray, _}
import java.util.UUID
import java.util.concurrent.Executor
import javax.sql.rowset.serial.{SerialBlob, SerialClob}
import scala.beans.{BeanProperty, BooleanBeanProperty}
import scala.collection.concurrent.TrieMap

/**
 * Qwery JDBC Connection
 * @param client the [[DatabaseClient database client]]
 * @param url    the JDBC Connection URL (e.g. "jdbc:qwery://localhost:8233/securities")
 */
class JDBCConnection(val client: DatabaseClient, url: String) extends Connection with JDBCWrapper {
  private val clientInfoMap = TrieMap[String, String]()
  private val savePoints = TrieMap[String, Savepoint]()
  private var warnings: Option[SQLWarning] = None
  private var networkTimeout: Int = 1500

  @BeanProperty var autoCommit: Boolean = true
  @BeanProperty var catalog: String = _
  @BeanProperty var clientInfo: java.util.Properties = new java.util.Properties()
  @BeanProperty var holdability: Int = ResultSet.HOLD_CURSORS_OVER_COMMIT
  @BooleanBeanProperty var readOnly: Boolean = false
  @BeanProperty var schema: String = _
  @BeanProperty var transactionIsolation: Int = Connection.TRANSACTION_NONE
  @BeanProperty var typeMap: java.util.Map[String, Class[_]] = new java.util.HashMap()

  override def createBlob(): Blob = new SerialBlob(new Array[Byte](0))

  override def createClob(): Clob = new SerialClob(new Array[Char](0))

  override def createNClob(): NClob = new SerialNClob(new Array[Char](0))

  override def createSQLXML(): SQLXML = JDBCSQLXML.create()

  override def createStatement(): Statement = new JDBCStatement(this)

  override def createStatement(resultSetType: Int, resultSetConcurrency: Int): Statement = {
    new JDBCStatement(this)
  }

  override def createStatement(resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): Statement = {
    new JDBCStatement(this)
  }

  override def getMetaData: DatabaseMetaData = new JDBCDatabaseMetaData(this, url)

  override def prepareStatement(sql: String): PreparedStatement = new JDBCPreparedStatement(this, sql)

  override def prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement = {
    new JDBCPreparedStatement(this, sql)
  }

  override def prepareStatement(sql: String, columnIndexes: Array[Int]): PreparedStatement = {
    new JDBCPreparedStatement(this, sql)
  }

  override def prepareStatement(sql: String, columnNames: Array[String]): PreparedStatement = {
    new JDBCPreparedStatement(this, sql)
  }

  override def prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement = {
    new JDBCPreparedStatement(this, sql)
  }

  override def prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): PreparedStatement = {
    new JDBCPreparedStatement(this, sql)
  }

  override def prepareCall(sql: String): CallableStatement = new JDBCCallableStatement(this, sql)

  override def prepareCall(sql: String, resultSetType: Int, resultSetConcurrency: Int): CallableStatement = {
    new JDBCCallableStatement(this, sql)
  }

  override def prepareCall(sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): CallableStatement = {
    new JDBCCallableStatement(this, sql)
  }

  override def nativeSQL(sql: String): String = sql

  override def commit(): Unit = die("Transactions are not supported")

  override def rollback(): Unit = die("Transactions are not supported")

  override def close(): Unit = client.close()

  override def isClosed: Boolean = client.isClosed

  override def getWarnings: SQLWarning = warnings.orNull

  override def clearWarnings(): Unit = warnings = None

  override def setSavepoint(): Savepoint = setSavepoint(UUID.randomUUID().toString)

  override def setSavepoint(name: String): Savepoint = {
    val savepoint = JDBCSavepoint(name)
    savePoints(name) = savepoint
    savepoint
  }

  override def rollback(savepoint: Savepoint): Unit = die("Transactions are not supported")

  override def releaseSavepoint(savepoint: Savepoint): Unit = savePoints.remove(savepoint.getSavepointName)

  override def isValid(timeout: Int): Boolean = networkTimeout >= timeout

  override def setClientInfo(name: String, value: String): Unit = clientInfoMap(name) = value

  override def getClientInfo(name: String): String = clientInfoMap.getOrElse(name, null)

  override def createArrayOf(typeName: String, elements: Array[AnyRef]): SQLArray = JDBCArray(this, typeName, elements)

  override def createStruct(typeName: String, attributes: Array[AnyRef]): Struct = JDBCStruct(typeName, attributes)

  override def abort(executor: Executor): Unit = ()

  override def getNetworkTimeout: Int = networkTimeout

  override def setNetworkTimeout(executor: Executor, milliseconds: Int): Unit = networkTimeout = milliseconds

}
