package com.qwery.database.functions

import com.qwery.database.models.KeyValues

/**
  * Represents a SQL Transformation Function
  */
trait TransformationFunction extends Function {

  def execute(keyValues: KeyValues): Option[Any]

}