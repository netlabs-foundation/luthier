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

import uy.com.netlabs.luthier.endpoint.BaseFlowsTest
import scala.concurrent._, duration._

class FlowRunEventsTest extends BaseFlowsTest {
  describe("FlowRuns") {
    they("Should report flow response completed asap") {
      new Flows {
        val appContext = testApp
        val res = Promise[String]()
        val run = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          blocking { Thread.sleep(10000) }.map {_ => 
            res.tryFailure(new Exception("You shouldn't have waited for me"))
          }
          fr.afterFlowResponse(res.trySuccess("Flow is done!"))
        }
        assert(Await.result(res.future, 100.millis) === "Flow is done!")
      }
    }
    they("Should report whole flow completion after all the code completes, and not before") {
      new Flows {
        val appContext = testApp
        val res = Promise[String]()
        val wholeFlowComplete = Promise[String]()
        val f = new Flow("test")(new endpoint.base.DummySource) {
          logic { msg => 
            blocking { Thread.sleep(100); log.info("Done with long wait") }.map {_ =>
              log.info("2 Done with long wait")
              wholeFlowComplete.success("Whole Flow is done!")
            }
            blocking { Thread.sleep(10); log.info("Some more blocking here") }.map {_ => 
              log.info("2 Some more blocking here")
            }
            blocking { Thread.sleep(10); log.info("And here") }.map {_ => 
              log.info("2 And here")
            }
            flowRun.afterWholeFlowRun {
              if (wholeFlowComplete.isCompleted) res.success("Whole Flow is done!")
              else res.failure(new Exception("It seems it ended prematurely"))
            }
          }
        }
        f.runFlow(null)
        assert(Await.result(res.future, 1000.millis) === "Whole Flow is done!")
      }
    }
  }
}
