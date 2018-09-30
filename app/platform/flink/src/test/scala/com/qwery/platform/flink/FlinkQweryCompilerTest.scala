package com.qwery.platform.flink

import com.qwery.models.expressions.Field
import com.qwery.models._
import org.scalatest.FunSpec

/**
  * Flink Qwery Compiler Test Suite
  * @author lawrence.daniels@gmail.com
  */
class FlinkQweryCompilerTest extends FunSpec {
  private val compiler = new FlinkQweryCompiler {}

  describe(classOf[FlinkQweryCompiler].getSimpleName) {
    import com.qwery.models.expressions.Expression.Implicits._
    import com.qwery.util.OptionHelper.Implicits.Risky._

    it("should compile a SELECT w/ORDER BY & LIMIT") {
      val sql = SQL(
        // create the input table
        Create(Table(name = "Securities",
          columns = List(
            Column(name = "Symbol", `type` = ColumnTypes.STRING),
            Column(name = "Name", `type` = ColumnTypes.STRING),
            Column(name = "LastSale", `type` = ColumnTypes.DOUBLE),
            Column(name = "MarketCap", `type` = ColumnTypes.DOUBLE),
            Column(name = "IPOyear", `type` = ColumnTypes.INTEGER),
            Column(name = "Sector", `type` = ColumnTypes.STRING),
            Column(name = "Industry", `type` = ColumnTypes.STRING),
            Column(name = "SummaryQuote", `type` = ColumnTypes.STRING),
            Column(name = "Reserved", `type` = ColumnTypes.STRING)),
          inputFormat = StorageFormats.CSV,
          outputFormat = StorageFormats.CSV,
          location = "./samples/companylist/"
        )),

        // project/transform the data
        Select(
          fields = List(
            Field(descriptor = "Symbol"),
            Field(descriptor = "Name"),
            Field(descriptor = "LastSale"),
            Field(descriptor = "MarketCap"),
            Field(descriptor = "IPOyear"),
            Field(descriptor = "Sector"),
            Field(descriptor = "Industry")),
          from = TableRef.parse("Securities"),
          orderBy = List(OrderColumn(name = "Symbol")),
          limit = 100
        ))

      implicit val rc: FlinkQweryContext = new FlinkQweryContext()
      val operation = compiler.compile(sql)
      operation.execute(input = None)
    }

  }

}
