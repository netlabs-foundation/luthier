package uy.com.netlabs.luthier

import scala.language._
import language.experimental.macros
import scala.reflect.macros.Context
import scala.concurrent.Future
import typelist._


/**
 * This class defines a scope where flows can be defined.
 */
trait Flows extends FlowsImplicits0 {
  import uy.com.netlabs.luthier.{ Flow => GFlow }
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

  @volatile var registeredFlows = Set.empty[GFlow]

  @scala.annotation.implicitNotFound("There is no ExchangePattern for ${T}.")
  sealed trait ExchangePattern[T <: InboundEndpoint, ResponseType] {
    private[Flows] def setup(t: T, f: Flow[T, ResponseType]): Unit
  }
  object ExchangePattern {
    implicit def OneWay[In <: Source] = new ExchangePattern[In, Unit] {
      private[Flows] def setup(endpoint, flow) {
        endpoint.onEvent(flow.runFlow(_))
      }
    }
    implicit def RequestResponse[In <: Responsible] = new ExchangePattern[In, Future[Message[OneOf[_, In#SupportedResponseTypes]]]] {
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

  private[this] val anonFlowsIncId = new java.util.concurrent.atomic.AtomicLong()
  /**
   * Convenient method to execute a code in a flow run context.
   * A special flow will be constructed for the passed code, it will run it immediately, and the be disposed.
   * This construct is useful when you must perform request using endpoints, instead of defining general purpose flows.
   *
   * '''Notes:''' Al methods that need an implicit MessageFactory, will need to be passed one explicitly (use the message)
   * unless you declare an implicit flowRun like this:
   *
   * {{{<pre>
   *   inFlow {(flow, dummyMsg) =>
   *     implicit val flowRun = dummyMsg.flowRun
   *     //your code here
   *   }
   * </pre>}}}
   * Also, to instantiate endpoints like you do in normal Flows, you need the implicit conversions declared in the flow
   * so its wise to imports its members. Summing up, to properly use this method, you should use it with this preamble:
   * {{{<pre>
   *   inFlow {(flow, dummyMsg) =>
   *     import flow._
   *     implicit val flowRun = dummyMsg.flowRun
   *     //your code here
   *   }
   * </pre>}}}
   *
   * @param code The code to be run in the flow. Its a function wich will be passed the flow instance, as well as the dummy
   *             root message.
   * @return The last expression in the code wrapped in a Future.
   */
  def inFlow[R](code: (Flow[_, Unit], RootMessage[Flow[_, Unit]]) => R): Future[R]= {
    val result = scala.concurrent.Promise[R]()
    val flowName = ("anon" + anonFlowsIncId.incrementAndGet + "@" + new Exception().getStackTrace()(2)).replace("$", "_").replaceAll("[()<>]", ";")
    val flow = new Flow(flowName)(new endpoint.base.DummySource) {
      logLifecycle = false
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

  /**
   * Helper method that starts all the flows registered in this container
   */
  def startAllFlows() {registeredFlows foreach (_.start())}
  /**
   * Helper method that stops all the flows registered in this container
   */
  def stopAllFlows() {registeredFlows foreach (_.dispose())}
}
object Flows {

  def genericInvalidResponseImpl[V, TL <: TypeList](c: Context)(value: c.Expr[V])(implicit valueEv: c.WeakTypeTag[V], tlEv: c.WeakTypeTag[TL]): c.Expr[Future[Message[OneOf[_, TL]]]] = {
    val expectedTypes = TypeList.describe(tlEv)
    c.abort(c.enclosingPosition, "\nInvalid response found: " + valueEv.tpe + ".\n" +
            "Expected a Message[T] or a Future[Message[T]] where T could be any of [" + expectedTypes.mkString("\n    ", "\n    ", "\n]"))
  }
}

/**
 * Trait to be mixed in Flows which provides with implicits for error reporting
 */
private[luthier] sealed trait FlowsImplicits0 extends FlowsImplicits1 {
}
/**
 * Even lower implicits
 */
private[luthier] sealed trait FlowsImplicits1 {
  implicit def genericInvalidResponse[V, TL <: TypeList](value: V): Future[Message[OneOf[_, TL]]] = macro Flows.genericInvalidResponseImpl[V, TL]
}