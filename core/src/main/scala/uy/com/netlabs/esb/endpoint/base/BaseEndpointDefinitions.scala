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
    for (h <- onEvents) flow.doWork(h(m)).onFailure {case f => appContext.actorSystem.log.error(f, "Error on flow " + flow)}(flow.workerActorsExecutionContext)
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
  protected var onRequests = Set.empty[Message[_] => Future[Message[OneOf[_, SupportedResponseTypes]]]]
  def onRequest(thunk: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]]) {
    onRequests += thunk.asInstanceOf[Message[_] => Future[Message[OneOf[_, SupportedResponseTypes]]]]
  }
  def cancelRequestListener(thunk: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]]) {
    onRequests -= thunk.asInstanceOf[Message[_] => Future[Message[OneOf[_, SupportedResponseTypes]]]]
  }

  val ioExecutionContext: ExecutionContext
  
  protected def requestArrived(m: Message[Payload], messageSender: Try[Message[OneOf[_, SupportedResponseTypes]]] => Unit) {
    implicit val ec = flow.workerActorsExecutionContext
    for (h <- onRequests) {
     flow.doWork {
       val f = h(m)
       f.onComplete(messageSender)(ioExecutionContext) //use ioExecutionContext to sendMessages
       f
     } onFailure {case ex => appContext.actorSystem.log.error(ex, "Error on flow " + flow)}
    }
  }
}

/**
 * Base implementation of PullEndpoint.
 * It implements pull by delegating to the ioExecutionContext a call to the
 * abstract `retrieveMessage()`
 * 
 */
trait BasePullEndpoint extends PullEndpoint {
  val ioExecutionContext: ExecutionContext
  def pull()(implicit mf: MessageFactory): Future[Message[Payload]] = {
    implicit val ec = ioExecutionContext
    Future {retrieveMessage(mf)}
  }
  
  protected def retrieveMessage(mf: MessageFactory): Message[Payload]
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