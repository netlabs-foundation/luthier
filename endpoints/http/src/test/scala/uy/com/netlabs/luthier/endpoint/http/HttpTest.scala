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
package http

import java.nio.file.{ Paths, Files }

import scala.concurrent._, duration._
import scala.util._
import language._

import logical.Poll

import org.scalatest._
import dispatch.{ Future => _, _ }

class HttpTest extends BaseFlowsTest {
  describe("An Http client Endpoint") {
    it("Should be able to perform HTTP request and transform its result") {
      new Flows {
        val appContext = testApp
        val result = Promise[Unit]()
        val flow = new Flow("Poll Http")(Poll.pulling(Http.pulling(url("http://www.google.com/").setFollowRedirects(true) OK as.jsoup.Document), every = 1.second)) { //initial delay is 0
          logic { m =>
            result complete Try( if (m.payload.select("a[href]").size == 0) fail("payload with 0 links?"))
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 5.seconds))
        flow.dispose
        res.get
      }
    }
    it("Should be able to ask HTTP request and transform its result") {
      new Flows {
        val appContext = testApp

        val res = inFlow { (flow, m) =>
          import flow._
          implicit val fr = m.flowRun
          val req = url("http://www.google.com/").setFollowRedirects(true) OK as.jsoup.Document
          Http[org.jsoup.nodes.Document]().ask(m.map(_ => req)) map { m =>
            println("Cookies: ")
            println(m.header.inbound.get("Cookies"))
            if (m.payload.select("a[href]").size == 0) fail("payload with 0 links?")
          }
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        Await.result(res, 6.seconds)
      }
    }
    if (scala.util.Properties.userName == "rcano") {
      it("Should be able to authenticate using SSL") {
        new Flows {
          val appContext = testApp

          val sslContext = SSLContext(
            SslKeys("/home/rcano/rcano.netlabs.com.uy.der"), SslCerts("/home/rcano/rcano.netlabs.com.uy.crt"),
            SslCerts("/home/rcano/nlca.netlabs.com.uy.crt"), sslProtocol = "TLSv1.2")

          val res = inFlow {(flow, m) =>
            import flow._
            implicit val flowRun = m.flowRun

            def redmine[T] = Http[T](httpClientConfig = ClientConfig(sslContext = sslContext))
            val postParams = Map("username"->"someUser", "password"->new String("somePass"))
            val request =
              redmine[String].ask(m map (_ => (url("https://redmine.netlabs.com.uy/login").setFollowRedirects(true) << postParams > as.String)))

            //example that after login, would download the time_entries csv (it works :) )
//          val request =
//            redmine[String].ask(m map (_ => (url("https://redmine.netlabs.com.uy/login").setFollowRedirects(true) << postParams, new OkFunctionHandler(as.String)))).flatMap {res =>
//              res.header.swap //swap headers to send the cookies we received
//              redmine[String].ask(res map (_ => (url("https://redmine.netlabs.com.uy/time_entries.csv"), new OkFunctionHandler(as.String))))
//            }

            request
          }.flatMap(identity)(appContext.actorSystem.dispatcher)

          println(Await.result(res, 6.seconds))
        }
      }
    }
  }

  describe("An Http server Endpoint") {
    it("Should accept request and respond to them") {
      new Flows {
        val appContext = testApp
        val serverFlow = new Flow("http-server")(Http.server(3987)) {
          import unfiltered.request._
          logic { m =>
            m.payload match {
              case GET(Path(p)) => m map (_ => p)
            }
          }
        }
        serverFlow.start
        val reqResponse = inFlow { (flow, m) =>
          import flow._
          implicit val flowRun = m.flowRun
          val res = Await.result(Http.pulling(url("http://localhost:3987/some/path") OK as.String).pull(), 3.seconds).payload
          println("Res gotten " + res)
          res
        }
        val result = Await.result(reqResponse, 3.seconds)
        serverFlow.dispose
        assert(result === "/some/path")
      }
    }
  }
}
