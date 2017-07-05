package com.github.ldaniels528.qwery
package etl

import java.io.File
import java.net.InetAddress
import java.util.Date

import akka.pattern.ask
import akka.util.Timeout
import com.github.ldaniels528.qwery.AppConstants._
import com.github.ldaniels528.qwery.actors.QweryActorSystem
import com.github.ldaniels528.qwery.etl.actors.FileManagementActor._
import com.github.ldaniels528.qwery.etl.actors.JobManagementActor._
import com.github.ldaniels528.qwery.etl.actors.JobStates.JobState
import com.github.ldaniels528.qwery.etl.actors.WorkflowManagementActor.ProcessFile
import com.github.ldaniels528.qwery.etl.actors._
import com.github.ldaniels528.qwery.etl.triggers.Trigger
import com.github.ldaniels528.qwery.ops.{ResultSet, Scope}
import com.github.ldaniels528.qwery.sources.Statistics
import com.github.ldaniels528.qwery.util.OptionHelper.Risky._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Properties, Success}

/**
  * Qwery ETL Application
  * @author lawrence.daniels@gmail.com
  */
object QweryETL extends FileMoving {
  private val log = LoggerFactory.getLogger(getClass)
  private var slaveID_? : Option[String] = None

  /**
    * Startup method
    * @param args the given commandline arguments
    */
  def main(args: Array[String]): Unit = run()

  /**
    * Starts the worker process
    */
  def run(): Unit = {
    println(welcome("ETL"))

    // get the home directory
    val baseDir = Properties.envOrNone(envHome).map(new File(_).getCanonicalFile)
      .getOrElse(throw new IllegalStateException(s"You must set environment variable '$envHome'"))

    // load the configuration
    implicit val config = new ETLConfig(baseDir)
    config.loadScheduledEvents()
    config.loadTriggers()

    // define the execution context
    implicit val dispatcher = QweryActorSystem.dispatcher

    // start a file watch for "$QWERY_HOME/inbox/"
    config.fileManager ! WatchFile(config.inboxDir, { file =>
      val rootScope = Scope.root()
      config.lookupTrigger(rootScope, file.getName) match {
        case Some(trigger) => scheduleJob(file, rootScope, trigger)
        case None =>
          log.warn(s"No trigger found for file '${file.getName}'")
      }
    })

    log.info("Hello.")

    // schedule job queries every 30 seconds
    QweryActorSystem.scheduler.scheduleOnce(0.seconds)(registerAsSlave())
    QweryActorSystem.scheduler.schedule(15.seconds, 30.seconds)(checkForJobs(rootScope = Scope.root()))
  }

  /**
    * Checks out the next available job
    * @param rootScope the [[Scope root scope]]
    * @param config    the [[ETLConfig ETL configuration]]
    */
  private def checkForJobs(rootScope: Scope)(implicit config: ETLConfig, ec: ExecutionContext) = {
    implicit val timeout: Timeout = 30.seconds

    slaveID_? foreach { slaveID =>
      (config.jobManager ? CheckForJobs(slaveID)).mapTo[Option[Job]] onComplete {
        case Success(Some(job)) => processJob(job, rootScope)
        case Success(None) =>
        case Failure(e) =>
          log.error("Failed job checkout", e)
      }
    }
  }

  /**
    * Returns the current CPU load of the JVM
    * @return the CPU load as a percentage
    */
  private def getCpuLoad: Option[Double] = {
    import java.lang.management.ManagementFactory
    import javax.management._

    import scala.collection.JavaConverters._

    val mBeanServer = ManagementFactory.getPlatformMBeanServer
    val objectName = ObjectName.getInstance("java.lang:type=OperatingSystem")
    mBeanServer.getAttributes(objectName, Array[String]("ProcessCpuLoad")).asScala.headOption flatMap {
      case attribute: Attribute =>
        attribute.getValue match {
          case value: Number => Some(100.0 * value.doubleValue())
          case _ => None
        }
      case _ => None
    }
  }

