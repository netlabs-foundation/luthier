package uy.com.netlabs.esb

import scala.concurrent._
import scala.concurrent.duration._
import language.implicitConversions
import typelist._

trait Endpoint {
  def start(): Unit
  def dispose(): Unit
  implicit def flow: Flow
  def appContext = flow.appContext
  def log = appContext.actorSystem.log
}
trait EndpointFactory[+E <: Endpoint] extends Equals {
  def apply(flow: Flow): E
}


/* ****** Incoming endpoints ****** */

trait InboundEndpoint extends Endpoint {
  type Payload <: Any
  
  def newReceviedMessage[P](payload: P) = Message(payload)
}

object InboundEndpoint {
  implicit class SelectOneWay[S <: Source](val s: S) {
    type SourceOnly = Source {
      type Payload = S#Payload
    }
    def asSource = s.asInstanceOf[SourceOnly]
  }
  implicit class SelectRequestResponse[R <: Responsible](val r: R) {
    type ResponsibleOnly = Responsible {
      type Payload = R#Payload
      type SupportedResponseTypes = R#SupportedResponseTypes
    }
    def asResponsible = r.asInstanceOf[ResponsibleOnly]
  }
}

trait Source extends InboundEndpoint {
  private[this] var onEventHandler0: Message[Payload] => Unit = _
  protected def onEventHandler: Message[Payload] => Unit = onEventHandler0
  def onEvent(thunk: Message[Payload] => Unit) {onEventHandler0 = thunk}
}

trait Responsible extends InboundEndpoint {
  type SupportedResponseTypes <: TypeList
  private[this] var onRequestHandler0: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]] = _
  protected def onRequestHandler: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]] = onRequestHandler0
  def onRequest(thunk: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]])
}


/* ****** Outgoing endpoints ****** */


@scala.annotation.implicitNotFound("This transport does not support messages with payload ${A}. Supported types are ${TL}")
trait TypeSupportedByTransport[TL <: TypeList, A]

object TypeSupportedByTransport extends TypeSelectorImplicits[TypeSupportedByTransport]

trait OutboundEndpoint extends Endpoint {
  type SupportedTypes <: TypeList
  type SupportedType[Payload] = TypeSupportedByTransport[SupportedTypes, Payload]  
}

trait Sink extends OutboundEndpoint {
  def push[Payload: SupportedType](msg: Message[Payload]): Future[Unit]
}

trait Askable extends OutboundEndpoint {
  type Response <: Any
  def ask[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration = 10.seconds): Future[Message[Response]]
}
object Askable {
  implicit class SourceAndSink2Askable[In <: Source, Out <: Sink](t: (In, Out)) extends Askable {
    val out = t._2
    val in = t._1
    type Response = in.Payload
    type SupportedTypes = out.SupportedTypes
    def ask[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration) = {
      out.push(msg)
      val resp = Promise[Message[Response]]()
      flow.scheduleOnce(timeOut)(resp.tryFailure(new TimeoutException()))
      in onEvent resp.trySuccess
      resp.future
    }
    implicit def flow = t._1.flow
    def start() {}
    def dispose() {} //Do not dispose underlying source and sink
  }

  //  @scala.annotation.implicitNotFound("There is no definition that states that ${Req} is replied with ${Resp}")
  //  trait CallDefinition[Req, Resp]
  //  
  //  implicit class AskableTypedPattern(val a: Askable) extends AnyVal {
  //    def typedAsk[Req, Resp](r: Req)(implicit flow: Flow, cd: CallDefinition[Req, Resp], reqct: ClassTag[Req], respct: ClassTag[Resp]): Future[TypedMessage[Resp]] = {
  //      import scala.concurrent.ExecutionContext.Implicits._
  //      a.ask(Message(r)) map {new TypedMessage[Resp](_)}
  //    }
  //  }
}

/* ******* Other Endpoints ****** */

trait PullEndpoint extends Endpoint {
  type Payload <: Any
  def pull()(implicit mf: MessageFactory): Future[Message[Payload]]
}

