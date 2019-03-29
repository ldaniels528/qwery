package com.qwery.platform.sparksql.embedded

import com.qwery.models.Aliasable

/**
  * Spark Aliasable
  * @author lawrence.daniels@gmail.com
  */
object SparkAliasable {

  /**
    * Spark Invokable Aliases
    * @param invokable the given [[SparkInvokable invokable]] to alias
    */
  final implicit class SparkInvokableAliases[T <: SparkInvokable](val invokable: T) extends AnyVal {
    @inline def as(alias: String): T = invokable match {
      case aliasable: Aliasable => aliasable.as(alias).asInstanceOf[T]
      case _ => die(s"Instance type '${invokable.getClass.getSimpleName}' does not support aliases")
    }

    @inline def getAlias: Option[String] = invokable match {
      case aliasable: Aliasable => aliasable.alias
      case _ => None
    }
  }


}