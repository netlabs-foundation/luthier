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

//  private implicit def flowExecutionContext(implicit fc: FlowRun.Any) = fc.flow.workerActorsExecutionContext(fc.rootMessage.asInstanceOf[RootMessage[fc.flow.type]])
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
    isFailure: Try[T] => Boolean)(implicit fc: FlowRun.Any): Future[T] = {
    val promise = Promise[T]()
    op.onComplete { t =>
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
                         logRetriesWithLevel: akka.event.Logging.LogLevel = akka.event.Logging.InfoLevel)(op: => Future[T])(isSuccess: Try[T] => Boolean)(implicit fc: FlowRun.Any): Future[T] = {
    retryAttempts(Int.MaxValue, description)(op)(isSuccess andThen (!_))
  }

  def retryBackoff[T](initalBackoff: Long, backoffExponent: Long, maxBackoff: Long, description: String,
                      logRetriesWithLevel: akka.event.Logging.LogLevel = akka.event.Logging.InfoLevel)(op: => Future[T])(isFailure: Try[T] => Boolean)(implicit fc: FlowRun.Any): Future[T] = {
    def backoff(wait: Long, acc: Long): Future[T] = {
      val promise = Promise[T]()
      op.onComplete { t =>
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

  trait Paging[A] {
    def next(): Future[Option[A]]
    def map[S](f: A => S)(implicit ec: ExecutionContext): Paging[S] = {
      val outer = this
      new Paging[S] {
        def next = outer.next.map(_.map(f))(ec)
      }
    }
    def flatMap[S](f: A => Future[S])(implicit ec: ExecutionContext): Paging[S] = {
      val outer = this
      new Paging[S] {
        def next = outer.next.map(_.map(v => f(v)))(ec).flatMap {
          case None => Future.successful(None)
          case Some(a) => a map Some.apply
        }
      }
    }
    def filter(f: A => Boolean)(implicit ec: ExecutionContext): Paging[A] = {
      val outer = this
      new Paging[A] {
        def next() = outer.next.flatMap {
          case None => Future.successful(None)
          case s@Some(a) if f(a) => Future.successful(s)
          case other => next()
        }
      }
    }
    def fold[B](init: B)(op: (A, B) => B)(implicit ec: ExecutionContext): Future[B] = {
      val res = Promise[B]()
      def iter(b: B): Unit = next() onComplete {
        case Success(Some(a)) => iter(op(a, b))
        case Success(None) => res.success(b)
        case Failure(ex) => res.failure(ex)
      }
      iter(init)
      res.future
    }
  }

  /**
   * Creates a paging using the intial state and a function that given an state, fetchs a page of data, optionally returning a new state of data.
   * @param intialState Initial state, possibly the starting index, or whatever makes sense for your paging.
   * @param pager Function that takes a state and optionally produces a page (if data is foud). When a page is produced, you can optionally return a new State
   *        representing the next page, or None of there is no more pages after this one.
   */
  def paging[S, R](initialState: S)(pager: S => Future[Option[(R, Option[S])]])(implicit fc: FlowRun.Any): Paging[R] = new Paging[R] {
    private[this] var lastCall: Future[Option[(R, Option[S])]] = null
    def next: Future[Option[R]] = synchronized {
      lastCall = if (lastCall == null) {
        pager(initialState)
      } else {
        lastCall.flatMap {
          case Some((_, Some(s))) => pager(s)
          case other => lastCall
        }
      }
      lastCall map (_.map(_._1))
    }
  }

  trait IndexedPaging[A] {
    def get(i: Int): Future[Option[A]]
    def map[S](f: A => S)(implicit ec: ExecutionContext): IndexedPaging[S] = {
      val outer = this
      new IndexedPaging[S] {
        def get(i: Int) = outer.get(i).map(_.map(f))(ec)
      }
    }
    def flatMap[S](f: A => Future[S])(implicit ec: ExecutionContext): IndexedPaging[S] = {
      val outer = this
      new IndexedPaging[S] {
        def get(i: Int) = outer.get(i).map(_.map(f))(ec).flatMap {
          case None => Future.successful(None)
          case Some(a) => a map Some.apply
        }
      }
    }
    def filter(f: A => Boolean)(implicit ec: ExecutionContext): Paging[A] = {
      val outer = this
      new Paging[A] {
        @volatile var lastIndex = -1
        def next() = outer.get({lastIndex += 1; lastIndex}).flatMap {
          case None => Future.successful(None)
          case s@Some(a) if f(a) => Future.successful(s)
          case other => next()
        }
      }
    }
    def fold[B](init: B)(op: (A, B) => B)(implicit ec: ExecutionContext): Future[B] = {
      val res = Promise[B]()
      def iter(i: Int, b: B): Unit = get(i) onComplete {
        case Success(Some(a)) => iter(i + 1, op(a, b))
        case Success(None) => res.success(b)
        case Failure(ex) => res.failure(ex)
      }
      iter(0, init)
      res.future
    }
  }

  /**
   * Produces a Paging representation that supports indexed fetching.
   * The initial state is whatever makes sense for the paging, often it is some kind of index.
   * @param initialState Initial state for the paging, possible an index of some sort.
   * @param cache Weather or not it should cache pages already accessed by index or fetch them again when requested.
   * @param pager Function that takes the initial state and returns a function that can be queried for an index to possible obtain a Page.
   */
  def indexedPaging[S, R](initialState: S, cache: Boolean = false)(pager: S => (Int => Future[Option[R]]))(implicit fc: FlowRun.Any): IndexedPaging[R] = new IndexedPaging[R] {
    import fc.flow._
    private[this] val retrievedPages = if (cache) new java.util.HashMap[Int, Future[Option[R]]]() else null
    private[this] var indexer: (Int => Future[Option[R]]) = null
    def get(i: Int): Future[Option[R]] = {
      synchronized {
        if (indexer == null) {
          indexer = pager(initialState)
        }
      }
      //cache previously retrieved pages
      if (cache) {
        retrievedPages.synchronized {
          val prev = retrievedPages.get(i)
          if (prev == null) {
            val res = indexer(i)
            retrievedPages.put(i, res)
            res
          } else prev
        }
      } else indexer(i)
    }
  }
}