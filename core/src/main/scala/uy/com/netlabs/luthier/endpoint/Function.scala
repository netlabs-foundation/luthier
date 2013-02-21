package uy.com.netlabs.luthier
package endpoint

import scala.concurrent._

import typelist._

object Function {
  class FunctionPull[R] private[Function] (f: Flow, function: () => R, ioThreads: Int) extends endpoint.base.BasePullEndpoint with Askable {
    val flow = f
    type Payload = R
    type Response = R
    type SupportedTypes = Function0[R] :: TypeNil

    val ioProfile = base.IoProfile.threadPool(ioThreads)
    protected def retrieveMessage(mf): Message[Payload] = mf(function())

    def ask[Payload: SupportedType](msg, timeOut): Future[Message[Response]] = {
      Future(msg.as[Function0[R]] map (_()))
    }

    def start() {
    }
    def dispose() {
      ioProfile.dispose()
    }
  }
  private case class EF[R](function: () => R, ioThreads: Int = 1) extends EndpointFactory[FunctionPull[R]] {
    def apply(f: Flow) = new FunctionPull(f, function, ioThreads)
  }
  def apply[R](function: => R, ioThreads: Int = 1): EndpointFactory[PullEndpoint {type Payload = R}] = EF[R](() => function, ioThreads)
  def apply[R](ioThreads: Int = 1): EndpointFactory[Askable {type Response = R; type SupportedTypes = FunctionPull[R]#SupportedTypes}] = EF[R](null, ioThreads)
}