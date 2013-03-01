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

import scala.concurrent._, duration._

class FlowPatternsTest extends endpoint.BaseFlowsTest {
  val totalCount = 15
  describe(s"Retrying with $totalCount attempts") {
    it(s"should perform $totalCount retries before giving up") {
      new Flows {
        val appContext = testApp
        @volatile var count = 0
        val run = inFlow { (flow, msg) =>
          import flow._
          retryAttempts(totalCount, "fail op")(doWork { println("Counting!"); count += 1 })(_ => count != totalCount)(msg.flowRun)
        }
        Await.result(Await.result(run, 1.hour), 1.seconds)
        assert(count === totalCount)
      }
    }
  }
  val maxBackoff = 600.millis
  describe(s"Retrying with $maxBackoff attempts") {
    it(s"should continue backing off retries, until failure.") {
      new Flows {
        val appContext = testApp
        @volatile var count = 0
        val run = inFlow { (flow, msg) =>
          import flow._
          Await.result( //must wait for the result here, or the flow gets disposed preemptively.
          retryBackoff(100, 2, maxBackoff.toMillis, "fail op")(blocking { println("Counting!"); count += 1 })(_ => true)(msg.flowRun)
          ,1.2.seconds)
        }
        Await.result(run, 1.2.seconds)
        assert(count === 4)
      }
    }
  }
}