package uy.com.netlabs.esb
package endpoint
package http

import java.nio.file.{ Paths, Files }

import scala.concurrent._, duration._
import scala.util._
import language._

import logical.Polling._

import org.scalatest._
import dispatch.{ Promise => _, _ }

class HttpTest extends BaseFlowsTest {
  describe("An Http client Endpoint") {
    it("Should be able to perform HTTP request and transform its result") {
      new Flows {
        val appContext = testApp
        val result = Promise[Option[String]]()
        val flow = new Flow("Poll Http")(Poll(Http(url("http://www.google.com/").setFollowRedirects(true) -> new OkFunctionHandler(as.jsoup.Document)), 1.seconds)) { //initial delay is 0
          logic { m =>
            result success {
              if (m.payload.select("a[href]").size != 0) None
              else Some("payload with 0 links?")
            }
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 5.seconds))
        flow.dispose
        assert(res.get)
      }
    }
    it("Should be able to ask HTTP request and transform its result") {
      new Flows {
        val appContext = testApp

        val res = inFlow { (flow, m) =>
          import flow._
          val req = url("http://www.google.com/").setFollowRedirects(true) -> new OkFunctionHandler(as.jsoup.Document)
          Await.result(Http[org.jsoup.nodes.Document]().ask(m.map(_ => req)) map { m =>
            println("Cookies: ")
            println(m.header.inbound.get("Cookies"))

            if (m.payload.select("a[href]").size != 0) None
            else Some("payload with 0 links?")
          }, 5.seconds)
        }
        assert(Await.result(res, 6.seconds))
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
          val res = Await.result(Http(url("http://localhost:3987/some/path") -> new OkFunctionHandler(as.String)).pull(), 3.seconds).payload 
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