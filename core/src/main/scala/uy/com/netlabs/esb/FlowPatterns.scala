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
   */
  def retryAttempts[T](maxAttempts: Int,
                       description: String,
                       logRetriesWithLevel: akka.event.Logging.LogLevel = akka.event.Logging.InfoLevel)(op: => Future[T])(isFailure: Try[T] => Boolean)(implicit fc: FlowRun[_ <: Flow]): Future[T] = {
    import fc.flow.workerActorsExecutionContext
    val promise = Promise[T]()
    op onComplete { t =>
      if (isFailure(t) && maxAttempts > 1) {
        if (fc.flow.log.isEnabled(logRetriesWithLevel)) fc.flow.log.log(logRetriesWithLevel, s"Attempt for $description failed. Remaining $maxAttempts attempts. Retrying...")
        promise.completeWith(retryAttempts(maxAttempts - 1, description, logRetriesWithLevel)(op)(isFailure))
      } else {
        if (fc.flow.log.isEnabled(logRetriesWithLevel)) fc.flow.log.log(logRetriesWithLevel, s"Attempt for $description failed all attempts.")
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
    def backoff(acc: Long): Future[T] = {
      val promise = Promise[T]()
      op onComplete { t =>
        if (isFailure(t) && acc < maxBackoff) {
          val backoffTime = acc * backoffExponent
          if (fc.flow.log.isEnabled(logRetriesWithLevel)) fc.flow.log.log(logRetriesWithLevel, s"Attempt for $description failed. Backing of for $backoffTime ms before retry")
          fc.flow.scheduleOnce(backoffTime.millis) {
            promise.completeWith(backoff(backoffTime))
          }
        } else promise.complete(t)
      }
      promise.future
    }
    backoff(0)
  }
}