package uy.com.netlabs.esb
package endpoint.http

import java.nio.file.{ Paths, Files }

import scala.concurrent._
import scala.concurrent.util.{ Duration, duration }, duration._
import scala.util._
import language._

import endpoint.PollingFeatures._

import org.scalatest._
import dispatch.{ Promise => _, _ }

class HttpTest extends FunSpec with BeforeAndAfter {
  var myApp: AppContext = _
  before {
    myApp = new AppContext {
      val name = "Test Http App"
      val rootLocation = Paths.get(".")
    }
  }
  after {
    myApp.actorSystem.shutdown()
  }

  describe("A Function Endpoint") {
    it("Should be able to perform HTTP request and transform its result") {
      new Flows {
        val appContext = myApp

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
        val appContext = myApp

        val result = Promise[Option[String]]()
        val flow = new Flow("Poll Http")(endpoint.Metronome(1.seconds)) { //initial delay is 0
          logic { m =>
            val req = url("http://notes.implicit.ly/post/30567701311/dispatch-0-9-1") OK as.jsoup.Document
            result completeWith {
              Http[org.jsoup.nodes.Document]().ask(Message(req)) map { m =>
                if (m.payload.select("a[href]").size != 0) None
                else Some("payload with 0 links?")
              }
            }
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 5.seconds))
        flow.stop
        assert(res.get)
      }
    }
  }
}