package uy.com.netlabs.esb
package endpoint
package cxf

import scala.concurrent._
import scala.concurrent.util.duration._

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
        val url = "http://localhost:8080/testws"
        val server = new Flow("service")(Ws(Sei[WsDef](url))(_.echo _)) {
          logic {m => m} //really simply echo!
        }
        server.start
        
        val timeout = 10.seconds
        val res = inFlow { (flow, m) => 
          import flow._
          val greet = "Hi there!"
          WsInvoker[String](WsClient(url + "?wsdl"), "echo", true).ask(m.map(_ => Seq(greet)))
          WsInvoker[String](WsClient(url + "?wsdl"), "echo", true).ask(m.map(_ => Seq(greet)))
          WsInvoker[String](WsClient(url + "?wsdl"), "echo", true).ask(m.map(_ => Seq(greet))) //several request, because I want to also test proper disposal
          val resp = Await.result(WsInvoker[String](WsClient(url + "?wsdl"), "echo", true).ask(m.map(_ => Seq(greet))), timeout) //2secs, the server has to be setup after all
          resp.payload === greet
        }
        val ass = Await.result(res, timeout)
        server.stop()
        assert(ass)
      }
    }
  }
}