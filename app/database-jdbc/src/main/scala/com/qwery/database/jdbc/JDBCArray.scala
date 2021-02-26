package com.qwery.database
package jdbc

import com.qwery.database.models.{Column, ColumnMetadata, ColumnTypes}

import java.sql.ResultSet
import java.{sql, util}

/**
 * Represents a JDBC array
 * @param connection the [[JDBCConnection connection]]
 * @param typeName   the name of the item type
 * @param elements   the array elements
 */
case class JDBCArray(connection: JDBCConnection, typeName: String, elements: Array[AnyRef]) extends sql.Array {

  override def getBaseTypeName: String = typeName

  override def getBaseType: Int = ColumnTypes.withName(typeName).getJDBCType

  override def getArray: AnyRef = elements

  override def getArray(map: util.Map[String, Class[_]]): AnyRef = elements

  override def getArray(index: Long, count: Int): Array[AnyRef] = {
    val n = index.toInt
    elements.slice(n, n + count)
  }

  override def getArray(index: Long, count: Int, map: util.Map[String, Class[_]]): Array[AnyRef] = {
    val n = index.toInt
    elements.slice(n, n + count)
  }

  override def getResultSet: ResultSet = createResultSet(start = 0, count = elements.length)

  override def getResultSet(map: util.Map[String, Class[_]]): ResultSet = createResultSet(start = 0, count = elements.length)

  override def getResultSet(index: Long, count: Int): ResultSet = createResultSet(index, count)

  override def getResultSet(index: Long, count: Int, map: util.Map[String, Class[_]]): ResultSet = createResultSet(index, count)

  override def free(): Unit = ()

  private def createResultSet(start: Long, count: Int): ResultSet = {
    val columnType = ColumnTypes.withName(typeName)
    val columns = elements.zipWithIndex map { case (_, index) =>
      Column(name = f"elem$index%02d", metadata = ColumnMetadata(`type` = ColumnTypes.withName(typeName)),
        comment = s"Array element $index", enumValues = Nil, sizeInBytes = columnType.getFixedLength.getOrElse(255))
    }
    new JDBCResultSet(connection, connection.getCatalog, connection.getSchema, tableName = "#Array", columns = columns, data = getArray(start, count) map { elem =>
      Seq(Option(elem))
    })
  }

}