  /**
    * Processes the given job
    * @param job       the given [[Job job]]
    * @param rootScope the [[Scope root scope]]
    * @param config    the [[ETLConfig ETL configuration]]
    */
  private def processJob(job: Job, rootScope: Scope)(implicit config: ETLConfig, ec: ExecutionContext) = {
    val result = for {
      inputFile <- job.input.map(new File(_))
      workflowName <- job.workflowName
      pid <- job._id
      trigger <- config.lookupTriggerByName(workflowName)
      workFile <- moveToWork(pid, inputFile)
    } yield (pid, workFile, trigger)

    result match {
      case Some((pid, workFile, trigger)) =>
        implicit val timeout: Timeout = 4.hours

        log.info(s"Processing file '${workFile.getAbsolutePath}' using '${trigger.name}'...")
        val refreshCycle = QweryActorSystem.scheduler.schedule(initialDelay = 5.seconds, interval = 5.seconds) {
          rootScope.getSources foreach (src => log.info(src.getStatistics.map(_.toString).orNull))
        }

        val startTime = System.currentTimeMillis()
        val outcome = (
          for {
            _ <- updateJobState(job, JobStates.RUNNING)
            resultSet <- (config.workflowManager ? ProcessFile(workFile, trigger, rootScope)).mapTo[ResultSet]
            _ <- updateJobState(job, JobStates.SUCCESS)
            _ <- resultSet.statistics.map(updateStatistics(job, _)) getOrElse Future.successful(None)
          } yield resultSet
          ) recoverWith { case e =>
          log.error(s"[$pid] Failed during processing: ${e.getMessage}")
          updateJobState(job = job.copy(message = e.getMessage), JobStates.FAILED) map { _ => ResultSet() }
        }

        outcome onComplete {
          case Success(resultSet) =>
            refreshCycle.cancel()
            val elapsedTime = System.currentTimeMillis() - startTime
            log.info(s"[$pid] Process completed successfully in $elapsedTime msec")
            resultSet.statistics foreach (stats => log.info(s"[$pid] $stats"))
            moveToArchive(workFile.getParentFile, compress = false)
          case Failure(e) =>
            log.error(s"[$pid] Process failed for '${job.input.orNull}': ${e.getMessage}", e)
            moveToFailed(pid, workFile)
        }
      case None =>
        log.warn("The work file could not be determined.")
        updateJobState(job, JobStates.STOPPED)
    }
  }

  /**
    * Registers this worker as a slave for the configured supervisor
    * @param config the [[ETLConfig ETL configuration]]
    */
  private def registerAsSlave()(implicit config: ETLConfig, ec: ExecutionContext) = {
    implicit val timeout: Timeout = 30.seconds

    val address = InetAddress.getLocalHost
    log.info(s"Registering myself (${address.getHostName}) as a slave...")
    val request = RegistrationRequest(name = "Worker1", host = address.getHostAddress, port = "1337", maxConcurrency = 2)

    (config.slaveManager ? request).mapTo[Option[RegistrationResponse]] onComplete {
      case Success(response_?) =>
        slaveID_? = response_?.flatMap(_._id)
        log.info(s"slaveID: ${slaveID_?.orNull}")
      case Failure(e) =>
        log.error("Registration request failed", e)
    }
  }

  /**
    * Schedules the given file for processing as a new job
    * @param file      the given [[File file]] to process
    * @param rootScope the [[Scope root scope]]
    * @param trigger   the [[Trigger trigger]] responsible for the processing
    * @param config    the [[ETLConfig ETL configuration]]
    */
  private def scheduleJob(file: File, rootScope: Scope, trigger: Trigger)(implicit config: ETLConfig, ec: ExecutionContext) = {
    implicit val timeout: Timeout = 30.seconds

    log.info(s"Trigger '${trigger.name}' accepts '${file.getName}'")
    val job = Job(
      name = file.getName,
      input = file.getAbsolutePath,
      inputSize = file.length().toDouble,
      state = JobStates.NEW,
      workflowName = trigger.name,
      processingHost = s"${InetAddress.getLocalHost.getHostAddress}:${config.controlPort}",
      slaveID = slaveID_?
    )

    //config.workflowManager ? ProcessFile(file, trigger, rootScope)
    (config.jobManager ? CreateJob(job)).mapTo[Option[Job]] onComplete {
      case Success(job_?) => log.info(s"scheduled job: ${job_?.orNull}")
      case Failure(e) =>
        log.error("Job creation failed", e)
    }
  }

  /**
    * Updates the the state of the given job
    * @param job    the [[Job job]] to update
    * @param state  the new [[JobStates job state]]
    * @param config the [[ETLConfig ETL configuration]]
    * @return the promise of the option of the updated job
    */
  private def updateJobState(job: Job, state: JobState)(implicit config: ETLConfig, ec: ExecutionContext): Future[Option[Job]] = {
    implicit val timeout: Timeout = 30.seconds

    log.info(s"Updating state for job # ${job._id.orNull} to '$state'")
    val outcome = (config.jobManager ? ChangeJobState(job, state)).mapTo[Option[Job]]
    outcome onComplete {
      case Success(_) =>
      case Failure(e) =>
        log.error("Failed job state updated", e)
    }
    outcome
  }

  /**
    * Updates the statistics for the given job
    * @param job    the [[Job job]] to update
    * @param stats  the [[Statistics statistics]]
    * @param config the [[ETLConfig ETL configuration]]
    * @return the promise of the option of the updated job
    */
  private def updateStatistics(job: Job, stats: Statistics)(implicit config: ETLConfig, ec: ExecutionContext): Future[Option[Job]] = {
    implicit val timeout: Timeout = 30.seconds

    log.info(s"Updating statistics for job # ${job._id.orNull}")
    val jobStats = JobStatistics(
      cpuLoad = getCpuLoad,
      totalInserted = stats.totalRecords,
      bytesRead = stats.bytesRead,
      bytesPerSecond = stats.bytesPerSecond,
      recordsDelta = stats.recordsDelta,
      recordsPerSecond = stats.recordsPerSecond,
      pctComplete = stats.pctComplete,
      completionTime = stats.completionTime.map(t => new Date(t.toLong))
    )
    (config.jobManager ? UpdateStatistics(job, jobStats)).mapTo[Option[Job]]
  }

}
