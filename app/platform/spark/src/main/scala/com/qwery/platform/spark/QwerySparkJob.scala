package com.qwery.platform.spark

import java.io.File

import com.qwery.language.SQLLanguageParser
import com.qwery.util.ResourceHelper._

/**
  * Qwery Spark Job - Batch or Streaming
  * @author lawrence.daniels@gmail.com
  */
object QwerySparkJob {

  /**
    * For stand alone operation
    * @param args the given command line arguments
    */
  def main(args: Array[String]): Unit = {
    // create the Qwery runtime context
    implicit val rc: SparkQweryContext = new SparkQweryContext()

    // check the command line arguments
    args.toList match {
      case path :: jobArgs =>
        val sql = SQLLanguageParser.parse(new File(path))
        new SparkQweryCompiler {}.compileAndRun(sql, args = jobArgs)
      case _ =>
        "/boot.sql".asURL match {
          case Some(url) =>
            val sql = SQLLanguageParser.parse(url)
            new SparkQweryCompiler {}.compileAndRun(sql, args = Nil)
          case None =>
            die(s"java ${getClass.getName.replaceAllLiterally("$", "")} <scriptFile> [<arg1> .. <argN>]")
        }
    }
  }

}
