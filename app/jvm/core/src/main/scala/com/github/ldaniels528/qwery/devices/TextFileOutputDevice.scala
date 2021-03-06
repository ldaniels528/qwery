package com.github.ldaniels528.qwery.devices

import java.io._
import java.util.zip.GZIPOutputStream

import com.github.ldaniels528.qwery.devices.TextFileOutputDevice.getBufferedWriter
import com.github.ldaniels528.qwery.ops.{Hints, Scope}

/**
  * Text File Output Device
  * @author lawrence.daniels@gmail.com
  */
case class TextFileOutputDevice(path: String, hints: Option[Hints]) extends OutputDevice {
  private var writer: Option[BufferedWriter] = None

  override def close(): Unit = writer.foreach(_.close())

  override def open(scope: Scope): Unit = writer = Option(getBufferedWriter(path, hints))

  override def write(record: Record): Unit = writer.foreach { out =>
    statsGen.update(records = 1, bytesRead = record.data.length)
    out.write(new String(record.data))
    out.newLine()
  }

}

/**
  * Text File Output Device Companion
  * @author lawrence.daniels@gmail.com
  */
object TextFileOutputDevice extends OutputDeviceFactory with SourceUrlParser {

  def getBufferedWriter(path: String, hints: Option[Hints]): BufferedWriter = {
    val append = hints.exists(_.isAppend)
    path.toLowerCase match {
      case s if hints.exists(_.isGzip) | s.endsWith(".gz") =>
        new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(path, append))))
      case _ =>
        new BufferedWriter(new FileWriter(path, append))
    }
  }

  /**
    * Returns a compatible Output device for the given URL.
    * @param url the given URL (e.g. "./companylist.csv")
    * @return an option of the [[OutputDevice Output device]]
    */
  override def parseOutputURL(url: String, hints: Option[Hints]): Option[OutputDevice] = {
    val path = if (url.startsWith("file://")) url.drop(7) else url
    Option(TextFileOutputDevice(path, hints))
  }

}