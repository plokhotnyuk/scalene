package scalene

import java.util.LinkedList
import scala.concurrent.duration._

case class BasicClientConfig(
  maxInFlightRequests: Int,
  maxPendingRequests: Int,
  pendingTimeout: Duration,
  inFlightTimeout: Duration,
  failFast: Boolean
)

class ClientException(message: String) extends Exception(message)

class BasicClient[Response,Request](factory: Codec.Factory[Response,Request], config: BasicClientConfig) extends ConnectionHandler {

  case class PendingRequest(request: Request, promise: PromiseAsync[Response], createdTS: Long)


  private val codec = factory(processResponse)
  private val pendingRequests = new LinkedList[PendingRequest]
  private val inFlightRequests = new LinkedList[PendingRequest]
  var _handle: Option[ConnectionHandle] = None
  private var env: WorkEnv = null

  def send(request: Request): Async[Response] = {
    def enqueue(): Async[Response] = {
      if (pendingRequests.size < config.maxPendingRequests) {
        val promise = new PromiseAsync[Response]
        pendingRequests.add(PendingRequest(request, promise, env.time()))
        promise
      } else {
        Async.failure(new ClientException("Too many pending requests"))
      }
    }
    _handle match {
      case Some(han) => enqueue()
      case None => if (config.failFast) {
        Async.failure(new ClientException("not connected"))
      } else {
        enqueue()
      }
    }
  }

  final def processResponse(response: Response): Unit = {
    val done = inFlightRequests.remove
    done.promise.succeed(response)

    if (pendingRequests.size > 0) {
      _handle.map{_.requestWrite()}
    }
  }

  def onInitialize(e: WorkEnv) {
    env = e
  }


  final def onReadData(buffer: ReadBuffer) {
    codec.decode(buffer)
  }

  final def onWriteData(buffer: WriteBuffer) = {
    while (!buffer.isOverflowed && pendingRequests.size > 0  && inFlightRequests.size <= config.maxInFlightRequests) {
      val p = pendingRequests.remove
      codec.encode(p.request, buffer)
      inFlightRequests.add(p)
    }
    if (pendingRequests.size > 0 && inFlightRequests.size <= config.maxInFlightRequests) {
      true
    } else {
      false
    }
  }

  def onConnected(handle: ConnectionHandle) {
    _handle = Some(handle)

  }

  def onDisconnected(reason: DisconnectReason) {
    //println(s"disconnected: $reason")
    _handle = None
  }

}