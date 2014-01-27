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
import akka.actor.Scheduler
import scala.concurrent._, duration._

object ConcurrentLimit {
  def apply(concurrentLimit: Int) = new ConcurrentLimit(concurrentLimit)
}
class ConcurrentLimit(@volatile var concurrentLimit: Int) extends Throttler {
  private val concurrentRunning = new java.util.concurrent.atomic.AtomicInteger(0)
  def aquireSlot() = if (concurrentRunning.get < concurrentLimit) {
    concurrentRunning.incrementAndGet
    Some(new Throttler.Slot {
        val startTime: Long = System.currentTimeMillis
        def markCompleted() { concurrentRunning.decrementAndGet }
      })
  } else None
  def start() {}
  def disposeImpl() {}
}

// this throttler needs review.
object FixedRateLimit {
  def apply(maxRate: Double, batchPeriod: FiniteDuration = 1.second)(implicit appContext: AppContext) =
    new FixedRateLimit(maxRate, batchPeriod, appContext)
}
class FixedRateLimit(val maxRate: Double,
                     val batchPeriod: FiniteDuration,
                     val appContext: AppContext) extends Throttler {
  // TODO: implement a decent congestion control algorithm like "vegas"...

  val SPEED_ESTIMATION_EWMA_ALPHA = 0.1
  @volatile private var remainder = 0.0
  @volatile private var lastUpdate = System.currentTimeMillis
  @volatile private var periodEstimationInSeconds: Double = 1 / maxRate
  private val permits = new java.util.concurrent.atomic.AtomicInteger((batchPeriod.toMillis / 1000.0 * maxRate).toInt)

  def aquireSlot() = {
    if (permits.get() > 0) Some(new Throttler.Slot {
        val startTime: Long = System.currentTimeMillis
        def markCompleted() {
          val spentMillis = System.currentTimeMillis - startTime
          lastUpdate = System.currentTimeMillis
          periodEstimationInSeconds = nextEwmaPeriod(spentMillis)
        }
      })
    else None
  }
  private def nextEwmaPeriod(deltaMillis: Long): Double = {
    SPEED_ESTIMATION_EWMA_ALPHA * (deltaMillis / 1000.0) + (1 - SPEED_ESTIMATION_EWMA_ALPHA) * periodEstimationInSeconds
  }
  def getEstimatedSpeed() = 1.0 / periodEstimationInSeconds

  private val timer = appContext.actorSystem.scheduler.schedule(batchPeriod, batchPeriod) {
    val msgsToSendDbl = batchPeriod.toMillis / 1000.0 * maxRate + remainder
    val msgsToSend = msgsToSendDbl.asInstanceOf[Int]
    remainder = msgsToSendDbl - msgsToSend
    permits.addAndGet(msgsToSend)
  }(appContext.actorSystem.dispatcher)
  def start() {}
  override def disposeImpl() {
    timer.cancel()
  }
}
