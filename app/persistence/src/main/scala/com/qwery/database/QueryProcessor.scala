package com.qwery.database

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Scheduler}
import akka.pattern.ask
import akka.routing.RoundRobinPool
import akka.util.Timeout
import com.qwery.database.QueryProcessor.CommandRoutingActor
import com.qwery.database.QueryProcessor.commands._
import com.qwery.database.models._
import com.qwery.database.server.{DatabaseFile, TableFile}
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

/**
 * Query Processor
 * @param routingActors  the number of command routing actors
 * @param requestTimeout the [[FiniteDuration request timeout]]
 */
class QueryProcessor(routingActors: Int = 5, requestTimeout: FiniteDuration = 5.seconds) {
  private val actorSystem: ActorSystem = ActorSystem(name = "QueryProcessor")
  private val actorPool: ActorRef = actorSystem.actorOf(Props(new CommandRoutingActor(requestTimeout))
    .withRouter(RoundRobinPool(nrOfInstances = routingActors)))

  implicit val _timeout: Timeout = requestTimeout
  implicit val dispatcher: ExecutionContextExecutor = actorSystem.dispatcher
  implicit val scheduler: Scheduler = actorSystem.scheduler

  def !(message: TableIORequest): Unit = actorPool ! message

  def ?(message: DatabaseIORequest): Future[DatabaseIOResponse] = (actorPool ? message).mapTo[DatabaseIOResponse]

  def createIndex(databaseName: String, tableName: String, indexName: String, indexColumnName: String): Future[UpdateCount] = {
    asUpdateCount { CreateIndex(databaseName, tableName, indexName, indexColumnName) }
  }

  def createTable(databaseName: String, tableName: String, columns: Seq[TableColumn]): Future[UpdateCount] = {
    asUpdateCount { CreateTable(databaseName, tableName, columns) }
  }

  def deleteField(databaseName: String, tableName: String, rowID: ROWID, columnID: Int): Future[UpdateCount] = {
    asUpdateCount { DeleteField(databaseName, tableName, rowID, columnID) }
  }

  def deleteRange(databaseName: String, tableName: String, start: ROWID, length: Int): Future[UpdateCount] = {
    asUpdateCount { DeleteRange(databaseName, tableName, start, length) }
  }

  def deleteRow(databaseName: String, tableName: String, rowID: ROWID): Future[UpdateCount] = {
    asUpdateCount { DeleteRow(databaseName, tableName, rowID) }
  }

  def deleteRows(databaseName: String, tableName: String, condition: TupleSet, limit: Option[Int]): Future[UpdateCount] = {
    asUpdateCount { DeleteRows(databaseName, tableName, condition, limit) }
  }

  def dropTable(databaseName: String, tableName: String, ifExists: Boolean): Future[UpdateCount] = {
    asUpdateCount { DropTable(databaseName, tableName, ifExists) }
  }

  def executeQuery(databaseName: String, sql: String): Future[QueryResult] = {
    asResultSet { ExecuteQuery(databaseName, sql) }
  }

  def findRows(databaseName: String, tableName: String, condition: TupleSet, limit: Option[Int] = None): Future[Seq[Row]] = {
    asRows { FindRows(databaseName, tableName, condition, limit) }
  }

  def getDatabaseMetrics(databaseName: String): Future[DatabaseMetrics] = {
    val command = GetDatabaseMetrics(databaseName)
    this ? command map {
      case FailureOccurred(cause, command) => throw FailedCommandException(command, cause)
      case DatabaseMetricsRetrieved(metrics) => metrics
      case response => throw UnhandledCommandException(command, response)
    }
  }

  def getField(databaseName: String, tableName: String, rowID: ROWID, columnID: Int): Future[Field] = {
    val command = GetField(databaseName, tableName, rowID, columnID)
    this ? command map {
      case FailureOccurred(cause, _) => throw FailedCommandException(command, cause)
      case FieldRetrieved(field) => field
      case response => throw UnhandledCommandException(command, response)
    }
  }

