package com.qwery.database.device

import java.nio.ByteBuffer
import java.nio.ByteBuffer.wrap

import com.qwery.database.{Column, FieldMetadata, RECORD_ID, ROWID, RowMetadata}

import scala.collection.mutable

/**
 * Caching Block Device
 * @param host the host [[BlockDevice device]]
 */
class CachingBlockDevice(host: BlockDevice) extends BlockDevice {
  private val cache = mutable.Map[ROWID, Array[Byte]]()

  override def close(): Unit = {
    cache.clear()
    host.close()
  }

  override def columns: Seq[Column] = host.columns

  override def getPhysicalSize: Option[Long] = host.getPhysicalSize

  override def length: ROWID = host.length

  override def readRow(rowID: ROWID): ByteBuffer = wrap(cache.getOrElseUpdate(rowID, host.readRow(rowID).array()))

  override def readFieldMetaData(rowID: ROWID, columnID: RECORD_ID): FieldMetadata = host.readFieldMetaData(rowID, columnID)

  override def readRowAsFields(rowID: ROWID): BinaryRow = host.readRowAsFields(rowID)

  override def readRowMetaData(rowID: ROWID): RowMetadata = host.readRowMetaData(rowID)

  override def readField(rowID: ROWID, columnID: Int): ByteBuffer = {
    //cache.remove(rowID)
    host.readField(rowID, columnID)
  }

  override def shrinkTo(newSize: ROWID): Unit = {
    cache.clear()
    host.shrinkTo(newSize)
  }

  override def writeRow(rowID: ROWID, buf: ByteBuffer): Unit = {
    cache(rowID) = buf.array()
    host.writeRow(rowID, buf)
  }

  override def writeField(rowID: ROWID, columnID: Int, buf: ByteBuffer): Unit = {
    cache.remove(rowID)
    host.writeField(rowID, columnID, buf)
  }

  override def writeFieldMetaData(rowID: ROWID, columnID: ROWID, metadata: FieldMetadata): Unit = {
    cache.remove(rowID)
    host.writeFieldMetaData(rowID, columnID, metadata)
  }

  override def writeRowMetaData(rowID: ROWID, metadata: RowMetadata): Unit = {
    cache.remove(rowID)
    host.writeRowMetaData(rowID, metadata)
  }

}