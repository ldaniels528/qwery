package com.qwery.platform.spark

import org.apache.spark.sql.DataFrame

/**
  * Spark Application Entry Point
  * @author lawrence.daniels@gmail.com
  */
case class SparkMain(name: String,
                     code: SparkInvokable,
                     hiveSupport: Boolean,
                     streaming: Boolean) extends SparkInvokable {
  override def execute(input: Option[DataFrame])(implicit rc: SparkQweryContext): Option[DataFrame] = {
    rc.mainProgram = Option(this)
    code.execute(input)
  }
}