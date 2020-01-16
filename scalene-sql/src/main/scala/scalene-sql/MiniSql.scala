package scalene.sql

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}

import scalene._
import scalene.actor._
import scalikejdbc._

import scala.util.{Failure, Success, Try}

case class MiniSQLConfig(
  url: String,
  username: String,
  password: String,
  numConnections: Int
)

object MiniSQL {


  def client(connectionName: String, host: String, username: String, password: String)(implicit pool: Pool): MiniSQLClient = {
    val config = MiniSQLConfig(host, username, password, 48)
    new MiniSQLClient(connectionName, config)
  }

  def client2(connectionName: String, host: String, username: String, password: String, numWorkers: Int): MiniSQLClient2 = {
    val config = MiniSQLConfig(host, username, password, numWorkers)
    new MiniSQLClient2(connectionName, config, numWorkers)
  }
}

class MiniSQLSession(connection: Connection) {

  val statementCache = new java.util.HashMap[String, PreparedStatement]()

  def prepared(template: String): PreparedStatement = {
    val stmt = {
      val s = statementCache.get(template)
      if (s == null) {
        val statement = connection.prepareStatement(template,ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY )
        statementCache.put(template, statement)
        statement
      } else {
        s
      }
    }
    stmt
  }


}


class MiniSQLClient(connectionName: String, config: MiniSQLConfig)(implicit pool: Pool) {


  trait QueryExecutor {
    def execute(session: MiniSQLSession): Unit
  }

  class SimplePromise[T] {
    var result: Option[Try[T]] = None
  }

  class WrappedQuery[T](runner: MiniSQLSession => T, promise: SimplePromise[T]) extends QueryExecutor {
    def execute(session: MiniSQLSession): Unit = try {
      promise.result = Some(Success(runner(session)))
    } catch {
      case e: Exception => {
        promise.result = Some(Failure(e))
      }
    }
  }

  class MiniSQLConnection(config: MiniSQLConfig)(implicit pool : Pool) extends ExternalBlockingClient[QueryExecutor, Unit] {

    private val connection = DriverManager.getConnection(config.url, config.username, config.password)
    private val session = new MiniSQLSession(connection)

    def sendBlocking(query: QueryExecutor): Try[Unit] = {
      query.execute(session)
      Success(())
    }
  }

  val blockingClients = Array.fill(config.numConnections)(new MiniSQLConnection(config))

  val nextIndex = new java.util.concurrent.atomic.AtomicLong(0)

  def query[T](q: MiniSQLSession => T): Deferred[T] = {
    val sp = new SimplePromise[T]
    val wrapped = new WrappedQuery(q, sp)
    blockingClients(nextIndex.incrementAndGet().toInt % blockingClients.length).send(wrapped).flatMap{_ => sp.result.get match {
      case Success(r) => Deferred.successful(r)
      case Failure(ex) => Deferred.failure(ex)
    }}
  }




}

class MiniSQLClient2(connectionName: String, config: MiniSQLConfig, numWorkers: Int) {

  trait QueryExecutor {
    def execute(session: MiniSQLSession): Unit
  }

  class WrappedQuery[T](runner: MiniSQLSession => T, promise: Try[T] => Unit) extends QueryExecutor {
    def execute(session: MiniSQLSession): Unit = {
      promise(Try{runner(session)}) 
    }
  }

  class MiniSQLConnection extends C.SimpleWorker[QueryExecutor] {

    private val connection = DriverManager.getConnection(config.url, config.username, config.password)
    private val session = new MiniSQLSession(connection)

    def process(query: QueryExecutor): Unit = {
      query.execute(session)
    }
  }

  val pool = new C.SimpleWorkerPool(() => new MiniSQLConnection, "client", numWorkers)

  def query[T](q: MiniSQLSession => T): Deferred[T] = defer {implicit context =>
    val (promise, async) = context.threadSafePromise[T]()
    val wrapped = new WrappedQuery(q, promise)
    pool.process(wrapped)
    async
  }
}

object C {

  abstract class SimpleWorker[T] {
    def process(item: T): Unit
  }

  class SimpleWorkerPool[T](workerFactory: () => SimpleWorker[T], name: String, numWorkers: Int) {

    val queue = new java.util.concurrent.LinkedBlockingQueue[T]()

    val running = new java.util.concurrent.atomic.AtomicBoolean(true)

    class WorkerRunner(id: Int, worker: SimpleWorker[T]) extends Thread(name) {
      override def run() : Unit = {
        println(s"worker $id starting up")
        while (running.get()) {
          try {
            val next = queue.take()
            worker.process(next)
          } catch {
            case e: InterruptedException => {}
            case e: Exception => {
            }
          }
        }
        println(s"worker $id shutting down")
      }
    }

    val runners = (0 until numWorkers).map{i =>
      val worker = workerFactory()
      val runner = new WorkerRunner(i, worker)
      runner.setDaemon(false)
      runner.start
    }

    def process(item: T): Unit = {
      queue.add(item)
    }

    def shutdown(): Unit = {
      running.set(false)
    }
  }

  class SimpleWorkerClient[I, O](numWorkers: Int, workerFunc: () => I => O) {

    case class WrappedRequest(input: I, out: Try[O] => Unit)

    class Worker extends SimpleWorker[WrappedRequest] {
      val func = workerFunc()
      def process(item: WrappedRequest): Unit = {
        item.out(Success(func(item.input)))
      }
    }

    val pool = new SimpleWorkerPool(() => new Worker, "client", numWorkers)

    def send(request: I): Deferred[O] = defer{ implicit context =>
      val (tPromise, async) = context.threadSafePromise[O]()
      pool.process(WrappedRequest(request, tPromise))
      async
    }

    def shutdown(): Unit = {
      pool.shutdown()
    }
  }

}

/*
class TLPreparedStatement(query: String) {
  val id = query.getHashCode

  def bind(f: PreparedStatement => Unit)

}
*/
  
