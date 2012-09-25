package uy.com.netlabs.esb
package endpoint.cxf.codefirst

import javax.jws._

object WsFastTest extends App {
  trait WSEndpoint {
    @WebMethod
    def callmeDude(number: String, when: Long): Unit
    @WebMethod
    def whereNow(direction: String): Long
    @WebMethod
    def iDoNothing(): Unit
  }

  val app = new AppContext {
    def name: String = "WsFastTest"
    def rootLocation: java.nio.file.Path = null

  }

  new Flows {
    def appContext = app
    val s = Sei[WSEndpoint]("http://localhost:8080/something")
    new Flow("someName")(Ws(s)(_.callmeDude _)) {
      logic { m =>
      println(s"I should call ${m.payload._1} before ${new java.util.Date(m.payload._2)}")
      m.map(_ => ())
      }
    }
    new Flow("someName2")(Ws(s)(_.whereNow _)) {
      logic { m =>
        println(s"I should go to  ${m.payload}")
        m.map(_ => 2354349857l)
        "lalalal"
      }
    }
  }.registeredFlows foreach (_.start)
}