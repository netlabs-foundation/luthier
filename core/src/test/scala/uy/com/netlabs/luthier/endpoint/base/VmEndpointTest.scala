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
package base

import typelist._
import scala.concurrent._, duration._, scala.util._

class VmEndpointTest extends BaseFlowsTest {
  describe("VM endpoints") {
    they("shuold successfully ignore messages of invalid types") {
      new Flows {
        val appContext = testApp
        val Vm = VM.forAppContext(appContext)
        val p = Promise[Unit]()
        new Flow("source")(Vm.source[String]("the-test-actor")) {
          logic { msg =>
            if (msg.payload == 42) { println("Error, 42 this should never happen!"); sys.exit(5) }
            println("Message " + msg.payload + " arrived")
            p.trySuccess(())
          }
        }.start()
        inFlow { (f, msg) =>
          import f._
          val dest = Vm.sink[Any]("user/VM/the-test-actor")
          dest.push(msg.map(_ => 42)) //wrong message
          dest.push(msg.map(_ => "42 - hellooooooo"))
        }
        Await.ready(p.future, 1.second) //the first one must be ignored, the second one succeed
      }
    }
    they("shuold support responsible") {
      new Flows {
        val appContext = testApp
        val Vm = VM.forAppContext(appContext)
        val p = Promise[String]()
        new Flow("responsible")(Vm.responsible[String, String :: TypeNil]("the-test-actor")) {
          logic { msg =>
            if (true)
              msg.map(m => "Hello " + m)
            else Future.successful(msg)
          }
        }.start()
        inFlow { (f, msg) =>
          import f._
          implicit val fr = msg.flowRun
          val dest = Vm.ref[String, String]("user/VM/the-test-actor")
          val q = dest.ask(msg.map(_ => "world"))
          q.onSuccess {case resp => p.trySuccess(resp.payload)}
          q
        }
        Await.result(p.future, 1.second) == "Hello world" //the first one must be ignored, the second one succeed
      }
    }
  }
}
