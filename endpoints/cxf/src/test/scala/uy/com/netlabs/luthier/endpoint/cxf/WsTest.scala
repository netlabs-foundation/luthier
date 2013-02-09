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