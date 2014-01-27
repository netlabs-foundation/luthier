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
  def aquireSlot(): Option[Throttler.Slot]
  def start()
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
sealed trait ThrottlingAction[-T <: InboundEndpoint]
object ThrottlingAction {
  /**
   * Signals that the operation should be discarded.
   */
  case object Drop extends ThrottlingAction[InboundEndpoint]
  /**
   * Signals that the operation should result in the given response.
   */
  case class Reply[T, R <: Responsible](resp: T)(implicit ev: Contained[R#SupportedResponseTypes, T]) extends ThrottlingAction[R]
  /**
   * Signals that the operation should be backed off using the given backoff function.
   */
  case class Backoff(initialBackoff: FiniteDuration, f: FiniteDuration => Option[FiniteDuration]) extends ThrottlingAction[InboundEndpoint]
}

object Throttling {
  class SourceThrottlingEndpoint[S <: Source](val flow: Flow, val underlying: S,
                                              override val throttler: Throttler,
                                              val action: ThrottlingAction[S]) extends Source {

    underlying.onEvent { evt =>
      throttler.aquireSlot match {
        case Some(slot) =>
          val r = onEventHandler(evt)
          r.onComplete { case _ => slot.markCompleted()}(flow.rawWorkerActorsExecutionContext)
          r
        case None =>
          import ThrottlingAction._
          (action: @unchecked) match {
            case Drop => log.info(s"Dropping message $evt due to throttling."); Future.successful(())
            case Backoff(id, f) => backoff(id, f, evt)
          }
      }
    }

    type Throttler = logical.Throttler
    type Payload = underlying.Payload

    private def backoff(d: FiniteDuration, next: FiniteDuration => Option[FiniteDuration], msg: Message[Payload]): Future[Unit] = {
      log.info(s"Backing off for $d message $msg due to throttling.")
      val res = Promise[Unit]
      flow.scheduleOnce(d) {
        throttler.aquireSlot match {
          case Some(slot) =>
            val r = onEventHandler(msg)
            r.onComplete { case _ => slot.markCompleted()}(flow.rawWorkerActorsExecutionContext)
            res completeWith r
          case None => next(d) match {
              case Some(d) => res completeWith backoff(d, next, msg)
              case None => res.failure(new TimeoutException())
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
      throttler.start()
      underlying.start()
    }
  }
  class ResponsibleThrottlingEndpoint[R <: Responsible](val flow: Flow, val underlying: R,
                                                        override val throttler: Throttler,
                                                        val action: ThrottlingAction[R]) extends Responsible {

    underlying.onRequest { evt =>
      throttler.aquireSlot match {
        case Some(slot) =>
          val r = onRequestHandler(evt)
          r.onComplete { case _ => slot.markCompleted()}(flow.rawWorkerActorsExecutionContext)
          r
        case None =>
          import ThrottlingAction._
          (action: @unchecked) match {
            case Drop =>
              log.info(s"Dropping message $evt due to throttling.")
              Future.failed(new Exception("Message dropped"))
            case Reply(resp) =>
              log.info(s"Replying with $resp due to throttling.")
              Future.successful(evt map (_ => new OneOf[Any, underlying.SupportedResponseTypes](resp)(null)))
            case Backoff(id, f) => backoff(id, f, evt)
          }
      }
    }

    type Throttler = logical.Throttler
    type Payload = underlying.Payload
    type SupportedResponseTypes = underlying.SupportedResponseTypes

    private def backoff(d: FiniteDuration, next: FiniteDuration => Option[FiniteDuration], msg: Message[Payload]): Future[Message[OneOf[_, SupportedResponseTypes]]] = {
      log.info(s"Backing off for $d message $msg due to throttling.")
      val res = Promise[Message[OneOf[_, SupportedResponseTypes]]]
      flow.scheduleOnce(d) {
        throttler.aquireSlot match {
          case Some(slot) =>
            val r = onRequestHandler(msg)
            r.onComplete { case _ => slot.markCompleted()}(flow.rawWorkerActorsExecutionContext)
            res completeWith r
          case None => next(d) match {
              case Some(d) => res completeWith backoff(d, next, msg)
              case None => res.failure(new TimeoutException())
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
      throttler.start()
      underlying.start()
    }
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