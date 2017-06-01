package com.github.ldaniels528.qwery

import com.github.ldaniels528.qwery.ops._

/**
  * Represents the extracted SQL template properties
  * @param atoms         the named collection of identifiers (e.g. "FROM './customers.csv')
  * @param conditions    the named collection of conditions (e.g. "lastTradeDate >= now() + 1")
  * @param assignables   the named collection of singular expressions (e.g. "SET X = 1+(2*3)")
  * @param expressions   the named collection of field/value expression arguments (e.g. "SELECT 1+(2*3), 'Hello', 123, symbol")
  * @param fields        the named collection of field references (e.g. "INSERT INTO (symbol, exchange, lastSale)")
  * @param hints         the named collection of hint references (e.g. "WITH GZIP COMPRESSION")
  * @param orderedFields the named collection of ordered fields (e.g. "ORDER BY symbol")
  * @param repeatedSets  the named collection of repeated sequences (e.g. "VALUES ('123', '456') VALUES ('789', '012')")
  * @param sources       the named collection of queries
  * @param variables     the named collection of variables
  */
case class SQLTemplateParams(atoms: Map[String, String] = Map.empty,
                             conditions: Map[String, Condition] = Map.empty,
                             assignables: Map[String, Expression] = Map.empty,
                             expressions: Map[String, List[Expression]] = Map.empty,
                             fields: Map[String, List[Field]] = Map.empty,
                             hints: Map[String, Hints] = Map.empty,
                             numerics: Map[String, Double] = Map.empty,
                             orderedFields: Map[String, List[OrderedColumn]] = Map.empty,
                             repeatedSets: Map[String, List[SQLTemplateParams]] = Map.empty,
                             sources: Map[String, Executable] = Map.empty,
                             variables: Map[String, VariableRef] = Map.empty) {

  def +(that: SQLTemplateParams): SQLTemplateParams = {
    this.copy(
      atoms = this.atoms ++ that.atoms,
      conditions = this.conditions ++ that.conditions,
      assignables = this.assignables ++ that.assignables,
      expressions = this.expressions ++ that.expressions,
      fields = this.fields ++ that.fields,
      hints = this.hints ++ that.hints,
      numerics = this.numerics ++ that.numerics,
      orderedFields = this.orderedFields ++ that.orderedFields,
      sources = this.sources ++ that.sources,
      repeatedSets = this.repeatedSets ++ that.repeatedSets,
      variables = this.variables ++ that.variables)
  }

  /**
    * Indicates whether all of the template mappings are empty
    * @return true, if all of the template mappings are empty
    */
  def isEmpty: Boolean = !nonEmpty

  /**
    * Indicates whether at least one of the template mappings is not empty
    * @return true, if at least one of the template mappings is not empty
    */
  def nonEmpty: Boolean = {
    Seq(atoms, conditions, assignables, expressions, fields, numerics, orderedFields, sources, repeatedSets, variables).exists(_.nonEmpty)
  }

}

/**
  * SQLTemplate Params Companion
  * @author lawrence.daniels@gmail.com
  */
object SQLTemplateParams {

  /**
    * Creates a new SQL Language Parser instance
    * @param ts the given [[TokenStream token stream]]
    * @return the [[SQLLanguageParser language parser]]
    */
  def apply(ts: TokenStream, template: String): SQLTemplateParams = new SQLLanguageParser(ts).process(template)

}