  def getRange(databaseName: String, tableName: String, start: ROWID, length: Int): Future[Seq[Row]] = {
    asRows { GetRange(databaseName, tableName, start, length) }
  }

  def getRow(databaseName: String, tableName: String, rowID: ROWID): Future[Option[Row]] = {
    val command = GetRow(databaseName, tableName, rowID)
    this ? command map {
      case FailureOccurred(cause, _) => throw FailedCommandException(command, cause)
      case RowRetrieved(row_?) => row_?
      case response => throw UnhandledCommandException(command, response)
    }
  }

  def getTableLength(databaseName: String, tableName: String): Future[UpdateCount] = {
    asUpdateCount { GetTableLength(databaseName, tableName) }
  }

  def getTableMetrics(databaseName: String, tableName: String): Future[TableMetrics] = {
    val command = GetTableMetrics(databaseName, tableName)
    this ? command map {
      case FailureOccurred(cause, _) => throw FailedCommandException(command, cause)
      case TableMetricsRetrieved(metrics) => metrics
      case response => throw UnhandledCommandException(command, response)
    }
  }

  def insertRow(databaseName: String, tableName: String, values: TupleSet): Future[UpdateCount] = {
    asUpdateCount { InsertRow(databaseName, tableName, values) }
  }

  def insertRows(databaseName: String, tableName: String, columns: Seq[String], values: List[List[Any]]): Future[UpdateCount] = {
    asUpdateCount { InsertRows(databaseName, tableName, columns, values) }
  }

  def replaceRow(databaseName: String, tableName: String, rowID: ROWID, values: TupleSet): Future[UpdateCount] = {
    asUpdateCount { ReplaceRow(databaseName, tableName, rowID, values) }
  }

  def truncateTable(databaseName: String, tableName: String): Future[UpdateCount] = {
    asUpdateCount { TruncateTable(databaseName, tableName) }
  }

  def updateField(databaseName: String, tableName: String, rowID: ROWID, columnID: Int, value: Option[Any]): Future[UpdateCount] = {
    asUpdateCount { UpdateField(databaseName, tableName, rowID, columnID, value) }
  }

  def updateRow(databaseName: String, tableName: String, rowID: ROWID, values: TupleSet): Future[UpdateCount] = {
    asUpdateCount { UpdateRow(databaseName, tableName, rowID, values) }
  }

  def updateRows(databaseName: String, tableName: String, values: TupleSet, condition: TupleSet, limit: Option[Int] = None): Future[UpdateCount] = {
    asUpdateCount { UpdateRows(databaseName, tableName, values, condition, limit) }
  }

  private def asUpdateCount(command: DatabaseIORequest): Future[UpdateCount] = {
    this ? command map {
      case FailureOccurred(cause, command) => throw FailedCommandException(command, cause)
      case RowUpdated(rowID) => UpdateCount(count = 1, __id = Some(rowID))
      case RowsUpdated(count) => UpdateCount(count = count)
      case response => throw UnhandledCommandException(command, response)
    }
  }

  private def asResultSet(command: DatabaseIORequest): Future[QueryResult] = {
    this ? command map {
      case FailureOccurred(cause, command) => throw FailedCommandException(command, cause)
      case QueryResultRetrieved(queryResult) => queryResult
      case response => throw UnhandledCommandException(command, response)
    }
  }

  private def asRows(command: DatabaseIORequest): Future[Seq[Row]] = {
    this ? command map {
      case FailureOccurred(cause, command) => throw FailedCommandException(command, cause)
      case RowsRetrieved(rows) => rows
      case response => throw UnhandledCommandException(command, response)
    }
  }

}

/**
 * Query Processor Companion
 */
object QueryProcessor {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Command Routing Actor
   * @param requestTimeout the [[FiniteDuration request timeout]]
   */
  class CommandRoutingActor(requestTimeout: FiniteDuration) extends Actor {
    import context.{dispatcher, system}
    private val databaseWorkers = TrieMap[String, ActorRef]()
    private val tableWorkers = TrieMap[(String, String), ActorRef]()
    private implicit val timeout: Timeout = requestTimeout

