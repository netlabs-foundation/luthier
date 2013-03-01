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

import scala.util._
import scala.concurrent._, duration._

/**
 * FlowPatterns defines common flow constructions to use when implementing a flow.
 * This trait is mixed in Flow by default, so its usage is automatic.
 */
trait FlowPatterns {

  /**
   * Retries the operation `op` at most `maxAttempts`.
   *
   * @param maxAttempts Max attempts.
   * @param description Description of the operation, so that it is logged appropriately
   * @param logRetriesWithLevel Level to log failures. Default is `akka.event.Logging.InfoLevel`
   * @param op Action returning a Future. Typically, a call to an endpoint.
   * @param isFailure Determines whether the result of the future is a failure or not.
   * @param fc Implicit FlowRun that forces this method to be called from within a flow.
   */
  def retryAttempts[T](maxAttempts: Int,
                       description: String,
                       logRetriesWithLevel: akka.event.Logging.LogLevel = akka.event.Logging.InfoLevel)(
                         op: => Future[T])(
                           isFailure: Try[T] => Boolean)(implicit fc: FlowRun[_ <: Flow]): Future[T] = {
    import fc.flow.workerActorsExecutionContext
    val promise = Promise[T]()
    op onComplete { t =>
      val failed = isFailure(t)
      if (failed && maxAttempts > 1) {
        if (fc.flow.log.isEnabled(logRetriesWithLevel)) fc.flow.log.log(logRetriesWithLevel, s"""Attempt for "$description" failed. Remaining $maxAttempts attempts. Retrying...""")
        promise.completeWith(retryAttempts(maxAttempts - 1, description, logRetriesWithLevel)(op)(isFailure))
      } else {
        if (failed && fc.flow.log.isEnabled(logRetriesWithLevel)) fc.flow.log.log(logRetriesWithLevel, s"""Attempt for "$description" failed all attempts.""")
        promise.complete(t)
      }
    }
    promise.future
  }

  /**
   * Convenience call to retryAttempts passing Int.MaxValue and a positive isSuccess instead of isFailure.
   */
  def untilSuccessful[T](description: String,
                         logRetriesWithLevel: akka.event.Logging.LogLevel = akka.event.Logging.InfoLevel)(op: => Future[T])(isSuccess: Try[T] => Boolean)(implicit fc: FlowRun[_ <: Flow]): Future[T] = {
    retryAttempts(Int.MaxValue, description)(op)(isSuccess andThen (!_))
  }

  def retryBackoff[T](initalBackoff: Long, backoffExponent: Long, maxBackoff: Long, description: String,
                      logRetriesWithLevel: akka.event.Logging.LogLevel = akka.event.Logging.InfoLevel)(op: => Future[T])(isFailure: Try[T] => Boolean)(implicit fc: FlowRun[_ <: Flow]): Future[T] = {
    import fc.flow.workerActorsExecutionContext
    def backoff(wait: Long, acc: Long): Future[T] = {
      val promise = Promise[T]()
      op onComplete { t =>
        val failed = isFailure(t)
        if (failed && acc < maxBackoff) {
          if (fc.flow.log.isEnabled(logRetriesWithLevel)) fc.flow.log.log(logRetriesWithLevel, s"""Attempt for "$description" failed. Backing of for $wait ms before retry""")
          fc.flow.scheduleOnce(wait.millis) {
            promise.completeWith(backoff(wait * backoffExponent, acc + wait))
          }
        } else {
          if (failed && fc.flow.log.isEnabled(logRetriesWithLevel)) fc.flow.log.log(logRetriesWithLevel, s"""Accumulated backoff($acc) ≥ maxBackoff($maxBackoff). "$description" failed.""")
          promise.complete(t)
        }
      }
      promise.future
    }
    backoff(initalBackoff, 0)
  }
}