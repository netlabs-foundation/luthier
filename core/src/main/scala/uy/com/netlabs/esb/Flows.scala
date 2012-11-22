package uy.com.netlabs.esb

import scala.language._
import language.experimental.macros
import scala.reflect.macros.Context
import scala.concurrent.Future
import typelist._


/**
 * This class defines a scope where flows can be defined.
 */
trait Flows extends FlowsImplicits0 {
  import uy.com.netlabs.esb.{ Flow => GFlow }
  implicit def appContext: AppContext
  //from message stright to future
  implicit def message2FutureOneOf[MT, TL <: TypeList](m: Message[MT])(implicit contained: Contained[TL, MT]): Future[Message[OneOf[_, TL]]] = {
    Future.successful(m map (p =>  new OneOf[MT, TL](p)))
  }
  //future to future
  implicit def futureMessage2FutureOneOf[MT, TL <: TypeList](f: Future[Message[MT]])(implicit contained: Contained[TL, MT]): Future[Message[OneOf[_, TL]]] = {
    f.map (m => m.map (p => new OneOf(p): OneOf[_, TL]))(appContext.actorSystem.dispatcher)
  }
  implicit val flowLogSource = new akka.event.LogSource[GFlow] {
    def genString(f) = f.appContext.name + ":" + f.name
  }

//  implicit def SelectRequestResponse[R <: Responsible](r: EndpointFactory[R]) = new Flows.SelectRequestResponse(r)
//  implicit def SelectOneWay[S <: Source](s: EndpointFactory[S]) = new Flows.SelectOneWay(s)

  @volatile var registeredFlows = Set.empty[GFlow]

  @scala.annotation.implicitNotFound("There is no ExchangePattern for ${T}.")
  sealed trait ExchangePattern[T <: InboundEndpoint, ResponseType] {
    private[Flows] def setup(t: T, f: Flow[T, ResponseType]): Unit
  }
  object ExchangePattern {
    implicit def OneWayCommunicationPattern[In <: Source] = new ExchangePattern[In, Unit] {
      private[Flows] def setup(endpoint, flow) {
        endpoint.onEvent(flow.runFlow(_))
      } 
    }
    implicit def RequestResponseCommunicationPattern[In <: Responsible] = new ExchangePattern[In, Future[Message[OneOf[_, In#SupportedResponseTypes]]]] {
      private[Flows] def setup(endpoint, flow) {
        endpoint.onRequest{m =>
          val res = flow.runFlow(m).mapTo[Future[Message[OneOf[_, endpoint.SupportedResponseTypes]]]] //the result of running the flow is the one this is being mappedTo, but is encapsulated in the florRun future, hence, flatten it
          res.flatMap(identity)(flow.workerActorsExecutionContext)
        }
      }
    }
  }

  abstract class Flow[E <: InboundEndpoint, ResponseType](val name: String)(endpoint: EndpointFactory[E])(implicit val exchangePattern: ExchangePattern[E, ResponseType]) extends GFlow {
    registeredFlows += this
    type InboundEndpointTpe = E
    type Logic = RootMessage[this.type] => ResponseType
    type LogicResult = ResponseType
    val appContext = Flows.this.appContext
    val log = akka.event.Logging(appContext.actorSystem, this)
    val flowsContext = Flows.this //backreference
    val rootEndpoint = endpoint(this)
    exchangePattern.setup(rootEndpoint, this)
  }
  
  def inFlow[R](code: (Flow[_, Unit], Message[Unit]) => R): Future[R]= {
    val result = scala.concurrent.Promise[R]()
    val flowName = ("anon@" + new Exception().getStackTrace()(2)).replace("$", "_").replaceAll("[()<>]", ";")
    val flow = new Flow(flowName)(new endpoint.base.DummySource) {
      logic {m => 
        try result.success(code(this, m))
        catch {case ex: Exception => result.failure(ex)}
      }
    }
    flow.rootEndpoint.runLogic
    val res = result.future
    res.onComplete(_ => flow.dispose())(flow.workerActorsExecutionContext) // code already got executed, can request the flow to stop
    res
  }

}
object Flows {
//  class SelectRequestResponse[R <: Responsible](val r: EndpointFactory[R]) {
//    type RR = Responsible { 
//      type Payload = R#Payload
//      type SupportedResponseTypes = R#SupportedResponseTypes
//    }
//    def RequestResponse: EndpointFactory[RR] = r.asInstanceOf[EndpointFactory[RR]]
//  }
//  class SelectOneWay[S <: Source](val s: EndpointFactory[S]) {
//    type SS = Source { type Payload = S#Payload }
//    def OneWay: EndpointFactory[SS] = s.asInstanceOf[EndpointFactory[SS]]
//  }
  
  
  def genericInvalidResponseImpl[V, TL <: TypeList](c: Context)(value: c.Expr[V])(implicit valueEv: c.WeakTypeTag[V], tlEv: c.WeakTypeTag[TL]): c.Expr[Future[Message[OneOf[_, TL]]]] = {
    val expectedTypes = TypeList.describe(tlEv)
    c.abort(c.enclosingPosition, "\nInvalid response found: " + valueEv.tpe + ".\n" + 
         "Expected a Message[T] or a Future[Message[T]] where T could be any of [" + expectedTypes.mkString("\n    ", "\n    ", "\n]"))
  }
}

/**
 * Trait to be mixed in Flows which provides with implicits for error reporting
 */
private[esb] sealed trait FlowsImplicits0 extends FlowsImplicits1 {
}
/**
 * Even lower implicits
 */
private[esb] sealed trait FlowsImplicits1 {
  implicit def genericInvalidResponse[V, TL <: TypeList](value: V): Future[Message[OneOf[_, TL]]] = macro Flows.genericInvalidResponseImpl[V, TL]
}