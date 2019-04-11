package com.qwery.language

import com.qwery.models._
import com.qwery.models.expressions.NativeFunctions._
import com.qwery.models.expressions._
import org.scalatest.FunSpec

/**
  * Expression Parser Test
  * @author lawrence.daniels@gmail.com
  */
class ExpressionParserTest extends FunSpec {

  describe(classOf[ExpressionParser].getSimpleName) {
    import com.qwery.models.expressions.implicits._
    import com.qwery.util.OptionHelper.Implicits.Risky._

    it("""should parse "A.Symbol" (expression)""") {
      verify("A.Symbol", JoinField("Symbol", tableAlias = "A"))
    }

    it("""should parse conditional expression "100 < 1" (conditional expression)""") {
      verify("100 < 1", Literal(100) < 1)
    }

    it("""should parse "'Hello World' = 'Goodbye'" (conditional expression)""") {
      verify("'Hello World' = 'Goodbye'", Literal("Hello World") === "Goodbye")
    }

    it("""should parse "`Symbol` = 'AAPL'" (conditional expression)""") {
      verify("`Symbol` = 'AAPL'", Field('Symbol) === "AAPL")
    }

    it("""should parse "A.Symbol = 'AMD'" (conditional expression)""") {
      verify("A.Symbol = 'AMD'", Field("A.Symbol") === "AMD")
    }

    it("""should parse "y + (x * 2)" (expression)""") {
      verify("y + (x * 2)", Add('y, Field('x) * 2))
    }

    it("""should parse "y + (x / 2)" (expression)""") {
      verify("y + (x / 2)", Add('y, Field('x) / 2))
    }

    it("""should parse "(y - (x / 2)) AS 'calc'" (expression)""") {
      verify("(y - (x / 2))  AS 'calc'", Subtract('y, Field('x) / 2).as("calc"))
    }

    it("""should parse "LastSale = 100" (equal)""") {
      verify("LastSale = 100", Field('LastSale) === 100)
    }

    it("""should parse "LastSale != 101" (not equal)""") {
      verify("LastSale != 101", Field('LastSale) !== 101)
    }

    it("""should parse "LastSale <> 102" (not equal)""") {
      verify("LastSale <> 102", Field('LastSale) !== 102)
    }

    it("""should parse "NOT LastSale = 103" (not equal)""") {
      verify("NOT LastSale = 103", NOT(Field('LastSale) === 103))
    }

    it("""should parse "LastSale > 104" (greater)""") {
      verify("LastSale > 104", Field('LastSale) > 104)
    }

    it("""should parse "LastSale >= 105" (greater or equal)""") {
      verify("LastSale >= 105", Field('LastSale) >= 105)
    }

    it("""should parse "LastSale < 106" (lesser)""") {
      verify("LastSale < 106", Field('LastSale) < 106)
    }

    it("""should parse "LastSale <= 107" (lesser or equal)""") {
      verify("LastSale <= 107", Field('LastSale) <= 107)
    }

    it("""should parse "Sector IS NULL" (IS NULL)""") {
      verify("Sector IS NULL", IsNull('Sector))
    }

    it("""should parse "Sector IS NOT NULL" (IS NOT NULL)""") {
      verify("Sector IS NOT NULL", IsNotNull('Sector))
    }

    it("""should parse expressions containing 'AND'""") {
      verify("Sector = 'Basic Industries' AND Industry = 'Gas & Oil'",
        Field('Sector) === "Basic Industries" && Field('Industry) === "Gas & Oil")
    }

    it("""should parse expressions containing 'OR'""") {
      verify("Sector = 'Basic Industries' OR Industry = 'Gas & Oil'",
        Field('Sector) === "Basic Industries" || Field('Industry) === "Gas & Oil")
    }

    it("""should parse expressions containing 'AND' and 'OR'""") {
      verify("Sector = 'Basic Industries' AND Industry LIKE '%Gas%' OR Industry LIKE '%Oil%'",
        Field('Sector) === "Basic Industries" && LIKE('Industry, "%Gas%") || LIKE(Field('Industry), "%Oil%"))
    }

    it("""should parse "(x + 3) * 2" (quantities)""") {
      verify("(x + 3) * 2", Multiply(Add('x, 3), 2))
    }

    it("should parse functions (Min)") {
      verify("Min(LastSale)", Min('LastSale))
    }

    it("should parse functions (Max)") {
      verify("Max(LastSale)", Max('LastSale))
    }

    it("should parse functions (Lpad)") {
      verify("LPad(Symbol, 5, ' ')", Lpad('Symbol, 5, " "))
    }

    it("should parse functions (Rpad)") {
      verify("RPad(Symbol, 5, ' ')", Rpad('Symbol, 5, " "))
    }

    it("should parse functions (Stddev)") {
      verify("StdDev(LastSale)", Stddev('LastSale))
    }

    it("should parse functions (Substring)") {
      verify("Substring(Sector, 1, 5)", Substring('Sector, 1, 5))
    }

    it("should parse functions (Sum)") {
      verify("Sum(LastSale)", Sum('LastSale))
    }

    it("should parse local variables: \"$total\"") {
      verify("$total", $("total"))
    }

  }

  private def verify(expr: String, expect: Condition): Unit = {
    val actual = new ExpressionParser {}.parseCondition(TokenStream(expr))
    info(s"$expr => $actual")
    assert(actual.contains(expect), s"$expr : failed")
  }

  private def verify(expr: String, expect: Expression): Unit = {
    val actual = new ExpressionParser {}.parseExpression(TokenStream(expr))
    info(s"$expr => $actual")
    assert(actual.contains(expect), s"$expr : failed")
  }

}
