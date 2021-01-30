package com.qwery.database
package collections

import scala.annotation.meta.field
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Random

case class StockQuote(@(ColumnInfo@field)(maxSize = 8) symbol: String,
                      @(ColumnInfo@field)(maxSize = 8) exchange: String,
                      lastSale: Double,
                      lastSaleTime: Long)

object StockQuote {
  private val random = new Random()

  @tailrec
  def randomURID[A <: Product : ClassTag](coll: PersistentSeq[A]): ROWID = {
    val offset: ROWID = random.nextInt(coll.length.toInt)
    if (coll.device.readRowMetaData(offset).isDeleted) randomURID(coll) else offset
  }

  def randomExchange: String = {
    val exchanges = Seq("AMEX", "NASDAQ", "OTCBB", "NYSE")
    exchanges(random.nextInt(exchanges.size))
  }

  def randomQuote: StockQuote = StockQuote(randomSymbol, randomExchange, randomPrice, randomDate)

  def randomDate: Long = 1603486147408L + random.nextInt(20).days.toMillis

  def randomPrice: Double = random.nextDouble() * random.nextInt(1000)

  def randomSummary: String = {
    val length = 240
    val chars = 'A' to 'Z'
    String.valueOf((0 until length).map(_ => chars(random.nextInt(chars.length))).toArray)
  }

  def randomSymbol: String = {
    val length = 3 + random.nextInt(3)
    val chars = 'A' to 'Z'
    String.valueOf((0 until length).map(_ => chars(random.nextInt(chars.length))).toArray)
  }

}
