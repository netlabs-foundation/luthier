package uy.com.netlabs.esb

import scala.language.implicitConversions
import akka.actor.{ Actor, Props, Cancellable }
import akka.routing.RoundRobinRouter
import scala.concurrent.{ ExecutionContext, Promise, Future, util }, util.Duration
import scala.util._

trait Flow {
  def name: String
  val appContext: AppContext
  val rootEndpoint: InboundEndpoint

  def start() { rootEndpoint.start() }
  def stop() {
    rootEndpoint.dispose()
    appContext.actorSystem.stop(workerActors)
  }

  type Logic <: Message[rootEndpoint.Payload] => _
  def logic(l: Logic)

  implicit def self = this
  implicit def endpointFactory2Endpoint[T <: Endpoint](ef: EndpointFactory[T]): T = ef(this)

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
    appContext.actorSystem.scheduler.scheduleOnce(delay)(f)(workerActorsExecutionContext)
  }
  def schedule(initialDelay: Duration, frequency: Duration)(f: ⇒ Unit): Cancellable = {
    appContext.actorSystem.scheduler.schedule(initialDelay, frequency)(f)(workerActorsExecutionContext)
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

  /**
   * Private executor meant for IO
   */
  private lazy val blockingExecutor = ExecutionContext.fromExecutor(java.util.concurrent.Executors.newFixedThreadPool(blockingWorkers))

  /**
   * Primitive to execute blocking code asynchronously without blocking
   * the flow's worker.
   *
   * @returns A future for the computation
   */
  def blocking[R](code: => R): Future[R] = {
    Future(code)(blockingExecutor)
  }
}
object Flow {
  class Work[R](val task: () => R) {
    protected[Flow] val promise = Promise[R]()
  }
}