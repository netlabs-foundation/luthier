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
    val queue = new java.util.concurrent.ArrayBlockingQueue[(Message[_], Promise[_])](maxQueueSize)
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
    protected def handler: Message[Payload] => Future[Res]

    protected def handle(msg: Message[Payload], throttlingAction: ThrottlingAction[Underlying]): Future[Res] = {
      throttler.acquireSlot match {
        case Some(slot) =>
          val r = handler(msg)
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
            case Backoff(id, f, rejectAction) => backoff(id, f, msg, rejectAction)
            case e@Enqueue(_, rejectAction) =>
              val res = Promise[Res]
              if (!e.queue.offer(msg->res)) handle(msg, rejectAction)
              else res.future
          }
      }
    }
    protected def backoff(d: FiniteDuration, next: FiniteDuration => Option[FiniteDuration], msg: Message[Payload],
                          rejectAction: ThrottlingAction[Underlying]): Future[Res] = {
      log.info(s"Backing off for $d message $msg due to throttling.")
      val res = Promise[Res]
      flow.scheduleOnce(d) {
        throttler.acquireSlot match {
          case Some(slot) =>
            val r = handler(msg)
            r.onComplete { case _ => slot.markCompleted()}(flow.rawWorkerActorsExecutionContext)
            res completeWith r
          case None => next(d) match {
              case Some(d) => res completeWith backoff(d, next, msg, rejectAction)
              case None =>
                log.info("Applying rejectAction after full backoff failed.")
                res completeWith handle(msg, rejectAction)
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
                  case (msg, promise: Promise[Res @unchecked]) =>
                    promise completeWith handle(msg.as[Payload], action)
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

  class SourceThrottlingEndpoint[S <: Source](val flow: Flow, val underlying: S,
                                              override val throttler: Throttler,
                                              val throttlingAction: ThrottlingAction[S]) extends Source with ThrottlingEndpoint {
    override def handler = onEventHandler
    underlying.onEvent(handle(_: Message[Payload], throttlingAction))

    protected type Res = Unit
    type Throttler = logical.Throttler
    type Underlying = S
    type Payload = underlying.Payload
  }
  class ResponsibleThrottlingEndpoint[R <: Responsible](val flow: Flow, val underlying: R,
                                                        override val throttler: Throttler,
                                                        val throttlingAction: ThrottlingAction[R]) extends Responsible with ThrottlingEndpoint {

    def handler = onRequestHandler
    underlying.onRequest(handle(_: Message[Payload], throttlingAction))

    protected type Res = Message[OneOf[_, underlying.SupportedResponseTypes]]
    type Throttler = logical.Throttler
    type Underlying = R
    type Payload = underlying.Payload
    type SupportedResponseTypes = underlying.SupportedResponseTypes
  }

  case class SEF[S <: Source](source: EndpointFactory[S], throttler: Throttler,
                              action: ThrottlingAction[S]) extends EndpointFactory[SourceThrottlingEndpoint[S]] {
    def apply(f) = new SourceThrottlingEndpoint(f, source(f), throttler, action)
  }
  case class REF[R <: Responsible](responsible: EndpointFactory[R], throttler: Throttler,
                                   action: ThrottlingAction[R]) extends EndpointFactory[ResponsibleThrottlingEndpoint[R]] {
    def apply(f) = new ResponsibleThrottlingEndpoint(f, responsible(f), throttler, action)
  }
  def apply[S <: Source](source: EndpointFactory[S])(throttler: Throttler, action: ThrottlingAction[S]) = SEF(source, throttler, action)
  def apply[R <: Responsible](responsible: => EndpointFactory[R])(throttler: Throttler, action: ThrottlingAction[R]) = REF(responsible, throttler, action) //not here that the responsible is a byname just to get past the overloading issue
}