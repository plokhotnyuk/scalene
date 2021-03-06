package scalene.routing

import scalene.http._
import scala.annotation.implicitNotFound
import scalene.corerouting._


trait HeaderExtractor[T] {
  def extract(request: HttpRequest, key: String) : Result[T]
}
object HeaderExtractor {
  implicit def single[T](implicit formatter: Formatter[T]) = new HeaderExtractor[T] {
    def extract(request: HttpRequest, key: String): Result[T] = {
      request.headers.firstValue(key) match {
        case Some(value) => formatter.format(value)
        case None => Left(ParseError.badRequest(s"missing required parameter $key"))
      }
    }
  }

  implicit def seq[T](implicit formatter: Formatter[T]) = new HeaderExtractor[Seq[T]] {
    def extract(request: HttpRequest, key: String): Result[Seq[T]] = {
      request.headers.allValues(key).foldLeft[Result[List[T]]](Right(Nil)){case (build, next) => for {
        buildSeq  <- build
        nextRes   <- formatter.format(next)
      } yield nextRes :: buildSeq }
    }
  }

  implicit def option[T](implicit formatter: Formatter[T]) = new HeaderExtractor[Option[T]] {
    def extract(request: HttpRequest, key: String): Result[Option[T]] = {
      request.headers.firstValue(key) match {
        case Some(p) => formatter.format(p).map{Some(_)}
        case None => Right(None)
      }
    }
  }

  def literal[T : Formatter](lit :T) = new HeaderExtractor[Unit] {
    val inner = single[T]
    def extract(request: HttpRequest, key: String): Result[Unit] = inner
      .extract(request, key) match {
        case Right(res) => if (res == lit) Right(()) else Left(ParseError.badRequest("bad value"))
        case Left(o) => Left(o)
      }
  }

}

@implicitNotFound("Need a Formatter[${X}] in scope")
trait HeaderExtractorProvider[X] {
  type Out
  def provide(extraction: X): HeaderExtractor[Out]
}


object HeaderExtractorProvider {

  implicit def realliteralProvider[T](implicit formatter: Formatter[T]) = new HeaderExtractorProvider[T] {
    type Out = Unit
    def provide(extraction: T): HeaderExtractor[Unit] = HeaderExtractor.literal[T](extraction)
  }

  //TODO: All this needs to be reworked to support mapped extractions
  implicit def extractProvider[T](implicit extractor: HeaderExtractor[T]) = new HeaderExtractorProvider[Extraction[T, T]] {
    type Out = T
    def provide(extraction: Extraction[T, T]): HeaderExtractor[T] = extractor
  }

}


object Header{
  def apply[X] 
  (key: String, extraction: X)
  (implicit provider: HeaderExtractorProvider[X]) = new Parser[HttpRequest, provider.Out] {
    val extractor = provider.provide(extraction)
    def parse(request: HttpRequest) = extractor.extract(request, key)
  }
}

