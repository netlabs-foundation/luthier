package uy.com.netlabs.esb
package endpoint
package http

import java.nio.file.{ Paths, Files }

import scala.concurrent._
import scala.concurrent.util.{ Duration, duration }, duration._
import scala.util._
import language._

import logical.Polling._

import org.scalatest._
import dispatch.{ Promise => _, _ }

class HttpTest extends BaseFlowsTest {
  describe("An Http Endpoint") {
    it("Should be able to perform HTTP request and transform its result") {
      new Flows {
        val appContext = testApp
        val result = Promise[Option[String]]()
        val flow = new Flow("Poll Http")(Poll(Http(url("http://notes.implicit.ly/post/30567701311/dispatch-0-9-1") OK as.jsoup.Document), 1.seconds)) { //initial delay is 0
          logic { m =>
            result success {
              if (m.payload.select("a[href]").size != 0) None
              else Some("payload with 0 links?")
            }
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 5.seconds))
        flow.stop
        assert(res.get)
      }
    }
    it("Should be able to ask HTTP request and transform its result") {
      new Flows {
        val appContext = testApp

        val res = inFlow { flow =>
          import flow._
          val req = url("http://notes.implicit.ly/post/30567701311/dispatch-0-9-1") OK as.jsoup.Document
          Await.result(Http[org.jsoup.nodes.Document]().ask(Message(req)) map { m =>
            if (m.payload.select("a[href]").size != 0) None
            else Some("payload with 0 links?")
          }, 5.seconds)
        }
        Try(assert(Await.result(res, 6.seconds)))
      }
    }
  }
}