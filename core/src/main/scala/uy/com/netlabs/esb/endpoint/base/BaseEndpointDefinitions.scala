package uy.com.netlabs.esb
package endpoint.base

import scala.concurrent.{ ExecutionContext, Future }
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

  protected def messageArrived(m: Message[Payload]) {
    onEventHandler(m)
  }
}
class DummySource extends EndpointFactory[DummySource.DummySourceEndpoint] {
  def canEqual(that: Any) = that.asInstanceOf[AnyRef] eq this
  def apply(f: uy.com.netlabs.esb.Flow) = new DummySource.DummySourceEndpoint {
    implicit val flow = f
    def start() {}
    def dispose() {}
    /**
     * Run registered logics asynchronously
     */
    def runLogic() {
      flow.runFlow(newReceviedMessage(()).asInstanceOf[Message[flow.InboundEndpointTpe#Payload]]) 
    }
  }
}
object DummySource {
  trait DummySourceEndpoint extends BaseSource {
    type Payload = Unit
    def runLogic()
  }
}

trait IoExecutionContext {
  val ioProfile: IoProfile
  implicit def ioExecutionContext = ioProfile.executionContext
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
trait BaseResponsible extends Responsible with IoExecutionContext {

  protected def requestArrived(m: Message[Payload], messageSender: Try[Message[OneOf[_, SupportedResponseTypes]]] => Unit) {
    val f = onRequestHandler(m)
    f.onComplete(messageSender)(ioProfile.executionContext) //use ioExecutionContext to sendMessages
    f onFailure { case ex => appContext.actorSystem.log.error(ex, "Error on flow " + flow) }
  }
}

/**
 * Base implementation of PullEndpoint.
 * It implements pull by delegating to the ioExecutionContext a call to the
 * abstract `retrieveMessage()`
 *
 */
trait BasePullEndpoint extends PullEndpoint with IoExecutionContext {
  def pull()(implicit mf: MessageFactory): Future[Message[Payload]] = {
    Future { retrieveMessage(mf) }
  }

  protected def retrieveMessage(mf: MessageFactory): Message[Payload]
}

/**
 * Base implementation of Sink.
 * It implements push by delegating to the ioExecutionContext a call to the
 * abstract `pushMessage()`
 *
 */
trait BaseSink extends Sink with IoExecutionContext {
  def push[Payload: SupportedType](msg: Message[Payload]): Future[Unit] = {
    Future { pushMessage(msg) }
  }

  protected def pushMessage[Payload: SupportedType](msg: Message[Payload])
}