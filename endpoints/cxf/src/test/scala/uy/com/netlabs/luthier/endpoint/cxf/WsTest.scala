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
package cxf

import language.dynamics
import scala.concurrent._
import scala.concurrent.duration._

import javax.jws.WebService

import codefirst._
import dynamic._

class WsTest extends BaseFlowsTest {
  describe("The WebService Endpoint support") {
    it("Should be able to publish a web service, and also consult it") {
      new Flows {
        val appContext = testApp

        @WebService
        trait WsDef {
          def echo(msg: String): String
        }
        val url = "http://localhost:8080"
        val server = new Flow("service")(Ws(Sei[WsDef](url, "/testws"))(_.echo _)) {
          logic {m => m} //really simply echo!
        }
        server.start

        val timeout = 10.seconds
        val res = inFlow { (flow, m) =>
          import flow._
          val greet = "Hi there!"
          WsInvoker[String](WsClient(url + "/testws?wsdl"), "echo", true).ask(m.map(_ => Seq(greet)))
          WsInvoker[String](WsClient(url + "/testws?wsdl"), "echo", true).ask(m.map(_ => Seq(greet)))
          WsInvoker[String](WsClient(url + "/testws?wsdl"), "echo", true).ask(m.map(_ => Seq(greet))) //several request, because I want to also test proper disposal
          val resp = Await.result(WsInvoker[String](WsClient(url + "/testws?wsdl"), "echo", true).ask(m.map(_ => Seq(greet))), timeout)
          resp.payload === greet
        }
        val ass = Await.result(res, timeout)
        server.dispose()
        assert(ass)
      }
    }
    it("Should be able to handle dynamic types") {
      new Flows {
        val appContext = testApp

        @WebService
        trait WsDef {
          def echo(msg: EchoMessage): String
        }
        val url = "http://localhost:8080"
        val server = new Flow("service")(Ws(Sei[WsDef](url, "/anotherws"))(_.echo _)) {
          logic {m => m.map(_.content)} //really simply echo!
        }
        server.start

        val timeout = 10.seconds
        val client = WsClient(url + "/anotherws?wsdl")
        val res = inFlow { (flow, m) =>
          import flow._
          val greet = "Hi there!"

          val em = client.instance("uy.com.netlabs.luthier.endpoint.cxf.EchoMessage")
          em.setContent(greet)
          val resp = Await.result(WsInvoker[String](client, "echo", true).ask(m.map(_ => Seq(em.instance))), timeout)
          resp.payload === greet
        }
        val ass = Await.result(res, timeout)
        server.dispose()
        assert(ass)
      }
    }
  }

}
class EchoMessage(var content: String) {
  def this() = this("")
  /*getter and setter to please jaxws so that it identifies that this fields must be serialized*/
  def setContent(s: String) {content = s}
  def getContent = content
  override def toString = s"EchoMessage($content)"
}