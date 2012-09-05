package uy.com.netlabs.esb

import java.nio.file.{ Paths, Files }
import scala.concurrent.Future
import scala.concurrent.util.Duration
import endpoint._, PollingFeatures._
import language._

object FastTest extends App {
  val myApp = new AppContext {
    val name = "Test App"
    val rootLocation = Paths.get(".")
  }

  new Flows {
    val appContext = myApp

    val pollFile = Poll(endpoint = File(path = "/tmp/testFile"), every = 5.seconds)
    val file = File(path = "/tmp/testFile2")

//    val responsible: EndpointFactory[Responsible with Source with Askable { type Payload = Int }] = null
//    val marako: EndpointFactory[Responsible { type Payload = Int }] = null
//
//    new Flow("rr")(marako) {
//      logic { m =>
//        m.mapPayload(_ * 3)
//      }
//    }
//    new Flow("sldkf")(responsible RequestResponse) {
//      logic { m =>
//        m.payload * 4
//        file.push(Message("lalalalala"))
//        val f = file.pull()
//        f onSuccess {case m => m}
//        f
//      }
//    }
    new Flow("poll the file!")(pollFile) {
      logic { m =>
        println("PUSHING!")
        file.push(Message("Read: " + new String(m.payload)))
      }
    }

    registeredFlows foreach (_.start)
  }
}