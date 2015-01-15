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
import language._, language.experimental._
import typelist._
import shapeless._

trait Endpoint {
  def start(): Unit
  def dispose(): Unit
  implicit def flow: Flow
  def appContext = flow.appContext
  def log = flow.log
}
trait EndpointFactory[+E <: Endpoint] extends Equals {
  def apply(flow: Flow): E
}

/* ****** Inbound endpoints ****** */

/**
 * Shared definition to all inbound endpoints.
 */
trait InboundEndpoint extends Endpoint {
  /**
   * Expected body in received messages
   */
  type Payload <: Any
  
  /**
   * Abstract definition of a Throttler. Endpoints which don't support inbound throttling will assign this to unit.
   */
  type Throttler <: Any

  def throttler: Throttler = null.asInstanceOf[Throttler]

  /**
   * This method is intended for InbountEndpoints to instantiate messages with received payloads.
   */
  protected def newReceviedMessage[P](payload: P) = Message(payload)
}

/**
 * Inbound endpoint that represents one way flows.
 */
trait Source extends InboundEndpoint {
  private[this] var onEventHandler0: Message[Payload] => Future[Unit] = _
  protected def onEventHandler: Message[Payload] => Future[Unit] = onEventHandler0
  def onEvent(thunk: Message[Payload] => Future[Unit]) { onEventHandler0 = thunk }
}
object Source {
  type Generic[S <: Source] = Source { type Payload = S#Payload }
  implicit class SelectOneWay[S <: Source](val s: S) {
    def asSource = s.asInstanceOf[Generic[S]]
  }  
}

/**
 * Inbound endpoint that represents request response flows, where when a message arrives, a response is expected.
 */
trait Responsible extends InboundEndpoint {
  type SupportedResponseType <: Any
  private[this] var onRequestHandler0: Message[Payload] => Future[Message[SupportedResponseType]] = _
  protected def onRequestHandler: Message[Payload] => Future[Message[SupportedResponseType]] = onRequestHandler0
  def onRequest(thunk: Message[Payload] => Future[Message[SupportedResponseType]]) { onRequestHandler0 = thunk }
}
object Responsible {
  type Generic[R <: Responsible] = Responsible {
    type Payload = R#Payload
    type SupportedResponseType = R#SupportedResponseType
  }
  implicit class SelectRequestResponse[R <: Responsible](val r: R) {
    def asResponsible = r.asInstanceOf[Generic[R]]
  }
}

/* ****** Outgoing endpoints ****** */

/**
 * Type class used to check wether a type is supported by an endpoint.
 */
@scala.annotation.implicitNotFound("This transport does not support messages with payload ${A}. Supported types are ${TL}")
trait TypeSupportedByTransport[TL <: HList, A]

object TypeSupportedByTransport extends TypeSelectorImplicits[TypeSupportedByTransport]

trait OutboundEndpoint extends Endpoint {
  type SupportedType
  type TypeIsSupported[Payload] = OutboundEndpoint.TypeIsSupported[Payload, SupportedType]
}
object OutboundEndpoint extends LowPriorityTypeIsSupportedImplicits {
  sealed trait TypeIsSupported[Type, SupportedType]
  implicit def typeIsSupportedInHList[Type, H <: HList](implicit ev: TypeSupportedByTransport[H, Type]): TypeIsSupported[Type, H] = null
  implicit def simpleTypeIsSupported[Type, SupportedType](implicit ev: CanBe[Type, SupportedType]): TypeIsSupported[Type, SupportedType] = null
}
trait LowPriorityTypeIsSupportedImplicits {
  implicit def typeIsSupportedError[Type, SupportedType]: OutboundEndpoint.TypeIsSupported[Type, SupportedType] = 
    macro LowPriorityTypeIsSupportedImplicits.typeIsSupportedErrorMacro[Type, SupportedType]
}
object LowPriorityTypeIsSupportedImplicits {
  import scala.reflect.macros.blackbox.Context
  import org.backuity.ansi.AnsiFormatter.FormattedHelper
  
  def typeIsSupportedErrorMacro[TheType: c.WeakTypeTag, SupportedType: c.WeakTypeTag](c: Context): c.Tree = {
    import c.universe._
    val typeTpe = c.weakTypeOf[TheType].dealias
    val supportedTypeTpe = c.weakTypeOf[SupportedType].dealias
    if (supportedTypeTpe <:< typeOf[::[_, _]]) {
      val selectorType = weakTypeOf[TypeSupportedByTransport[_, _]].typeConstructor
      new Macros[c.type](c).noSelectorErrorImpl(Some(selectorType), typeTpe, supportedTypeTpe)
    } else {
      val descriptor = new TypeList.TypeListDescriptor(c.universe)
      val description = descriptor.describeAsString(c.TypeTag(supportedTypeTpe).asInstanceOf[descriptor.universe.TypeTag[_]]).head
      
      c.abort(c.enclosingPosition, s"Could not prove that type $typeTpe can be a $description")
    }
  }
}

trait Pushable extends OutboundEndpoint {
  /**
   * Macro definition for pushImpl that provides nicer error reporting, but in case it succeeds, its equivalent to just call pushImpl
   */
  def push[Payload: TypeIsSupported](msg: Message[Payload]): Future[Unit]
}
object Pushable {
  type Generic[P <: Pushable] = Pushable {
    type SupportedType = P#SupportedType
  }
}

/**
 * This endpoint represents an endpoint to which you can send a message and expect a response in exchange.
 */
trait Askable extends OutboundEndpoint {
  type Response <: Any
  /**
   * Asks the given message await at most timeOut for the response.
   */
  def ask[Payload: TypeIsSupported](msg: Message[Payload], timeOut: FiniteDuration = 10.seconds): Future[Message[Response]]
}
object Askable {
  type Generic[A <: Askable] = Askable {
    type SupportedType = A#SupportedType
    type Response = A#Response
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

/**
 * Base type for endpoints that are able to pull messages on demand
 */
trait Pullable extends Endpoint {
  type Payload <: Any
  def pull()(implicit mf: MessageFactory): Future[Message[Payload]]
}

object Pullable {
  type Generic[P <: Pullable] = Pullable {
    type Payload = P#Payload
  }
}