    override def receive: Receive = {
      case command: DropTable =>
        processRequest(caller = sender(), command) onComplete { _ =>
          // kill the actor whom is responsible for the table
          tableWorkers.remove(command.databaseName -> command.tableName).foreach(_ ! PoisonPill)
        }
      case command: TableIORequest => processRequest(caller = sender(), command)
      case command: DatabaseIORequest => processRequest(caller = sender(), command)
      case message =>
        logger.error(s"Unhandled routing message $message")
        unhandled(message)
    }

    private def findDatabaseWorker(command: DatabaseIORequest): ActorRef = {
      import command.databaseName
      databaseWorkers.getOrElseUpdate(databaseName, system.actorOf(Props(new DatabaseCPU(databaseName))))
    }

    private def findTableWorker(command: TableIORequest): ActorRef = {
      import command.{databaseName, tableName}
      tableWorkers.getOrElseUpdate(databaseName -> tableName, system.actorOf(Props(new TableCPU(databaseName, tableName))))
    }

    private def processRequest(caller: ActorRef, command: DatabaseIORequest): Future[DatabaseIOResponse] = {
      try {
        // lookup the appropriate worker for the command
        val worker = command match {
          case cmd: TableIORequest => findTableWorker(cmd)
          case cmd => findDatabaseWorker(cmd)
        }

        // perform the remote command
        val promise = (worker ? command).mapTo[DatabaseIOResponse]
        promise onComplete {
          case Success(response) => caller ! response
          case Failure(cause) => caller ! FailureOccurred(cause, command)
        }
        promise
      } catch {
        case e: Throwable =>
          caller ! FailureOccurred(e, command)
          Future.failed(e)
      }
    }

  }

  /**
   * Database Command Processing Unit Actor
   * @param databaseName the database name
   */
  class DatabaseCPU(databaseName: String) extends Actor {
    private lazy val databaseFile: DatabaseFile = DatabaseFile(databaseName)

    override def receive: Receive = {
      case cmd@CreateTable(_, tableName, columns) =>
        invoke(cmd, sender())(TableFile.createTable(databaseName, tableName, columns.map(_.toColumn))) { case (caller, _) => caller ! RowsUpdated(1) }
      case cmd@DropTable(_, tableName, ifExists) =>
        invoke(cmd, sender())(TableFile.dropTable(databaseName, tableName, ifExists)){ case (caller, isDropped) => caller ! RowsUpdated(count = if (isDropped) 1 else 0) }
      case cmd@ExecuteQuery(_, sql) =>
        invoke(cmd, sender())(TableFile.executeQuery(databaseName, sql)) { case (caller, rows) => caller ! QueryResultRetrieved(rows) }
      case cmd: GetDatabaseMetrics =>
        invoke(cmd, sender())(databaseFile.getDatabaseMetrics) { case (caller, metrics) => caller ! DatabaseMetricsRetrieved(metrics) }
      case message =>
        logger.error(s"Unhandled processing message $message")
        unhandled(message)
    }

    private def invoke[A](command: DatabaseIORequest, caller: ActorRef)(block: => A)(f: (ActorRef, A) => Unit): Unit = {
      try f(caller, block) catch {
        case e: Throwable =>
          caller ! FailureOccurred(e, command)
          None
      }
    }
  }

  /**
   * Table Command Processing Unit Actor
   * @param databaseName the database name
   * @param tableName    the table name
   */
  class TableCPU(databaseName: String, tableName: String) extends Actor {
    private lazy val table: TableFile = QweryFiles.getTableFile(databaseName, tableName)

