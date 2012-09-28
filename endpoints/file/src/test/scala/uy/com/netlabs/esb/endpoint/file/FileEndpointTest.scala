package uy.com.netlabs.esb
package endpoint
package file

import scala.util._
import scala.concurrent._
import scala.concurrent.util.duration._

class FileEndpointTest extends BaseFlowsTest {
  describe("A FileEndpoint") {
    val file = java.nio.file.Files.createTempFile("test", "file")
    val content = "someContent"
    it("should be able to write content") {
      new Flows {
        val appContext = testApp
        val result = Promise[Option[String]]()
        val flow = new Flow("test")(logical.Metronome(0.seconds)) {
          logic { m =>
            result completeWith File(file.toString).push(m.map(_ => "someContent")).map[Option[String]](_ => None).recover{case e => Some(e.toString)}
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 0.25.seconds))
        flow.stop
        assert(res.get)
      }
    }
    it("should be able to read content") {
      new Flows {
        val appContext = testApp
        val result = Promise[Option[String]]()
        val flow = new Flow("test")(logical.Metronome(0.seconds)) {
          logic { m =>
            result completeWith File(file.toString).pull.map(m => new String(m.payload) === content).recover{case e => Some(e.toString)}
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 0.25.seconds))
        flow.stop
        assert(res.get)
      }
    }
  }
}