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
          import scala.sys.process.{ Process => _, _ }
          Process.string("ifconfig" #| Seq("grep", "inet addr")).pull()(m) onSuccess { case r => println(r) }
          Await.result(Process.string("echo hi there!").pull()(m) map (m => m.payload === "hi there!"), 0.3.seconds)
        }
        Try(assert(Await.result(res, 0.3.seconds)))
      }
    }
  }
}