    override def receive: Receive = {
      case cmd@CreateIndex(_, _, indexName, indexColumn) =>
        invoke(cmd, sender())(table.createIndex(indexName, indexColumn)) { case (caller, n) => caller ! RowsUpdated(n.length) }
      case cmd@DeleteField(_, _, rowID, columnID) =>
        invoke(cmd, sender())(table.deleteField(rowID, columnID)) { case (caller, b) => caller ! RowsUpdated(if (b) 1 else 0) }
      case cmd@DeleteRange(_, _, start, length) =>
        invoke(cmd, sender())(table.deleteRange(start, length)) { case (caller, n) => caller ! RowUpdated(n) }
      case cmd@DeleteRow(_, _, rowID) =>
        invoke(cmd, sender())(table.deleteRow(rowID)) { case (caller, _id) => caller ! RowUpdated(_id) }
      case cmd@DeleteRows(_, _, condition, limit) =>
        invoke(cmd, sender())(table.deleteRows(condition, limit)) { case (caller, n) => caller ! RowsUpdated(n) }
      case cmd@FindRows(_, _, condition, limit) =>
        invoke(cmd, sender())(table.findRows(condition, limit)) { case (caller, rows) => caller ! RowsRetrieved(rows) }
      case cmd@GetField(_, _, rowID, columnID) =>
        invoke(cmd, sender())(table.getField(rowID, columnID)) { case (caller, field) => caller ! FieldRetrieved(field) }
      case cmd@GetRange(_, _, start, length) =>
        invoke(cmd, sender())(table.getRange(start, length)) { case (caller, rows) => caller ! RowsRetrieved(rows) }
      case cmd@GetRow(_, _, rowID) =>
        invoke(cmd, sender())(table.get(rowID)) { case (caller, row_?) => caller ! RowRetrieved(row_?) }
      case cmd: GetTableLength =>
        invoke(cmd, sender())(table.device.length) { case (caller, n) => caller ! RowsUpdated(n) }
      case cmd: GetTableMetrics =>
        invoke(cmd, sender())(table.getTableMetrics) { case (caller, metrics) => caller ! TableMetricsRetrieved(metrics) }
      case cmd@InsertRow(_, _, row) =>
        invoke(cmd, sender())(table.insertRow(row)) { case (caller, _id) => caller ! RowUpdated(_id) }
      case cmd@InsertRows(_, _, columns, rows) =>
        invoke(cmd, sender())(table.insertRows(columns, rows)) { case (caller, n) => caller ! RowsUpdated(n) }
      case cmd@ReplaceRow(_, _, rowID, row) =>
        invoke(cmd, sender())(table.replaceRow(rowID, row)) { case (caller, _) => caller ! RowUpdated(rowID) }
      case cmd: TruncateTable =>
        invoke(cmd, sender())(table.truncate()) { case (caller, n) => caller ! RowsUpdated(n) }
      case cmd@UpdateField(_, _, rowID, columnID, value) =>
        invoke(cmd, sender())(table.updateField(rowID, columnID, value)) { case (caller, _) => caller ! RowUpdated(rowID) }
      case cmd@UpdateRow(_, _, rowID, row) =>
        invoke(cmd, sender())(table.updateRow(rowID, row)) { case (caller, _) => caller ! RowUpdated(rowID) }
      case cmd@UpdateRows(_, _, values, condition, limit) =>
        invoke(cmd, sender())(table.updateRows(values, condition, limit)) { case (caller, n) => caller ! RowsUpdated(n) }
      case message =>
        logger.error(s"Unhandled processing message $message")
        unhandled(message)
    }

    override def postStop(): Unit = {
      super.postStop()
      logger.info(s"Table actor '$databaseName.$tableName' was shutdown")
    }

    private def invoke[A](command: TableIORequest, caller: ActorRef)(block: => A)(f: (ActorRef, A) => Unit): Unit = {
      try f(caller, block) catch {
        case e: Throwable =>
          caller ! FailureOccurred(e, command)
          None
      }
    }

  }

  object commands {

    /////////////////////////////////////////////////////////////////////////////////////////////////
    //      REQUESTS
    /////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Represents a Database I/O Request
     */
    sealed trait DatabaseIORequest {

      def databaseName: String

    }

    /**
     * Represents a Table I/O Request
     */
    sealed trait TableIORequest extends DatabaseIORequest {

