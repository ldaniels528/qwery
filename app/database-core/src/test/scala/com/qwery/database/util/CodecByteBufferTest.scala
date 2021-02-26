package com.qwery.database.util

import com.qwery.database.{LONG_BYTES, SHORT_BYTES}
import com.qwery.database.models.FieldMetadata
import com.qwery.database.util.Codec.{CodecByteBuffer, sizeOf}
import com.qwery.database.util.CodecByteBufferTest.FakeNews
import com.qwery.util.ResourceHelper._
import org.scalatest.Assertion
import org.scalatest.funspec.AnyFunSpec

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer.allocate
import java.util.{Date, UUID}
import scala.io.Source

/**
 * Codec Byte Buffer Test Suite
 */
class CodecByteBufferTest extends AnyFunSpec {

  describe(classOf[CodecByteBuffer].getSimpleName) {

    it("should encode and decode BLOB instances") {
      implicit val fmd: FieldMetadata = FieldMetadata(isCompressed = true)
      val file = new File("build.sbt")
      val expected = Source.fromFile(file).use(_.mkString)
      val buf = allocate((1.2 * file.length()).toInt)
      buf.putBlob(expected.getBytes("utf-8"))
      info(s"File size in bytes is ${file.length()}")
      info(s"BLOB size in bytes is ${buf.remaining()}")
      buf.flip()

      val actual = new String(buf.getBlob)
      verify(actual, expected)
    }

    it("should encode and decode CLOB instances") {
      implicit val fmd: FieldMetadata = FieldMetadata(isCompressed = true)
      val file = new File("build.sbt")
      val expected = Source.fromFile(file).use(_.mkString)
      val buf = allocate((1.2 * file.length()).toInt)
      buf.putClob(expected)
      info(s"File size in bytes is ${file.length()}")
      info(s"CLOB size in bytes is ${buf.remaining()}")
      buf.flip()

      val actual = buf.getClob
      verify(actual, expected)
    }

    it("should encode and decode BigDecimal instances") {
      val expected = BigDecimal(Math.sqrt(2))
      val buf = allocate(sizeOf(expected))
      buf.putBigDecimal(expected)
      buf.flip()

      val actual = buf.getBigDecimal
      verify(actual, expected)
    }

    it("should encode and decode BigInteger instances") {
      val expected = BigInteger.valueOf(1e+23.toLong)
      val buf = allocate(sizeOf(expected))
      buf.putBigInteger(expected)
      buf.flip()

      val actual = buf.getBigInteger
      verify(actual, expected)
    }

    it("should encode and decode Date values") {
      val expected = new Date()
      val buf = allocate(LONG_BYTES)
      buf.putDate(expected)
      buf.flip()

      val actual = buf.getDate
      verify(actual, expected)
    }

    it("should encode and decode JVM Objects") {
      implicit val fmd: FieldMetadata = FieldMetadata(isCompressed = true)
      val expected = FakeNews(message = "Yes, they did it!!!")
      val buf = allocate(1024)
      buf.putSerializable(expected)
      info(s"object size in bytes is ${buf.remaining()}")
      buf.flip()

      val actual = buf.getSerializableAs[FakeNews]
      verify(actual, expected)
    }

    it("should encode and decode String values") {
      val expected = "Hello World"
      val buf = allocate(expected.length + SHORT_BYTES)
      buf.putString(expected)
      buf.flip()

      val actual = buf.getString
      verify(actual, expected)
    }

    it("should encode and decode UUID values") {
      val expected = UUID.randomUUID()
      val buf = allocate(2 * LONG_BYTES)
      buf.putUUID(expected)
      buf.flip()

      val actual = buf.getUUID
      verify(actual, expected)
    }

  }

  def verify[A](actual: A, expected: A): Assertion = {
    info(s"actual:   [${actual.toString.take(100)}]")
    info(s"expected: [${expected.toString.take(100)}]")
    assert(actual == expected)
  }

}

/**
 * Codec Byte Buffer Test Companion
 */
object CodecByteBufferTest {

  case class FakeNews(message: String)

}