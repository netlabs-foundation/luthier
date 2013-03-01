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

import scala.concurrent._
import scala.concurrent.duration._
import language.implicitConversions
import typelist._

trait Endpoint {
  def start(): Unit
  def dispose(): Unit
  implicit def flow: Flow
  def appContext = flow.appContext
  def log = appContext.actorSystem.log
}
trait EndpointFactory[+E <: Endpoint] extends Equals {
  def apply(flow: Flow): E
}

/* ****** Incoming endpoints ****** */

trait InboundEndpoint extends Endpoint {
  type Payload <: Any

  def newReceviedMessage[P](payload: P) = Message(payload)
}

object InboundEndpoint {
  implicit class SelectOneWay[S <: Source](val s: S) {
    type SourceOnly = Source {
      type Payload = S#Payload
    }
    def asSource = s.asInstanceOf[SourceOnly]
  }
  implicit class SelectRequestResponse[R <: Responsible](val r: R) {
    type ResponsibleOnly = Responsible {
      type Payload = R#Payload
      type SupportedResponseTypes = R#SupportedResponseTypes
    }
    def asResponsible = r.asInstanceOf[ResponsibleOnly]
  }
}

trait Source extends InboundEndpoint {
  private[this] var onEventHandler0: Message[Payload] => Unit = _
  protected def onEventHandler: Message[Payload] => Unit = onEventHandler0
  def onEvent(thunk: Message[Payload] => Unit) { onEventHandler0 = thunk }
}

trait Responsible extends InboundEndpoint {
  type SupportedResponseTypes <: TypeList
  private[this] var onRequestHandler0: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]] = _
  protected def onRequestHandler: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]] = onRequestHandler0
  def onRequest(thunk: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]]) { onRequestHandler0 = thunk }
}

/* ****** Outgoing endpoints ****** */

@scala.annotation.implicitNotFound("This transport does not support messages with payload ${A}. Supported types are ${TL}")
trait TypeSupportedByTransport[TL <: TypeList, A]

object TypeSupportedByTransport extends TypeSelectorImplicits[TypeSupportedByTransport]

trait OutboundEndpoint extends Endpoint {
  type SupportedTypes <: TypeList
  type SupportedType[Payload] = TypeSupportedByTransport[SupportedTypes, Payload]
}

trait Sink extends OutboundEndpoint {
  def push[Payload: SupportedType](msg: Message[Payload]): Future[Unit]
}

trait Askable extends OutboundEndpoint {
  type Response <: Any
  def ask[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration = 10.seconds): Future[Message[Response]]
}
object Askable {
  implicit class SourceAndSink2Askable[In <: Source, Out <: Sink](t: (In, Out)) extends Askable {
    val out = t._2
    val in = t._1
    type Response = in.Payload
    type SupportedTypes = out.SupportedTypes
    def ask[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration) = {
      out.push(msg)
      val resp = Promise[Message[Response]]()
      flow.scheduleOnce(timeOut)(resp.tryFailure(new TimeoutException()))
      in onEvent resp.trySuccess
      resp.future
    }
    implicit def flow = t._1.flow
    def start() {}
    def dispose() {} //Do not dispose underlying source and sink
  }

  //  @scala.annotation.implicitNotFound("There is no definition that states that ${Req} is replied with ${Resp}")
  //  trait CallDefinition[Req, Resp]
  //
  //  implicit class AskableTypedPattern(val a: Askable) extends AnyVal {
  //    def typedAsk[Req, Resp](r: Req)(implicit flow: Flow, cd: CallDefinition[Req, Resp], reqct: ClassTag[Req], respct: ClassTag[Resp]): Future[TypedMessage[Resp]] = {
  //      import scala.concurrent.ExecutionContext.Implicits._
  //      a.ask(Message(r)) map {new TypedMessage[Resp](_)}
  //    }
  //  }
}

/* ******* Other Endpoints ****** */

trait PullEndpoint extends Endpoint {
  type Payload <: Any
  def pull()(implicit mf: MessageFactory): Future[Message[Payload]]
}