      def tableName: String

    }

    case class CreateIndex(databaseName: String, tableName: String, indexName: String, indexColumnName: String) extends TableIORequest

    case class CreateTable(databaseName: String, tableName: String, columns: Seq[TableColumn]) extends DatabaseIORequest

    case class DeleteField(databaseName: String, tableName: String, rowID: ROWID, columnID: Int) extends TableIORequest

    case class DeleteRange(databaseName: String, tableName: String, start: ROWID, length: Int) extends TableIORequest

    case class DeleteRow(databaseName: String, tableName: String, rowID: ROWID) extends TableIORequest

    case class DeleteRows(databaseName: String, tableName: String, condition: TupleSet, limit: Option[Int]) extends TableIORequest

    case class DropTable(databaseName: String, tableName: String, ifExists: Boolean) extends DatabaseIORequest

    case class ExecuteQuery(databaseName: String, sql: String) extends DatabaseIORequest

    case class FindRows(databaseName: String, tableName: String, condition: TupleSet, limit: Option[Int] = None) extends TableIORequest

    case class GetDatabaseMetrics(databaseName: String) extends DatabaseIORequest

    case class GetField(databaseName: String, tableName: String, rowID: ROWID, columnID: Int) extends TableIORequest

    case class GetRange(databaseName: String, tableName: String, start: ROWID, length: Int) extends TableIORequest

    case class GetRow(databaseName: String, tableName: String, rowID: ROWID) extends TableIORequest

    case class GetTableLength(databaseName: String, tableName: String) extends TableIORequest

    case class GetTableMetrics(databaseName: String, tableName: String) extends TableIORequest

    case class InsertRow(databaseName: String, tableName: String, row: TupleSet) extends TableIORequest

    case class InsertRows(databaseName: String, tableName: String, columns: Seq[String], values: List[List[Any]]) extends TableIORequest

    case class ReplaceRow(databaseName: String, tableName: String, rowID: ROWID, row: TupleSet) extends TableIORequest

    case class TruncateTable(databaseName: String, tableName: String) extends TableIORequest

    case class UpdateField(databaseName: String, tableName: String, rowID: ROWID, columnID: Int, value: Option[Any]) extends TableIORequest

    case class UpdateRow(databaseName: String, tableName: String, rowID: ROWID, row: TupleSet) extends TableIORequest

    case class UpdateRows(databaseName: String, tableName: String, values: TupleSet, condition: TupleSet, limit: Option[Int]) extends TableIORequest

    /////////////////////////////////////////////////////////////////////////////////////////////////
    //      RESPONSES
    /////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Represents a Database I/O Response
     */
    sealed trait DatabaseIOResponse

    case class DatabaseMetricsRetrieved(metrics: DatabaseMetrics) extends DatabaseIOResponse

    case class FailureOccurred(cause: Throwable, command: DatabaseIORequest) extends DatabaseIOResponse

    case class FieldRetrieved(field: Field) extends DatabaseIOResponse

    case class QueryResultRetrieved(result: QueryResult) extends DatabaseIOResponse

    case class RowRetrieved(row: Option[Row]) extends DatabaseIOResponse

    case class RowsRetrieved(rows: Seq[Row]) extends DatabaseIOResponse

    case class RowsUpdated(count: Int) extends DatabaseIOResponse

    case class RowUpdated(rowID: ROWID) extends DatabaseIOResponse

    case class TableMetricsRetrieved(metrics: TableMetrics) extends DatabaseIOResponse

    /////////////////////////////////////////////////////////////////////////////////////////////////
    //      EXCEPTIONS
    /////////////////////////////////////////////////////////////////////////////////////////////////

    case class FailedCommandException(command: DatabaseIORequest, cause: Throwable)
      extends RuntimeException(s"Request '$command' failed", cause)

    case class UnhandledCommandException(command: DatabaseIORequest, response: DatabaseIOResponse)
      extends RuntimeException(s"After a '$command' an unhandled message '$response' was received")

  }

}
