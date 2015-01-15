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

import language._
import typelist._
import scala.concurrent._, duration._

class ThrottlingTest extends BaseFlowsTest {
  val alwaysThrottle = new Throttler {
    def acquireSlot() = None
    def isSlotAvailableHint = false
    def start() {}
    def disposeImpl() {}
  }
  describe("Drop action in Throttler") {
    it("should drop messages in SourceEndpoints") {
      implicit val ec = testApp.actorSystem.dispatcher
      val requestor = new FeedableEndpoint.Requestor[String, Unit]()
      @volatile var neverToggles = true
      new Flows {
        val appContext = testApp
        new Flow("t1")(Throttling(FeedableEndpoint(requestor))(alwaysThrottle, ThrottlingAction.Drop)) {
          logic { req =>
            /*nothing will ever reach here*/
            neverToggles = false
          }
        }.start
      }
      val res = for {
        _ <- requestor.request("a")
        _ <- requestor.request("b")
      } yield ()
      Await.ready(res, 100.millis)
      assert(neverToggles === true, "Toggled?")
    }
    it("should drop messages in ResposibleEndpoints") {
      implicit val ec = testApp.actorSystem.dispatcher
      val requestor = new FeedableEndpoint.Requestor[String, String]()
      @volatile var neverToggles = true
      new Flows {
        val appContext = testApp
        new Flow("t1")(Throttling(FeedableEndpoint(requestor))(alwaysThrottle, ThrottlingAction.Drop)) {
          logic { req =>
            /*nothing will ever reach here*/
            neverToggles = false
            req
          }
        }.start
      }
      val res = for {
        _ <- requestor.request("a")
        _ <- requestor.request("b")
      } yield ()
      Await.ready(res, 100.millis)
      assert(neverToggles === true, "Toggled?")
    }
  }
  describe("Backoff action in Throttler") {
    it("should backoff messages in SourceEndpoints") {
      implicit val ec = testApp.actorSystem.dispatcher
      val requestor = new FeedableEndpoint.Requestor[String, Unit]()
      @volatile var neverToggles = true
      val startTime = System.currentTimeMillis
      new Flows {
        val appContext = testApp
        new Flow("t1")(Throttling(FeedableEndpoint(requestor))(alwaysThrottle, ThrottlingAction.Backoff(
              100.millis, d => if (d < 200.millis) Some(d * 2) else None, ThrottlingAction.Drop))) {
          logic { req =>
            /*nothing will ever reach here*/
            neverToggles = false
          }
        }.start
      }
      val res = for {
        _ <- requestor.request("a")
        _ <- requestor.request("b")
      } yield ()
      Await.ready(res, 1.seconds)
      assert(neverToggles === true, "Toggled?")
      val totalTime = System.currentTimeMillis - startTime
      assert(totalTime > 300, "Throttle time?" + totalTime)
    }
    it("should backoff messages in ResponsibleEndpoints") {
      implicit val ec = testApp.actorSystem.dispatcher
      val requestor = new FeedableEndpoint.Requestor[String, String]()
      @volatile var neverToggles = true
      val startTime = System.currentTimeMillis
      new Flows {
        val appContext = testApp
        new Flow("t1")(Throttling(FeedableEndpoint(requestor))(alwaysThrottle, ThrottlingAction.Backoff(
              100.millis, d => if (d < 200.millis) Some(d * 2) else None, ThrottlingAction.Drop))) {
          logic { req =>
            /*nothing will ever reach here*/
            neverToggles = false
            req
          }
        }.start
      }
      val res = for {
        _ <- requestor.request("a")
        _ <- requestor.request("b")
      } yield ()
      Await.ready(res, 1.seconds)
      assert(neverToggles === true, "Toggled?")
      val totalTime = System.currentTimeMillis - startTime
      assert(totalTime > 300, "Throttle time?" + totalTime)
    }
  }
  describe("Reply action in Throttler") {
    it("should reply with the passed message") {
      implicit val ec = testApp.actorSystem.dispatcher
      val requestor = new FeedableEndpoint.Requestor[String, String]()
      @volatile var neverToggles = true
      new Flows {
        val appContext = testApp
        new Flow("t1")(Throttling(FeedableEndpoint(requestor))(alwaysThrottle, ThrottlingAction.Reply("my message is not the one you sent"))) {
          logic { req =>
            /*nothing will ever reach here*/
            neverToggles = false
            req
          }
        }.start
      }
      val res = requestor.request("a")
      val answer = Await.result(res, 100.millis)
      assert(neverToggles === true, "Toggled?")
      assert(answer === "my message is not the one you sent", "Reply?")
    }
  }
  describe("Enqueue action in Throttler") {
    it("enqueue up to its limit, and then call the rejectAction") {
      implicit val ec = testApp.actorSystem.dispatcher
      val throttler = new Throttler {
        def acquireSlot() = None
        def isSlotAvailableHint = false
        def start() {}
        def disposeImpl() {}
      }
      val requestor = new FeedableEndpoint.Requestor[String, String]()
      @volatile var neverToggles = true
      val e1 = ThrottlingAction.Enqueue(1, ThrottlingAction.Drop)
      val e2 = ThrottlingAction.Enqueue(1, e1)
      new Flows {
        val appContext = testApp
        new Flow("t1")(Throttling(FeedableEndpoint(requestor))(throttler, e2)) {
          logic { req =>
            /*nothing will ever reach here*/
            neverToggles = false
            req
          }
        }.start
      }
      val a = requestor.request("a")
      val b = requestor.request("b")
      val res = Future.sequence(Seq(a,b))
      Thread.sleep(100) //give some time for the flows to process it
      assert(neverToggles === true, "Toggled?")
      assert(e1.queue.size === 1, "Enqueue1?")
      assert(e2.queue.size === 1, "Enqueue2?")
    }
  }
}
