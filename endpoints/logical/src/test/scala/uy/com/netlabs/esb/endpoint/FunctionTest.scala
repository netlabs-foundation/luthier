package uy.com.netlabs.esb
package endpoint

import java.nio.file.Paths
import scala.concurrent._, util.duration._
import scala.util._
import language._
import logical.Polling._
import org.scalatest._
import uy.com.netlabs.esb.AppContext
import uy.com.netlabs.esb.Flows

class FunctionTest extends FunSpec with BeforeAndAfter {
  var myApp: AppContext = _
  before {
    myApp = new AppContext {
      val name = "Test Function App"
      val rootLocation = Paths.get(".")
    }
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
        flow.stop
        assert(res.get)
      }
    }

    it("Should be able to ask functions") {
      new Flows {
        val appContext = myApp

        val result = Promise[Option[String]]()
        val flow = new Flow("Ask Function")(Metronome(1.seconds)) { //initial delay is 0
          logic { m =>
            val msg = "this is a function that returns a text"
            result completeWith {
              Function[String]().ask(Message(() => msg)) map (m => m.payload === msg)
            }
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 0.25.seconds))
        flow.stop
        assert(res.get)
      }
    }
  }
  
  describe("A Process Endpoint") {
    it("Should be able to run processes") {
      
      new Flows {
        val appContext = myApp

        val result = Promise[Option[String]]()
        val flow = new Flow("Run processes")(Metronome(1.seconds)) { //initial delay is 0
          logic { m =>
            result completeWith {
              import scala.sys.process.{Process => _, _}
              Process.string("ifconfig" #| Seq("grep","inet addr")).pull onSuccess {case r => println(r)}
              Process.string("echo hi there!").pull map (m => m.payload === "hi there!")
            }
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 0.3.seconds))
        flow.stop
        assert(res.get)
      }
    }
  }
}