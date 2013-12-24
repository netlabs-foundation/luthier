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
package endpoint.logical

import akka.actor.Cancellable
import endpoint.base.IoProfile
import language._
import scala.collection.mutable.SynchronizedQueue
import scala.concurrent._, duration._
import uy.com.netlabs.luthier.endpoint.base.IoExecutionContext

trait Throttler {
  def queueEvent()
  def completeEvent(spentMillis: Long)
  def dispose() {}
}


case class NoLimit(val cb: ThrottlingCallback) extends Throttler {
  def queueEvent{ cb.dispatch() }
  def completeEvent(spentMillis: Long) {}
}


case class ConcurrentRequestsLimit(val maxConcurrentAsks: Int)(val cb: ThrottlingCallback) extends Throttler {
  @volatile private var activeCount: Int = 0
  def queueEvent() {
    this.synchronized {
      if (activeCount < maxConcurrentAsks) {
        cb.dispatch()
        activeCount += 1
      }
    }
  }

  def completeEvent(spentMillis: Long) {
    val dispatched = cb.dispatch()
    if (!dispatched) {
      this.synchronized { activeCount -= 1 }
    }
  }
}


case class FixedRateLimit(val maxRate: Double,
                          val batchPeriod: FiniteDuration = 1.second)(val cb: ThrottlingCallback) extends Throttler {
  // TODO: implement a decent congestion control algorithm like "vegas"...

  //val deadPeriod = deadPeriodOption.getOrElse((5*1000/maxRate max 60000).millis) //don't needed...
  val SPEED_ESTIMATION_EWMA_ALPHA = 0.1
  @volatile private var remainder = 0.0
  @volatile private var lastUpdate = System.currentTimeMillis
  @volatile private var periodEstimationInSeconds: Double = 1 / maxRate

  def queueEvent() {
  }

  def completeEvent(spentMillis: Long) {
    lastUpdate = System.currentTimeMillis
    periodEstimationInSeconds = nextEwmaPeriod(spentMillis)
  }

  private def nextEwmaPeriod(deltaMillis: Long): Double = {
    SPEED_ESTIMATION_EWMA_ALPHA * (deltaMillis / 1000.0) + (1 - SPEED_ESTIMATION_EWMA_ALPHA) * periodEstimationInSeconds
  }

  def getEstimatedSpeed() = 1.0 / periodEstimationInSeconds

  private val timer = cb.schedule(batchPeriod) {
    val msgsToSendDbl = batchPeriod.toMillis / 1000.0 * maxRate + remainder
    val msgsToSend = msgsToSendDbl.asInstanceOf[Int]
    remainder = msgsToSendDbl - msgsToSend
    1 to msgsToSend takeWhile {_ => cb.dispatch}
  }

  override def dispose() {
    timer.cancel()
  }
}


trait ThrottlingCallback {
  /**
   * This method returns true if a queued message could be asked to the inner endpoint, or false if there weren't any
   * queued messages. If the call to "ask" fails, then that exception is not returned. Instead the promise is completed
   * with failure.
   */
  def dispatch(): Boolean

  /**
   * queuedCount returns an approximation to the queued messages count.
   */
  def queuedCount: Int

  // Wrapper to scheduleOnce
  def scheduleOnce(dur: FiniteDuration)(callback: => Unit): Cancellable

  /**
   * Wrapper to schedule. Don't forget to cancel them.
   */
  def schedule(dur: FiniteDuration)(callback: => Unit): Cancellable
}


case class Throttling(val throttlerFactory: ThrottlingCallback => Throttler) {
  class ThrottlingEndpoint[A <: Askable](val flow: Flow, val ef: EndpointFactory[A], val ioThreads: Int) extends Askable with IoExecutionContext {
    val endpoint = ef(flow)
    type SupportedTypes = endpoint.SupportedTypes
    type Response = endpoint.Response

    val ioProfile = IoProfile.threadPool(ioThreads, flow.name + "-throttling-ep")

    val throttler = throttlerFactory(new ThrottlingCallback {
        def dispatch() = {
          val entry = try { queue.dequeue } catch { case _: NoSuchElementException => null }
          try {
            if (null != entry) {
              entry.sentTime = System.currentTimeMillis
              val f = endpoint.askImpl(entry.msg, entry.timeOut)(null) //provide evidence
              entry.promise.completeWith(f)
            }
          } catch {
            case ex: Throwable => entry.promise.tryFailure(ex)
          }
          null != entry
        }
        def queuedCount = queue.size
        def scheduleOnce(dur: FiniteDuration)(cb: => Unit) = appContext.actorSystem.scheduler.scheduleOnce(dur)(cb)
        def schedule(dur: FiniteDuration)(cb: => Unit) = appContext.actorSystem.scheduler.schedule(dur, dur)(cb)
      })

    case class QueueEntry[Payload: SupportedType](
      msg: Message[Payload],
      timeOut: FiniteDuration,
      promise: Promise[Message[Response]],
      var sentTime: Long = 0
    )
    val queue = new SynchronizedQueue[QueueEntry[_]]

    def askImpl[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration): Future[Message[Response]] = {
      val p = promise[Message[Response]]
      val entry = QueueEntry(msg, timeOut, p)
      queue += entry
      p.future.onComplete {
        case _ =>
          val now = System.currentTimeMillis
          throttler.completeEvent(now - entry.sentTime)
      }
      throttler.queueEvent
      p.future
    }

    def start{
      endpoint.start()
    }

    def dispose{
      throttler.dispose()
      endpoint.dispose()
      ioProfile.dispose()
    }
  }

  private case class EF[A <: Askable](ef: EndpointFactory[A], ioThreads: Int) extends EndpointFactory[ThrottlingEndpoint[A]] {
    def apply(f: Flow) = new ThrottlingEndpoint(f, ef, ioThreads)
  }

  def apply[A <: Askable](ef: EndpointFactory[A], ioThreads: Int = 4): EndpointFactory[ThrottlingEndpoint[A]] = {
    EF(ef, ioThreads)
  }
}
