package uy.com.netlabs.esb
package endpoint
package cxf.codefirst

import uy.com.netlabs.esb.reflect.util.MethodRef
import typelist._
import java.util.concurrent.Executors
import scala.concurrent._, util.Duration, util.duration._
import language._
import org.apache.cxf.endpoint.Server

object Ws {

  private[Ws] val WsResponsePromise = "ws-response-promise"
  class WsResponsible[I, PL, R] private[Ws] (f: Flow,
      val sei: Sei[I],
      val methodRef: MethodRef[I, PL, R],
      val maxResponseTimeout: Duration,
      val ioWorkers: Int) extends base.BaseResponsible {
    type SupportedResponseTypes = R :: TypeNil
    type Payload = PL

    implicit val flow = f

    sei.InterfaceImplementor.methodImplementors += methodRef.method -> { args =>
      val payload = args match { //map from array to tuple
        case arr if arr.length == 0 => () 
        case arr if arr.length == 1 => arr(0)
        case arr if arr.length == 2 => (arr(0), arr(1))
        case arr if arr.length == 3 => (arr(0), arr(1), arr(2))
        case arr if arr.length == 4 => (arr(0), arr(1), arr(2), arr(3))
        case arr if arr.length == 5 => (arr(0), arr(1), arr(2), arr(3), arr(4))
        case arr if arr.length == 6 => (arr(0), arr(1), arr(2), arr(3), arr(4), arr(5))
        case arr if arr.length == 7 => (arr(0), arr(1), arr(2), arr(3), arr(4), arr(5), arr(6))
        case arr if arr.length == 8 => (arr(0), arr(1), arr(2), arr(3), arr(4), arr(5), arr(6), arr(7))
        case arr if arr.length == 9 => (arr(0), arr(1), arr(2), arr(3), arr(4), arr(5), arr(6), arr(7), arr(8))
        case arr if arr.length == 10 => (arr(0), arr(1), arr(2), arr(3), arr(4), arr(5), arr(6), arr(7), arr(8), arr(9))
      }
      val m = Message(payload.asInstanceOf[PL])
      val resultPromise = Promise[Message[OneOf[_, SupportedResponseTypes]]]()
      m.header.put("FLOW", collection.mutable.Map(WsResponsePromise->resultPromise))
      requestArrived(m)
      Await.result(resultPromise.future, maxResponseTimeout).payload.value
    }

    private var server: Server = _
    def start() {
      server = sei.srvFactory.create
    }
    def dispose() {
      server.destroy()
    }

    private val ioExecutor = Executors.newFixedThreadPool(ioWorkers)
    implicit val ioExecutionContext = ExecutionContext.fromExecutor(ioExecutor)

    def sendMessage(msg) {
      msg.header.get("FLOW") match {
        case None => throw new IllegalStateException("ResultPromise was removed from FLOW scope? Maybe response message was created from scratch instead of via a transformation of the request")
        case Some(m) => m(WsResponsePromise).asInstanceOf[Promise[Message[OneOf[_, SupportedResponseTypes]]]].success(msg)
      }
    }
  }

  private case class EF[I, PL, R](s: Sei[I], maxResponseTimeout: Duration, ioWorkers: Int)(f: I => MethodRef[I, PL, R]) extends EndpointFactory[WsResponsible[I, PL, R]] {
    def apply(flow: Flow) = new WsResponsible[I, PL, R](flow, s, f(null.asInstanceOf[I]), maxResponseTimeout, ioWorkers)
  } 
  def apply[I, PL, R](s: Sei[I], maxResponseTimeout: Duration = 30.seconds, ioWorkers: Int = 4)(f: I => MethodRef[I, PL, R]): EndpointFactory[WsResponsible[I, PL, R]] = EF(s, maxResponseTimeout, ioWorkers)(f)
}