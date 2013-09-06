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
package endpoint
package base

import typelist._
import akka.actor._
import scala.concurrent._, scala.util._, scala.concurrent.duration._
import scala.reflect.{ClassTag, classTag}

/**
 * TODO: document
 */
class VM private[VM](val appContext: AppContext) {

  //Supervisor of all instantiated endpoints
  private[VM] class SetupEndpoint(val actorFactory: () => Actor, val actorName: String)
  private[VM] class KillEndpoint(val ref: ActorRef)
  private[VM] val endpointsSupervisor = try {
    appContext.actorSystem.actorOf(Props(new Actor {
          def receive = {
            case s: SetupEndpoint =>
              val ref = context.actorOf(Props(s.actorFactory()), s.actorName)
              println(ref + " created")
              sender ! ref
            case k: KillEndpoint =>
              sender ! context.stop(k.ref)
          }
        }), "VM")
  } catch {case e: InvalidActorNameException =>
      //the supervisor is already there, so just reference it
      appContext.actorSystem.actorFor("user/VM")
  }

  //////////////////////////////////////////////////////////////////////
  // Inbound endpoints
  //////////////////////////////////////////////////////////////////////

  trait VmInboundEndpointBase extends InboundEndpoint {
    /**
     * Subpath of the actor that represents this endpoint.
     */
    def actorPath: String

    @volatile private[this] var _endpointActor: ActorRef = _
    def endpointActor = _endpointActor

    protected def newReceiverActor: Actor

    def start() {
      val creationFuture = akka.pattern.ask(endpointsSupervisor).ask(new SetupEndpoint(() => newReceiverActor, actorPath))(500.millis).
      map(e => e.asInstanceOf[ActorRef])(appContext.actorSystem.dispatcher)
      _endpointActor = Await.result(creationFuture, 500.millis)
    }
    def dispose() {
      val killFuture = akka.pattern.ask(endpointsSupervisor).ask(new KillEndpoint(_endpointActor))(500.millis)
      Await.result(killFuture, 500.millis)
    }
  }


  class VMSourceEndpoint[ExpectedType: ClassTag] private[VM](val flow: Flow, val actorPath: String) extends BaseSource with VmInboundEndpointBase {
    type Payload = ExpectedType
    val expectedTypeClass = classTag[ExpectedType].runtimeClass
    def newReceiverActor = new Actor {
      def receive = {
        case msg =>
          try {
            messageArrived(newReceviedMessage(expectedTypeClass.cast(msg).asInstanceOf[Payload]))
          } catch {case e: ClassCastException =>
              log.error(s"Received on $actorPath a message of type ${msg.getClass.getName} but this actor is typed as ${expectedTypeClass.getName}. Message ignored")
          }
      }
    }
  }
  case class SourceEndpointFactory[ExpectedType: ClassTag] private[VM](actorPath: String) extends EndpointFactory[VMSourceEndpoint[ExpectedType]] {
    def apply(f) = new VMSourceEndpoint[ExpectedType](f, actorPath)
  }

  def source[ExpectedType: ClassTag](actorPath: String) = SourceEndpointFactory[ExpectedType](actorPath)

  class VMResponsibleEndpoint[ReqType: ClassTag, ResponseType <: TypeList] private[VM](
    val flow: Flow, val actorPath: String) extends Responsible with VmInboundEndpointBase {
    type Payload = ReqType
    type SupportedResponseTypes <: ResponseType
    val expectedTypeClass = classTag[ReqType].runtimeClass
    def newReceiverActor = new Actor {
      def receive = {
        case msg =>
          try {
            requestArrived(newReceviedMessage(expectedTypeClass.cast(msg).asInstanceOf[Payload]), self, sender)
          } catch {case e: ClassCastException =>
              log.error(s"Received on $actorPath a message of type ${msg.getClass.getName} but this actor is typed as ${expectedTypeClass.getName}. Message ignored")
          }
      }
    }

    private def requestArrived(m: Message[Payload], self: ActorRef, requestor: ActorRef) {
      implicit val ec = appContext.actorSystem.dispatcher
      val f = onRequestHandler(m)
      f.onComplete {
        case Success(msg) => requestor.tell(msg.payload.value, self)
        case Failure(err) => requestor.tell(err, self)
      }
      f onFailure { case ex => log.error(ex, "Error on flow " + flow) }
    }
  }
  case class ResponsibleEndpointFactory[ReqType: ClassTag, ResponseType <: TypeList] private[VM](actorPath: String) extends EndpointFactory[VMResponsibleEndpoint[ReqType, ResponseType]] {
    def apply(f) = new VMResponsibleEndpoint[ReqType, ResponseType](f, actorPath)
  }

  def responsible[ReqType: ClassTag, ResponseType <: TypeList](actorPath: String) = ResponsibleEndpointFactory[ReqType, ResponseType](actorPath)


  //////////////////////////////////////////////////////////////////////
  // Outbound endpoints
  //////////////////////////////////////////////////////////////////////

  class VmOutboundEndpoint[OutSupportedTypes <: TypeList, ExpectedResponse] private[VM](
    val flow: Flow, val actorPath: String) extends Sink with Askable {
    type SupportedTypes = OutSupportedTypes
    type Response = ExpectedResponse
    protected def destActor = appContext.actorSystem.actorFor(actorPath)
    def start() {}
    def dispose() {}
    def pushImpl[Payload: SupportedType](msg: Message[Payload]): Future[Unit] = Future.successful(destActor.tell(msg.payload, null))
    def askImpl[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration): Future[Message[Response]] = {
      akka.pattern.ask(destActor).?(msg.payload)(timeOut).map(r => msg.map(_ => r.asInstanceOf[Response]))(appContext.actorSystem.dispatcher)
    }
  }
  case class VmOutboundEndpointFactory[OutSupportedTypes <: TypeList, ExpectedResponse] private[VM](
    actorPath: String) extends EndpointFactory[VmOutboundEndpoint[OutSupportedTypes, ExpectedResponse]] {
    def apply(f) = new VmOutboundEndpoint[OutSupportedTypes, ExpectedResponse](f, actorPath)
  }

  def sink[Out](actorPath: String) = VmOutboundEndpointFactory[Out :: TypeNil, Any](actorPath)
  def ref[Out, ExpectedResponse](actorPath: String) = VmOutboundEndpointFactory[Out :: TypeNil, ExpectedResponse](actorPath)
}
object VM {
  def forAppContext(ac: AppContext) = new VM(ac)
}