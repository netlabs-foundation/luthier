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

import java.nio.file.Paths
import scala.concurrent._, duration._
import scala.util._
import language._
import logical.Polling._
import org.scalatest._
import uy.com.netlabs.luthier.AppContext
import uy.com.netlabs.luthier.Flows
import scala.sys.process.stringSeqToProcess
import scala.sys.process.stringToProcess
import uy.com.netlabs.luthier.MessageFactory.factoryFromMessage
import uy.com.netlabs.luthier.endpoint.Process

class FunctionTest extends FunSpec with BeforeAndAfter {
  var myApp: AppContext = _
  before {
    myApp = AppContext.build("Test Function App")
  }
  after {
    myApp.actorSystem.shutdown()
  }

  describe("A Function Endpoint") {
    it("Should be able to execute functions") {
      new Flows {
        val appContext = myApp

        val result = Promise[Option[String]]()
        val flow = new Flow("Poll Function")(Metronome(1.seconds)) { //initial delay is 0
          logic { m =>
            val msg = "this is a function that returns a text"
            result completeWith (Function(msg).pull map (m => m.payload === msg))
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 0.25.seconds))
        flow.dispose
        assert(res.get)
      }
    }

    it("Should be able to ask functions") {
      new Flows {
        val appContext = myApp

        val res = inFlow { (flow, m) =>
          import flow._
          implicit val fr = m.flowRun
          val msg = "this is a function that returns a text"
          Await.result(Function[String]().ask(m.map(_ => () => msg)) map (m => m.payload === msg), 0.3.seconds)
        }
        Try(assert(Await.result(res, 0.3.seconds)))
      }
    }
  }

  describe("A Process Endpoint") {
    it("Should be able to run processes") {

      new Flows {
        val appContext = myApp

        val res = inFlow { (flow, m) =>
          import flow._
          implicit val fr = m.flowRun
          import scala.sys.process.{ Process => _, _ }
          Process.string("ifconfig" #| Seq("grep", "inet addr")).pull()(m) onSuccess { case r => println(r) }
          Await.result(Process.string("echo hi there!").pull()(m) map (m => m.payload === "hi there!"), 0.3.seconds)
        }
        Try(assert(Await.result(res, 0.3.seconds)))
      }
    }
  }
}