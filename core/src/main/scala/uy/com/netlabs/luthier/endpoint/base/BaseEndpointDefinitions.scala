/**
 * Copyright (c) 2013, Netlabs S.R.L. <contacto@netlabs.com.uy>
 * All rights reserved.
 *
 * This software is dual licensed as GPLv2: http://gnu.org/licenses/gpl-2.0.html,
 * and as the following 3-clause BSD license. In other words you must comply to
 * either of them to enjoy the permissions they grant over this software.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name "netlabs" nor the names of its contributors may be
 *       used to endorse or promote products derived from this software
 *       without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL NETLABS S.R.L.  BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uy.com.netlabs.luthier
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
  def apply(f: uy.com.netlabs.luthier.Flow) = new DummySource.DummySourceEndpoint {
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

//VMEndpoints are meant to be called via runFlow, since they will generate no messages.
/**
 * VM objects contains two definitions of endpoint factories, source and responsible.
 * They are both VM endpoints which dont actually generate any Message. Their intended
 * usage is to have typed flows to be used internally by reference with runFlow.
 */
object VM {

  class VMSourceEndpoint[ExpectedType] private[VM](val flow: Flow) extends BaseSource {
    type Payload = ExpectedType
    def start() {}
    def dispose() {}
  }
  case class SourceEndpointFactory[ExpectedType] private[VM]() extends EndpointFactory[VMSourceEndpoint[ExpectedType]] {
    def apply(f) = new VMSourceEndpoint[ExpectedType](f)
  }

  def source[ExpectedType] = SourceEndpointFactory[ExpectedType]()

  class VMResponsibleEndpoint[ReqType, ResponseType <: TypeList] private[VM](val flow: Flow) extends Responsible {
    type Payload = ReqType
    type SupportedResponseTypes <: ResponseType
    def start() {}
    def dispose() {}
  }
  case class ResponsibleEndpointFactory[ReqType, ResponseType <: TypeList] private[VM]() extends EndpointFactory[VMResponsibleEndpoint[ReqType, ResponseType]] {
    def apply(f) = new VMResponsibleEndpoint[ReqType, ResponseType](f)
  }

  def responsible[ReqType, ResponseType <: TypeList] = ResponsibleEndpointFactory[ReqType, ResponseType]()
}