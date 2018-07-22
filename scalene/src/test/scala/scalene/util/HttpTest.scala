package scalene
package http

import util._
import Method._

import microactor._
import org.scalatest._
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

class HttpSpec extends AsyncFlatSpec with Matchers with BeforeAndAfterAll{

  behavior of "HttpServer"
  
  val pool = new Pool

  override def afterAll() {
    pool.shutdown()
  }

  val settings = HttpServerSettings(
    serverName = "test",
    server = ServerSettings(
      port = 9876,
      addresses = Nil,
      maxConnections = 4096,
      tcpBacklogSize = None,
      numWorkers = Some(1),
      maxIdleTime = 10.seconds
    )
  )

  it should "receive a request" in {
    implicit val p = pool
    val server = HttpServer.start(settings, Seq(
      Get url "/test"  to {
        println("got the request")
        Body.plain("foo").ok
      }
    ))
    val client = HttpClient.futureClient(BasicClientConfig.default("localhost", 9876))
    client.send(HttpRequest.get("/test")).map{res =>
      assert(res.code == ResponseCode.Ok)
    }
  }

  behavior of "HttpRequestEncoding"

  it should "encode" in {
    val req = HttpRequest.get("/foo")
    val expected = "GET /foo HTTP/1.1\r\nContent-Length: 0\r\n\r\n"
    val encoder = new HttpMessageEncoder[HttpRequest](timeKeeper = new TestTimeKeeper(123))

    encoder.encodeString(req) should equal(expected)
  }

}
