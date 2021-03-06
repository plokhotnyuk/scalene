package scalene

import java.util.concurrent.atomic.AtomicReference
import java.net.{InetSocketAddress, ServerSocket, StandardSocketOptions}
import java.nio.channels.{SelectionKey, Selector, ServerSocketChannel, SocketChannel}
import scala.concurrent.duration.Duration
import scalene.actor._
import util._

case class ServerSettings(
  port: Int,
  addresses: Seq[String],
  maxConnections: Int,
  tcpBacklogSize: Option[Int],
  numWorkers: Option[Int]
)

object ServerSettings {

  val Default = ServerSettings(
    port = 1349,
    addresses = Nil,
    maxConnections = 10000,
    tcpBacklogSize = None,
    numWorkers = None
  )

}

sealed trait ServerMessage

sealed trait ExternalServerMessage extends ServerMessage
object ExternalServerMessage {
  case object Shutdown extends ExternalServerMessage
}

sealed trait WorkerToServerMessage extends ServerMessage
object WorkerToServerMessage {
  case object ConnectionClosed extends WorkerToServerMessage
}

sealed trait ServerState
object ServerState {
  case object Starting extends ServerState
  case object Running extends ServerState
  case object Terminated extends ServerState
}

private[this] case object SelectNow extends ServerMessage with NoWakeMessage

class ServerActor(
  settings: ServerSettings,
  handlerFactory: AsyncContext => ServerConnectionHandler,
  timeKeeper: TimeKeeper,
  state: AtomicReference[ServerState],
  context: Context
) extends Receiver[ServerMessage](context) with Logging {

  implicit val der = context.dispatcher
  private var openConnections = 0

  val workers = collection.mutable.ArrayBuffer[Actor[ServerToWorkerMessage]]()

  class WorkerIterator extends Iterator[Actor[ServerToWorkerMessage]] {
    private var internal = workers.toIterator

    def hasNext = true

    def next = {
      if (!internal.hasNext) {
        internal = workers.toIterator
      }
      internal.next
    }
  }
  val workerIterator = new WorkerIterator

  val selector: Selector = Selector.open()
  val serverSocketChannel                = ServerSocketChannel.open()
  serverSocketChannel.configureBlocking(false)

  val selectDispatcher = dispatcher.pool.createDispatcher("selector-{ID}")
  private val coSelect: Actor[SelectNow.type] = {
    val serverActor = self
    selectDispatcher.attach(new Receiver[SelectNow.type](_) {
      def receive(m: SelectNow.type) {
        selector.select()
        serverActor.send(SelectNow)
      }
    })
  }

  val ss: ServerSocket = serverSocketChannel.socket()
  val addresses: Seq[InetSocketAddress] =
    settings.addresses.isEmpty match {
      case true  => Seq(new InetSocketAddress(settings.port))
      case false => settings.addresses.map(address => new InetSocketAddress(address, settings.port))
    }

  serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT)

  override def onStart() {
    super.onStart()
    (1 to settings.numWorkers.getOrElse(Runtime.getRuntime.availableProcessors())).foreach{i =>
      val dispatcher = context.dispatcher.pool.createDispatcher(s"worker-$i")
      val actor: Actor[ServerToWorkerMessage] = dispatcher.attach(ctx => new ServerWorker(
        self.specialize[WorkerToServerMessage],
        handlerFactory,
        timeKeeper,
        ctx
      )).specialize[ServerToWorkerMessage]
      workers.append(actor)

    }
    startServer()
    coSelect.send(SelectNow)
  }

  override def onStop() {
    super.onStop()
    info("Shutting down Server")
    state.set(ServerState.Terminated)
    selector.close()
    serverSocketChannel.close()

  }

  def startServer() = {
    //setup the server
    try {
      addresses.foreach(address => ss.bind(address, settings.tcpBacklogSize.getOrElse(0)))
      info(s"name: Bound to ${addresses.mkString(", ")}")
      state.set(ServerState.Running)
      true
    } catch {
      case t: Throwable => {
        error(s"bind failed: ${t.getMessage}, retrying", t)
        false
      }
    }
  }

  def receive(s: ServerMessage) : Unit = s match {
    case SelectNow => {
      select()
      coSelect.send(SelectNow)
    }
    case WorkerToServerMessage.ConnectionClosed => {
      openConnections -= 1
    }
    case ExternalServerMessage.Shutdown => {
      info("Beginning Server shutdown")
      workers.foreach(_.stop())
      self.stop()
      serverSocketChannel.close()
    }
  }

  private def select(): Unit = {
    val selectedKeys = selector.selectedKeys()
    val it           = selectedKeys.iterator()

    while (it.hasNext) {
      val key: SelectionKey = it.next
      if (!key.isValid) {
        it.remove()
      } else if (key.isAcceptable) {
        // Accept the new connection
        try {
          val serverSocketChannel: ServerSocketChannel = key.channel.asInstanceOf[ServerSocketChannel]
          val sc: SocketChannel        = serverSocketChannel.accept()
          if (openConnections < settings.maxConnections) {
            openConnections += 1
            sc.configureBlocking(false)
            sc.socket.setTcpNoDelay(true)
            val w = workerIterator.next
            w.send(ServerToWorkerMessage.NewConnection(sc))
          } else {
            sc.close()
          }
        } catch {
          case c: java.nio.channels.NotYetBoundException => error("Attempted to accept before bound!?", c)
          case t: Throwable =>
            error(s"Error accepting connection: ${t.getClass.getName} - ${t.getMessage}", t)
        }
        it.remove()
      }
    }

  }

}

class Server(stateReader: AtomicReference[ServerState], actor: Actor[ExternalServerMessage]) {

  def state: ServerState = stateReader.get()

  def shutdown(): Unit = actor.send(ExternalServerMessage.Shutdown)

  def blockUntilReady(timeoutMillis: Long): Unit = {
    val end = System.currentTimeMillis + timeoutMillis
    while (state != ServerState.Running && System.currentTimeMillis < end) {
      Thread.sleep(10)
    }
    if (state != ServerState.Running) {
      throw new Exception("Timed out waiting for server to start")
    }
  }

  def blockUntilShutdown(timeoutMillis: Long):  Unit = {
    val end = System.currentTimeMillis + timeoutMillis
    while (state != ServerState.Terminated && System.currentTimeMillis < end) {
      Thread.sleep(10)
    }
    if (state != ServerState.Terminated) {
      throw new Exception("Timed out waiting for server to shutdown")
    }

  }

}

object Server {

  def start(settings: ServerSettings, factory: AsyncContext => ServerConnectionHandler, timeKeeper: TimeKeeper)(implicit pool: Pool): Server = {
    val dispatcher = pool.createDispatcher("server-{ID}")
    val state = new AtomicReference[ServerState](ServerState.Starting)
    val actor = dispatcher
      .attach(ctx => new ServerActor(settings, factory, timeKeeper, state, ctx))
      .specialize[ExternalServerMessage]
    new Server(state, actor)
  }

}


