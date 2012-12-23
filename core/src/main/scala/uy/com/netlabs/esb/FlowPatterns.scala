package uy.com.netlabs.esb

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