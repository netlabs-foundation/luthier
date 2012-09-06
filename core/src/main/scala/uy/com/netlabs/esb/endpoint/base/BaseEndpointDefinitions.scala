package uy.com.netlabs.esb
package endpoint.base

import scala.concurrent.{ExecutionContext, Future}
import scala.util._
import typelist._

/**
 * Base implementation of Source.
 * The handlers registered with onEvent are stored in a set, and a job is tasked for each
 * with the arrived message.
 * Implementors that extend this trait should call `messageArrived` with the actual message
 * from the logic that actually receives it.
 *
 * For example, a JMS Source would register a consumer, and upon message arrival call
 * `messageArrived`
 */
trait BaseSource extends Source {
  protected var onEvents = Set.empty[Message[_] => Unit]
  def onEvent(thunk: Message[Payload] => Unit) { onEvents += thunk.asInstanceOf[Message[_] => Unit] }
  def cancelEventListener(thunk: Message[Payload] => Unit) { onEvents -= thunk.asInstanceOf[Message[_] => Unit] }

  protected def messageArrived(m: Message[Payload]) {
    implicit val ec = flow.workerActorsExecutionContext
    for (h <- onEvents) flow.doWork(h(m)) onFailure {case f => appContext.actorSystem.log.error(f, "Error on flow " + flow)}
  }
}

/**
 * Base implementation of Responsible.
 * The handlers registered with onRequest are stored in a set, and a job is tasked for each
 * with the arrived message.
 * Implementors that extend this trait should call `requestArrived` with the actual message
 * from the logic that actually receives it.
 *
 * For example, a JMS Responsible would register a consumer, and upon message arrival call
 * `requestArrived`.
 * 
 */
trait BaseResponsible extends Responsible {
  protected var onRequests = Set.empty[Message[_] => Future[Message[_]]]
  def onRequest(thunk: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]]) {
    onRequests += thunk.asInstanceOf[Message[_] => Future[Message[OneOf[_, SupportedResponseTypes]]]]
  }
  def cancelRequestListener(thunk: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]]) {
    onRequests -= thunk.asInstanceOf[Message[_] => Future[Message[OneOf[_, SupportedResponseTypes]]]]
  }

  val ioExecutionContext: ExecutionContext
  
  protected def requestArrived(m: Message[Payload]) {
    implicit val ec = flow.workerActorsExecutionContext
    for (h <- onRequests) {
     flow.doWork {
       val f = h(m)
       f.onComplete {
         case Success(m) => sendMessage(m)
         case Failure(ex) => appContext.actorSystem.log.error(ex, "Error on flow " + flow)
       } (ioExecutionContext) //use ioExecutionContext to sendMessages
       f
     } onFailure {case ex => appContext.actorSystem.log.error(ex, "Error on flow " + flow)}
    }
  }
  protected def sendMessage(m: Message[_]): Unit
}

/**
 * Base implementation of PullEndpoint.
 * It implements pull by delegating to the ioExecutionContext a call to the
 * abstract `retrieveMessage()`
 * 
 */
trait BasePullEndpoint extends PullEndpoint {
  val ioExecutionContext: ExecutionContext
  def pull(): Future[Message[Payload]] = {
    implicit val ec = ioExecutionContext
    Future {retrieveMessage}
  }
  
  protected def retrieveMessage(): Message[Payload]
}

/**
 * Base implementation of Sink.
 * It implements push by delegating to the ioExecutionContext a call to the
 * abstract `pushMessage()`
 * 
 */
trait BaseSink extends Sink {
  val ioExecutionContext: ExecutionContext
  def push[Payload: SupportedType](msg: Message[Payload]): Future[Unit] = {
    implicit val ec = ioExecutionContext
    Future {pushMessage(msg)}
  }
  
  protected def pushMessage[Payload: SupportedType](msg: Message[Payload])
}