package com.qwery
package platform
package sparksql.generator

import java.io.{File, PrintWriter}

import com.qwery.language.SQLLanguageParser
import com.qwery.models._
import com.qwery.util.ResourceHelper._
import com.qwery.util.StringHelper._
import org.slf4j.LoggerFactory

import scala.io.Source

/**
  * Spark/Scala Job Generator
  * @author lawrence.daniels@gmail.com
  */
class SparkJobGenerator(className: String,
                        packageName: String,
                        outputPath: String,
                        appArgs: ApplicationArgs = ApplicationArgs(Nil)) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
    * Generates the SBT build script
    * @return the [[File]] representing the generated build.sbt
    */
  def createBuildScript(): File = {
    import appArgs._
    writeToDisk(outputFile = new File(outputPath, "built.sbt")) {
      s"""|name := "$appName"
          |
          |version := "$appVersion"
          |
          |scalaVersion := "$scalaVersion"
          |
          |test in assembly := {}
          |mainClass in assembly := Some("$packageName.$className")
          |assemblyJarName in assembly := s"$${name.value}-$${version.value}.fat.jar"
          |assemblyMergeStrategy in assembly := {
          |  case PathList("META-INF", "services", _*) => MergeStrategy.filterDistinctLines
          |  case PathList("META-INF", "MANIFEST.MF", _*) => MergeStrategy.discard
          |  case PathList("META-INF", _*) => MergeStrategy.filterDistinctLines
          |  case PathList("org", "datanucleus", _*) => MergeStrategy.rename
          |  case PathList("com", "scoverage", _*) => MergeStrategy.discard
          |  case _ => MergeStrategy.first
          |}
          |
          |libraryDependencies ++= Seq(
          |   "com.databricks" %% "spark-avro" % "$sparkAvroVersion",
          |   "com.databricks" %% "spark-csv" % "$sparkCsvVersion",
          |   "org.apache.spark" %% "spark-core" % "$sparkVersion",
          |   "org.apache.spark" %% "spark-hive" % "$sparkVersion",
          |   "org.apache.spark" %% "spark-sql" % "$sparkVersion",
          |   //
          |   // placeholder dependencies
          |   "com.qwery" %% "core" % "${AppConstants.version}",
          |   "com.qwery" %% "platform-spark-generator" % "${AppConstants.version}",
          |   "com.qwery" %% "language" % "${AppConstants.version}"
          |)
          |""".stripMargin
    }
  }

  /**
    * Generates an executable class file
    * @param invokable the [[Invokable]] for which to generate code
    * @return the [[File]] representing the generated Main class
    */
  def createMainClass(invokable: Invokable)(implicit appArgs: ApplicationArgs): File = {
    // create the package directory
    val pkgDir = new File(inferSourceDir(outputPath), packageName.replaceAllLiterally(".", File.separator))
    if (!pkgDir.mkdirs() && !pkgDir.exists()) die(s"Failed to create the package directory (package '$packageName')")

    // write the class to disk
    logger.info(s"[*] Generating class '$packageName.$className'...")
    writeToDisk(outputFile = new File(pkgDir, s"$className.scala")) {
      generateClassWithMain(invokable, imports = List(
        "com.qwery.models._",
        StorageFormats.getObjectFullName,
        ResourceManager.getObjectFullName,
        "org.apache.spark.SparkConf",
        "org.apache.spark.sql.functions._",
        "org.apache.spark.sql.types.StructType",
        "org.apache.spark.sql.DataFrame",
        "org.apache.spark.sql.Row",
        "org.apache.spark.sql.SparkSession",
        "org.slf4j.LoggerFactory"
      ))
    }
  }

  /**
    * Generates an executable class file
    * @param templateFile the given template [[File file]]
    * @param invokable    the [[Invokable]] for which to generate code
    * @return the [[File]] representing the generated Main class
    */
  def createMainClassFromTemplate(templateFile: File, invokable: Invokable)(implicit appArgs: ApplicationArgs): File = {
    // create the package directory
    val pkgDir = new File(inferSourceDir(outputPath), packageName.replaceAllLiterally(".", File.separator))
    if (!pkgDir.mkdirs() && !pkgDir.exists()) die(s"Failed to create the package directory (package '$packageName')")

    // write the class to disk
    logger.info(s"[*] Generating class '$packageName.$className' from template '${templateFile.getCanonicalPath}'...")
    writeToDisk(outputFile = new File(pkgDir, s"$className.scala")) {
      generateClassFromTemplate(templateFile, invokable, imports = List(
        "com.qwery.models._",
        StorageFormats.getObjectFullName,
        ResourceManager.getObjectFullName,
        "org.apache.spark.sql.functions._",
        "org.slf4j.LoggerFactory"
      )) getOrElse die(s"No replaceable code block found in ${templateFile.getCanonicalPath}")
    }
  }

  /**
    * Generates a new SBT project
    * @param invokable the [[Invokable]] for which to generate code
    * @return the [[File]] representing the generated Main class
    */
  def createProject(invokable: Invokable)(implicit appArgs: ApplicationArgs): File = {
    val startTime = System.currentTimeMillis()
    lazy val elapsedTime = System.currentTimeMillis() - startTime

    logger.info(s"[*] Generating Spark-Scala project '${appArgs.appName}' [${new File(outputPath).getCanonicalPath}]...")
    logger.info("[*] Building project structure...")
    createProjectStructure()

    logger.info("[*] Generating build script (build.sbt)...")
    createBuildScript()

    // generate the class file
    val file = appArgs.templateClass.map(createMainClassFromTemplate(_, invokable)).getOrElse(createMainClass(invokable))

    logger.info(s"[*] Process completed in $elapsedTime msec(s)")
    file
  }

  /**
    * Creates the SBT project structure
    * @return the [[File]] representing the `project` directory
    */
  def createProjectStructure(): File = {
    val projectDir = new File(outputPath, "project")
    if (!projectDir.mkdirs() && !projectDir.exists())
      die(s"Failed to create the SBT project directory ('${projectDir.getCanonicalPath}')")

    // generate the configuration files
    val configFiles = Seq(
      """addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.6")""" -> "assembly.sbt",
      """sbt.version=0.13.16""" -> "build.properties",
      """|addSbtPlugin("com.databricks" %% "sbt-databricks" % "0.1.5")
         |addSbtPlugin("com.typesafe.akka" % "akka-sbt-plugin" % "2.2.3")""".stripMargin -> "plugins.sbt"
    )
    configFiles foreach { case (contents, path) =>
      writeToDisk(outputFile = new File(projectDir, path))(contents)
    }
    projectDir
  }

  def generate(invokable: Invokable)(implicit appArgs: ApplicationArgs): File = {
    if (!appArgs.isClassOnly) createProject(invokable) else {
      appArgs.templateClass match {
        case Some(templateFile) => createMainClassFromTemplate(templateFile, invokable)
        case None => createMainClass(invokable)
      }
    }
  }

  /**
    * Generates a class from a template file
    * @param templateFile the given template [[File file]]
    * @param invokable    the [[Invokable]] for which to generate code
    * @param imports      the collection of imports
    * @param appArgs      the implicit [[ApplicationArgs]]
    * @return an option of the contents f the generated file
    */
  private def generateClassFromTemplate(templateFile: File, invokable: Invokable, imports: Seq[String])
                                       (implicit appArgs: ApplicationArgs): Option[String] = {
    import SparkCodeCompiler.Implicits._
    import com.qwery.util.StringHelper._

    val codeBegin = "// @@SPARK_QL_START"
    val codeEnd = "// @@SPARK_QL_END"

    // read the contents of the template file
    val code = Source.fromFile(templateFile).use(_.getLines().mkString("\n"))

    // replace the QWERY block if it exists
    for {
      start <- code.indexOfOpt(codeBegin)
      end <- code.indexOfOpt(codeEnd).map(_ + codeEnd.length)
    } yield {
      new StringBuilder(code).replace(start, end,
        s"""|${imports.map(pkg => s"import $pkg").sortBy(s => s).mkString("\n")}
            |import spark.implicits._
            |${invokable.compile}
            |""".stripMargin
      ).toString()
    }
  }

  /**
    * Generates a class file with a main() method
    * @param invokable the [[Invokable]] for which to generate code
    * @param imports   the collection of imports
    * @param appArgs   the implicit [[ApplicationArgs]]
    * @return the contents f the generated file
    */
  private def generateClassWithMain(invokable: Invokable, imports: Seq[String])(implicit appArgs: ApplicationArgs): String = {
    import SparkCodeCompiler.Implicits._
    s"""|package $packageName
        |
        |${imports.map(pkg => s"import $pkg").sortBy(s => s).mkString("\n")}
        |
        |class $className() extends Serializable {
        |  @transient
        |  private lazy val logger = LoggerFactory.getLogger(getClass)
        |
        |  def start(args: Array[String])(implicit spark: SparkSession): Unit = {
        |     import spark.implicits._
        |     ${invokable.compile}
        |  }
        |
        |}
        |
        |object $className {
        |   private[this] val logger = LoggerFactory.getLogger(getClass)
        |
        |   def main(args: Array[String]): Unit = {
        |     implicit val spark: SparkSession = createSparkSession("${appArgs.appName}")
        |     new $className().start(args)
        |     spark.stop()
        |   }
        |
        |   def createSparkSession(appName: String): SparkSession = {
        |     val sparkConf = new SparkConf()
        |     val builder = SparkSession.builder()
        |       .appName(appName)
        |       .config(sparkConf)
        |       .enableHiveSupport()
        |
        |     // first attempt to create a clustered session
        |     try builder.getOrCreate() catch {
        |       // on failure, create a local one...
        |       case _: Throwable =>
        |         System.setSecurityManager(null)
        |         logger.warn(s"$$appName failed to connect to EMR cluster; starting local session...")
        |         builder.master("local[*]").getOrCreate()
        |     }
        |   }
        |}
        |""".stripMargin
  }

  private def inferSourceDir(outputPath: String)(implicit appArgs: ApplicationArgs): File = {
    if (appArgs.isClassOnly) new File(outputPath) else new File(outputPath, "src/main/scala")
  }

  /**
    * Writes the given contents to disk
    * @param outputFile the given [[File output file]]
    * @param contents   the given contents to write to disk
    * @return a reference to the [[File output file]]
    */
  private def writeToDisk(outputFile: File)(contents: => String): File = {
    new PrintWriter(outputFile).use(_.println(contents))
    outputFile
  }

}

