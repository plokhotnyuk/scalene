package scalene
package http

import java.util.LinkedList

trait HttpResponse extends HttpMessage {
  def code: ResponseCode
}

class ParsedHttpResponse(firstLine: Array[Byte], val headers: LinkedList[Header], val body: Body) extends HttpResponse {
  def code = ???
  def version = ???
}

case class BasicHttpResponse(code: ResponseCode, headers: LinkedList[Header], body: Body) extends HttpResponse {
  def version = ???
}

object HttpResponse {
  private val emptyHeaders = new LinkedList[Header]()
  def apply(code: ResponseCode, body:Body): HttpResponse = BasicHttpResponse(code, emptyHeaders, body)
}
