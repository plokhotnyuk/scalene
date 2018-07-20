package scalene

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

import microactor._
import util._

class FutureClient[Request, Response](factory: Codec.Factory[Response,Request], config: BasicClientConfig)(implicit pool: Pool) {

  implicit val dispatcher = pool.createDispatcher
  val timeKeeper = new RealTimeKeeper

  val receiver = SimpleReceiver[EventLoopEvent]{e => ()}

  val eventLoop = new EventLoop(timeKeeper, Duration.Inf, receiver)

  val client = new BasicClient(factory, config)

  eventLoop.attachAndConnect(config.address, client)

  case class AsyncRequest(request: Request, promise: Promise[Response])

  val sender = SimpleReceiver[AsyncRequest]{req =>
    client.send(req.request).onComplete{t => req.promise.complete(t)}
  }

  def send(request: Request): Future[Response] = {
    val p = Promise[Response]()
    sender.send(AsyncRequest(request, p))
    p.future
  }  

}
