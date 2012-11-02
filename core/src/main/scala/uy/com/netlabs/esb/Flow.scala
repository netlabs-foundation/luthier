package uy.com.netlabs.esb

import scala.language.implicitConversions
import language.experimental._
import akka.actor.{ Actor, Props, Cancellable }
import akka.routing.RoundRobinRouter
import akka.event.LoggingAdapter
import scala.concurrent.{ ExecutionContext, Promise, Future, duration }, duration._
import scala.util._

trait Flow extends Disposable {
  type InboundEndpointTpe <: InboundEndpoint
  
  def name: String
  val appContext: AppContext
  val rootEndpoint: InboundEndpointTpe
  val log: LoggingAdapter
  private[this] var instantiatedEndpoints = Map.empty[EndpointFactory[_], Endpoint]

  def start() {
    rootEndpoint.start()
    log.info("Flow " + name + " started")
  }
  protected def disposeImpl() {
    rootEndpoint.dispose()
    instantiatedEndpoints.values foreach (_.dispose())
    instantiatedEndpoints = Map.empty
    appContext.actorSystem.stop(workerActors)
    blockingExecutor.shutdown()
    log.info("Flow " + name + " disposed")
  }
  /**
   * Bind the life of this flow to that of the disposable.
   */
  def bind(d: Disposable): this.type = {
    d.onDispose(_ => dispose())
    this
  }

  type Logic <: RootMessage[InboundEndpointTpe#Payload] => _
  private[this] var logic: Logic = _
  def logic(l: Logic) {
    logic = l
  }

  def runFlow(rootMessage: Message[InboundEndpointTpe#Payload]): Future[_] = {
    doWork(logic(new RootMessage[InboundEndpointTpe#Payload] with MessageProxy[InboundEndpointTpe#Payload] {
      val peer = rootMessage
      val self = this
      val flowRun = new FlowRun[InboundEndpointTpe#Payload] {
        lazy val rootMessage = self
        val flow = Flow.this
      }
    }))
  }

  implicit def self: this.type = this
  implicit def messageInLogicImplicit: RootMessage[InboundEndpointTpe#Payload] = macro Flow.findNearestMessageMacro[InboundEndpointTpe#Payload]
  implicit def flowRun(implicit rootMessage: RootMessage[InboundEndpointTpe#Payload]) = rootMessage.flowRun
  implicit def endpointFactory2Endpoint[T <: Endpoint](ef: EndpointFactory[T]): T = {
    instantiatedEndpoints.get(ef) match {
      case Some(endpoint) => endpoint.asInstanceOf[T]
      case None =>
        val res = ef(this)
        instantiatedEndpoints += ef -> res
        res.start()
        res
    }
  }

  /**
   * Number of workers to allocate in the flow's worker pool.
   */
  var workers: Int = 5

  lazy val workerActors = appContext.actorSystem.actorOf(Props(new Actor {
    def receive = {
      case w: Flow.Work[_] =>
        try w.promise.complete(Success(w.task()))
        catch { case ex => w.promise.complete(Failure(ex)) }
    }
  }).withRouter(RoundRobinRouter(nrOfInstances = workers)), name.replace(' ', '-') + "-actors")
  /**
   * Executes the passed ``task`` asynchronously in a FlowWorker and returns
   * a Future for the computation.
   */
  def doWork[R](task: => R) = {
    val res = new Flow.Work(() => task)
    workerActors ! res
    res.promise.future
  }
  def scheduleOnce(delay: FiniteDuration)(f: ⇒ Unit): Cancellable = {
    appContext.actorSystem.scheduler.scheduleOnce(delay)(f)(blockingExecutorContext)
  }
  def schedule(initialDelay: FiniteDuration, frequency: FiniteDuration)(f: ⇒ Unit): Cancellable = {
    appContext.actorSystem.scheduler.schedule(initialDelay, frequency)(f)(blockingExecutorContext)
  }
  /**
   * Implicit ExecutionContext so future composition inside a flow
   * declaration delegates work to the actors
   */
  implicit lazy val workerActorsExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable) {
      doWork(runnable.run)
    }
    def reportFailure(t: Throwable) = appContext.actorSystem.log.error(t, "")
  }

  /**
   * Number of workers to allocate in the blocking workers pool.
   * Note that this threadpool is only instantiated if used by a call of blocking.
   */
  var blockingWorkers: Int = 10

  private lazy val blockingExecutor = java.util.concurrent.Executors.newFixedThreadPool(blockingWorkers)
  /**
   * Private executor meant for IO
   */
  private lazy val blockingExecutorContext = ExecutionContext.fromExecutor(blockingExecutor)

  /**
   * Primitive to execute blocking code asynchronously without blocking
   * the flow's worker.
   *
   * @returns A future for the computation
   */
  def blocking[R](code: => R): Future[R] = {
    Future(code)(blockingExecutorContext)
  }
}
object Flow {
  class Work[R](val task: () => R) {
    protected[Flow] val promise = Promise[R]()
  }

  import scala.reflect.macros.{ Context, Universe }
  def findNearestMessageMacro[Payload](c: Context): c.Expr[RootMessage[Payload]] = {
    import c.universe._
    val collected = c.enclosingClass.collect {
      case a @ Apply(Ident(i), List(Function(List(param @ ValDef(modifiers, paramName, _, _)), _))) if i.encoded == "logic" &&
        modifiers.hasFlag(Flag.PARAM) && param.symbol.typeSignature.toString.startsWith("uy.com.netlabs.esb.RootMessage") &&
        !c.typeCheck(c.parse(paramName.encoded), param.symbol.typeSignature, silent = true).isEmpty =>
        paramName
    }.head
    val selectMessage = c.Expr(c.parse(collected.encoded))
    reify(selectMessage.splice)
  }

}