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
package logical

import typelist._
import scala.concurrent._, duration._

/**
 * A Throttler is an entity which has state and can tell you whether you should
 * proceed with an operation or backoff somehow.
 */
trait Throttler extends Disposable {
  /**
   * Asks the throttler for an operation slot, which might not be available.
   */
  def acquireSlot(): Option[Throttler.Slot]
  /**
   * Best effort to detect if there are slots available. Due to concurrent nature of a throttler
   * the value might not be true with respect to the acquireSlot.
   */
  def isSlotAvailableHint: Boolean
  def start()
  @volatile private var slotListeners = Vector.empty[() => Unit]
  def registerSlotListener(listener: => Unit) {
    slotListeners :+= (() => listener)
  }
  def notifySlotAvailable() {
    slotListeners foreach (_())
  }
}
object Throttler {
  trait Slot {
    def startTime: Long
    def markCompleted()
  }
}

/**
 * Action to take when an operation should be throttled.
 * @param T Specificies with which inbound endpoint type the action is compatible.
 */
sealed trait ThrottlingAction[-T <: Endpoint]
object ThrottlingAction {
  /**
   * Signals that the operation should be discarded.
   */
  case object Drop extends ThrottlingAction[Endpoint]
  /**
   * Signals that the operation should result in the given response.
   */
  case class Reply[T, R <: Responsible](resp: T)(implicit ev: Contained[R#SupportedResponseTypes, T]) extends ThrottlingAction[R]
  /**
   * Signals that the operation should be backed off using the given backoff function.
   */
  case class Backoff[T <: Endpoint](initialBackoff: FiniteDuration, f: FiniteDuration => Option[FiniteDuration], rejectAction: ThrottlingAction[T]) extends ThrottlingAction[T]
  /**
   * Signals that the operation should be stored and retried later when there is a slot available.
   */
  case class Enqueue[T <: Endpoint](maxQueueSize: Int, rejectAction: ThrottlingAction[T]) extends ThrottlingAction[T] {
    val queue = new java.util.concurrent.ArrayBlockingQueue[(Message[_], Promise[_], Any)](maxQueueSize)
    override def hashCode = java.util.Objects.hashCode(this)
    override def equals(that: Any) = this eq that.asInstanceOf[AnyRef]
  }
}

object Throttling {

  private[Throttling] trait ThrottlingEndpoint extends Endpoint {
    import ThrottlingAction._
    protected type Res
    protected type Payload
    type Underlying <: Endpoint
    def underlying: Underlying
    protected def throttler: Throttler
    protected def throttlingAction: ThrottlingAction[Underlying]
    protected type HandlerArgs
    protected def handler(args: HandlerArgs): Message[Payload] => Future[Res]

    protected def handle(msg: Message[Payload], throttlingAction: ThrottlingAction[Underlying], args: HandlerArgs): Future[Res] = {
      throttler.acquireSlot match {
        case Some(slot) =>
          val r = handler(args)(msg)
          r.onComplete { case _ => slot.markCompleted()}(flow.rawWorkerActorsExecutionContext)
          r
        case None =>
          (throttlingAction: @unchecked) match {
            case Drop =>
              log.info(s"Dropping message $msg due to throttling.")
              Future.failed(new Exception("Message dropped"))
            case Reply(resp) =>
              log.info(s"Replying with $resp due to throttling.")
              Future.successful(msg.map (_ => new OneOf[Any, TypeNil](resp)(null)).asInstanceOf[Res])
            case Backoff(id, f, rejectAction) => backoff(id, f, msg, rejectAction, args)
            case e@Enqueue(_, rejectAction) =>
              val res = Promise[Res]
              if (!e.queue.offer((msg, res, args))) {
                log.info(s"Applying rejectAction to $msg due to queue being full")
                handle(msg, rejectAction, args)
              } else {
                log.info(s"Enqueing $msg due to throttling")
                res.future
              }
          }
      }
    }
    protected def backoff(d: FiniteDuration, next: FiniteDuration => Option[FiniteDuration], msg: Message[Payload],
                          rejectAction: ThrottlingAction[Underlying], args: HandlerArgs): Future[Res] = {
      log.info(s"Backing off for $d message $msg due to throttling.")
      val res = Promise[Res]
      flow.scheduleOnce(d) {
        throttler.acquireSlot match {
          case Some(slot) =>
            val r = handler(args)(msg)
            r.onComplete { case _ => slot.markCompleted()}(flow.rawWorkerActorsExecutionContext)
            res completeWith r
          case None => next(d) match {
              case Some(d) => res completeWith backoff(d, next, msg, rejectAction, args)
              case None =>
                log.info(s"Applying rejectAction to $msg after full backoff failed.")
                res completeWith handle(msg, rejectAction, args)
            }
        }
      }
      res.future
    }
    def dispose(): Unit = {
      underlying.dispose()
      throttler.dispose()
    }
    def start(): Unit = {
      def registerSlotListener(action: ThrottlingAction[Underlying]) {
        action match {
          case e@Enqueue(_, rejectAction) =>
            throttler.registerSlotListener {
              val q = e.queue
              var done = false
              while (!done && throttler.isSlotAvailableHint) {
                q.poll() match {
                  case null => done = true
                  case (msg, promise: Promise[Res] @unchecked, args: HandlerArgs @unchecked) =>
                    promise completeWith handle(msg.as[Payload], action, args)
                }
              }
            }
            registerSlotListener(rejectAction)
          case Backoff(_, _, rejectAction) => registerSlotListener(rejectAction)
          case _ =>
        }
      }
      registerSlotListener(throttlingAction)

      throttler.start()
      underlying.start()
    }
  }

  ////////////////////////
  // Inbound Endpoints
  ////////////////////////


  class SourceThrottlingEndpoint[S <: Source](val flow: Flow, val underlying: S,
                                              override val throttler: Throttler,
                                              val throttlingAction: ThrottlingAction[S]) extends Source with ThrottlingEndpoint {
    override def handler(args) = onEventHandler
    underlying.onEvent(handle(_: Message[Payload], throttlingAction, ()))

    protected type Res = Unit
    type Throttler = logical.Throttler
    type Underlying = S
    protected type HandlerArgs = Unit
    type Payload = underlying.Payload
  }
  class ResponsibleThrottlingEndpoint[R <: Responsible](val flow: Flow, val underlying: R,
                                                        override val throttler: Throttler,
                                                        val throttlingAction: ThrottlingAction[R]) extends Responsible with ThrottlingEndpoint {

    def handler(args) = onRequestHandler
    underlying.onRequest(handle(_: Message[Payload], throttlingAction, ()))

    protected type Res = Message[OneOf[_, underlying.SupportedResponseTypes]]
    type Throttler = logical.Throttler
    type Underlying = R
    protected type HandlerArgs = Unit
    type Payload = underlying.Payload
    type SupportedResponseTypes = underlying.SupportedResponseTypes
  }

  case class SourceEF[S <: Source](source: EndpointFactory[S], throttler: Throttler,
                                   action: ThrottlingAction[S]) extends EndpointFactory[SourceThrottlingEndpoint[S]] {
    def apply(f) = new SourceThrottlingEndpoint(f, source(f), throttler, action)
  }
  case class ResponsibleEF[R <: Responsible](responsible: EndpointFactory[R], throttler: Throttler,
                                             action: ThrottlingAction[R]) extends EndpointFactory[ResponsibleThrottlingEndpoint[R]] {
    def apply(f) = new ResponsibleThrottlingEndpoint(f, responsible(f), throttler, action)
  }
  /**
   * @usecase def apply[S <: Source](source: EndpointFactory[S])(throttler: Throttler, action: ThrottlingAction[S]): EndpointFactory[SourceThrottlingEndpoint[S]]
   */
  def apply[S <: Source](source: EndpointFactory[S])(throttler: Throttler, action: ThrottlingAction[S])(implicit d1: DummyImplicit) = SourceEF(source, throttler, action)

  /**
   * @usecase def apply[R <: Responsible](responsible: EndpointFactory[R])(throttler: Throttler, action: ThrottlingAction[R]): EndpointFactory[ResponsibleThrottlingEndpoint[R]]
   */
  def apply[R <: Responsible](responsible: EndpointFactory[R])(throttler: Throttler, action: ThrottlingAction[R])(implicit d1: DummyImplicit, d2: DummyImplicit) = ResponsibleEF(responsible, throttler, action) //dummies are a hack to have proper overloading despite the java generics


  ////////////////////////
  // Outbound Endpoints
  ////////////////////////

  class SinkThrottlingEndpoint[S <: Sink](val flow: Flow, val underlying: S,
                                          override val throttler: Throttler,
                                          val throttlingAction: ThrottlingAction[S]) extends Sink with ThrottlingEndpoint {

    protected type Res = Unit
    type Throttler = logical.Throttler
    type Underlying = S
    protected type HandlerArgs = Unit
    protected type Payload = Any
    type SupportedTypes = underlying.SupportedTypes

    def handler(args) = underlying.pushImpl(_)(null)
    def pushImpl[Payload: SupportedType](msg: Message[Payload]): Future[Unit] = handle(msg, throttlingAction, ())
  }
  class AskableThrottlingEndpoint[A <: Askable](val flow: Flow, val underlying: A,
                                                override val throttler: Throttler,
                                                val throttlingAction: ThrottlingAction[A]) extends Askable with ThrottlingEndpoint {

    protected type Res = Message[Response]
    type Throttler = logical.Throttler
    type Underlying = A
    protected type HandlerArgs = FiniteDuration
    protected type Payload = Any
    type SupportedTypes = underlying.SupportedTypes
    type Response = underlying.Response

    def handler(timeOut) = underlying.askImpl(_, timeOut)(null)
    def askImpl[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration): Future[Message[Response]] =
      handle(msg, throttlingAction, timeOut)
  }
  case class SinkEF[S <: Sink](sink: EndpointFactory[S], throttler: Throttler,
                               action: ThrottlingAction[S]) extends EndpointFactory[SinkThrottlingEndpoint[S]] {
    def apply(f) = new SinkThrottlingEndpoint(f, sink(f), throttler, action)
  }
  case class AskableEF[A <: Askable](askable: EndpointFactory[A], throttler: Throttler,
                                     action: ThrottlingAction[A]) extends EndpointFactory[AskableThrottlingEndpoint[A]] {
    def apply(f) = new AskableThrottlingEndpoint(f, askable(f), throttler, action)
  }
  /**
   * @usecase def apply[S <: Sink](sink: EndpointFactory[S])(throttler: Throttler, action: ThrottlingAction[S]): EndpointFactory[SinkThrottlingEndpoint[S]]
   */
  def apply[S <: Sink](sink: EndpointFactory[S])(throttler: Throttler, action: ThrottlingAction[S])(implicit d1: DummyImplicit, d2: DummyImplicit, d3: DummyImplicit) = SinkEF(sink, throttler, action)

  /**
   * @usecase def apply[A <: Askable](askable: EndpointFactory[A])(throttler: Throttler, action: ThrottlingAction[A]): EndpointFactory[AskableThrottlingEndpoint[A]]
   */
  def apply[A <: Askable](askable: EndpointFactory[A])(throttler: Throttler, action: ThrottlingAction[A])(implicit d1: DummyImplicit, d2: DummyImplicit, d3: DummyImplicit, d4: DummyImplicit) = AskableEF(askable, throttler, action) //dummies are a hack to have proper overloading despite the java generics

}