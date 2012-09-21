package uy.com.netlabs.esb

import scala.language.implicitConversions
import akka.actor.{ Actor, Props, Cancellable }
import akka.routing.RoundRobinRouter
import akka.event.LoggingAdapter
import scala.concurrent.{ ExecutionContext, Promise, Future, util }, util.Duration
import scala.util._

trait Flow {
  def name: String
  val appContext: AppContext
  val rootEndpoint: InboundEndpoint
  val log: LoggingAdapter
  private[this] var instantiatedEndpoints = Map.empty[EndpointFactory[_], Endpoint]

  def start() {
    rootEndpoint.start()
  }
  def stop() {
    rootEndpoint.dispose()
    instantiatedEndpoints.values foreach (_.dispose())
    instantiatedEndpoints = Map.empty
    appContext.actorSystem.stop(workerActors)
    blockingExecutor.shutdown()
  }

  type Logic <: Message[rootEndpoint.Payload] => _
  def logic(l: Logic)

  implicit def self = this
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
    workerActors tell res
    res.promise.future
  }
  def scheduleOnce(delay: Duration)(f: ⇒ Unit): Cancellable = {
    appContext.actorSystem.scheduler.scheduleOnce(delay)(f)(blockingExecutorContext)
  }
  def schedule(initialDelay: Duration, frequency: Duration)(f: ⇒ Unit): Cancellable = {
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
}