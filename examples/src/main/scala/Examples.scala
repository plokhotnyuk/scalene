package examples

import scalene.routing._
import scalene.stream._
import BasicConversions._

object Main extends App {

  case class Foo(i: Int, s: String)
  val parseFooFromPath = ![Int] / ![String] map Foo.tupled

  val PositiveInt = ![Int]("num")
    .filter(_ > 0, "{NAME} must be a positive integer")

  val fooRoutes = "foo" subroutes (
    _ + POST + parseFooFromPath to {foo => s"got a foo $foo".ok},
    _ + GET + PositiveInt to {id => s"give me foo $id".ok}
  )

  val streamRoute = GET / "stream" to {_ =>
    Stream.fromIter(List("a", "b" , "c").toIterator).ok
  }

  println(fooRoutes.document)

  implicit val pool = new scalene.actor.Pool
  val blockingClient: String => scala.util.Try[String] =
    x => if (x == "fail") {scala.util.Failure(new Exception("FAIL"))} else {Thread.sleep(5000);scala.util.Success(x.toUpperCase)}

  val client = new scalene.ExternalBlockingClient(blockingClient)

  val routes = Routes(
    fooRoutes,
    streamRoute,
    GET / "ucase" / ![String] to {s => client.send(s).map{_.ok}},
    GET / "sum" / ![Int] / ![Int] to {case (a,b) => (a + b).ok},

    GET / "quotient" / ![Int] / 
      ![Int].filter(_ != 0, "dividend can't be zero") to {case (a,b) => (a / b).ok}
  )
  
  val settings = Settings.basic(serverName = "examples", port = 8080)
  Routing.start(settings, routes)

}