/**
  * Spark Job Generator Companion
  * @author lawrence.daniels@gmail.com
  * @example sbt "project platform_sparkcode" "run ./samples/sql/adbook/adbook-client.sql ../adbook_poc/ com.coxautoinc.wtm.adbook.AdBookIngestSparkJob"
  */
object SparkJobGenerator {
  private[this] val logger = LoggerFactory.getLogger(getClass)

  /**
    * For stand alone operation
    * @param args the given command line arguments
    */
  def main(args: Array[String]): Unit = {
    import com.qwery.util.StringHelper._

    // get the input path, output path and fully qualified class name (e.g. "com.acme.CoyoteCrush") and flags
    val (inputPath, outputPath, (className, packageName), appArgs) = args.toList match {
      case input :: output :: fullClass :: flags => (input, output, getClassAndPackageNames(fullClass), ApplicationArgs(flags))
      case passedArgs =>
        logger.error(s"Invalid number of arguments passed: [${passedArgs.mkString(",")}]")
        die(s"java ${SparkJobGenerator.getObjectFullName} <inputPath> <outputPath> <outputClass> [<flags>]")
    }

    // generate the sbt project
    val model = SQLLanguageParser.parse(new File(inputPath))
    new SparkJobGenerator(className = className, packageName = packageName, outputPath = outputPath, appArgs = appArgs)
      .generate(model)(appArgs)
  }

  /**
    * Extracts the class and package names from the given fully qualified class name
    * @param classNameWithPackage the given fully qualified class name (e.g. "com.acme.CoyoteFuture")
    * @return the class and package names (e.g. "com.acme.CoyoteFuture" => ["com.acme", "CoyoteFuture"])
    */
  private def getClassAndPackageNames(classNameWithPackage: String): (String, String) = {
    import com.qwery.util.StringHelper._
    classNameWithPackage.lastIndexOfOpt(".").map(classNameWithPackage.splitAt) match {
      case Some((packageName, className)) => (className.drop(1), packageName)
      case None => (classNameWithPackage, "com.examples.spark")
    }
  }

}