package uy.com.netlabs.esb
package endpoint.http

import scala.concurrent._

import typelist._

import com.ning.http.client.Request
import dispatch.{ Promise => _, _ }

object Http {

  class HttpEndpoint[R] private[Http] (f: Flow, req: Option[(Request, FunctionHandler[R])], ioThreads: Int) extends PullEndpoint with Askable {
    val flow = f
    type Payload = R
    type Response = R
    type SupportedTypes = (Request, FunctionHandler[R]) :: TypeNil

    val dispatcher = new dispatch.Http().threads(ioThreads)

    def start() {}
    def dispose() {
      dispatcher.shutdown
    }

    def pull(): Future[Message[Payload]] = {
      val promise = Promise[Message[Payload]]()
      dispatcher(req.get).onComplete {
        case Right(res) => promise.success(Message(res))
        case Left(err)  => promise.failure(err)
      }
      promise.future
    }
    def ask[Payload: SupportedType](msg, timeOut): Future[Message[Response]] = {
      val promise = Promise[Message[Response]]()
      dispatcher(msg.mapTo[(Request, FunctionHandler[R])].payload).onComplete {
        case Right(res) => promise.success(Message(res))
        case Left(err)  => promise.failure(err)
      }
      promise.future
    }
  }

  private case class EF[R](req: Option[(Request, FunctionHandler[R])], ioThreads: Int = 1) extends EndpointFactory[HttpEndpoint[R]] {
    def apply(f: Flow) = new HttpEndpoint(f, req, ioThreads)
  }
  def apply[R](req: (Request, FunctionHandler[R]), ioThreads: Int = 1): EndpointFactory[PullEndpoint { type Payload = R }] = EF(Some(req), ioThreads)
  def apply[R](ioThreads: Int = 1): EndpointFactory[Askable { type Response = R; type SupportedTypes = HttpEndpoint[R]#SupportedTypes }] = EF(None, ioThreads)
}