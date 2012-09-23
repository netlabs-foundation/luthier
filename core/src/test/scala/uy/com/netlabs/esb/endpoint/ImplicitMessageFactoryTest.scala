package uy.com.netlabs.esb
package endpoint

import org.scalatest.{ BeforeAndAfter, FunSpec }
import scala.concurrent.util.duration._

class ImplicitMessageFactoryTest extends BaseFlowsTest {
  new Flows {
    val appContext = testApp
    new Flow("test")(endpoint.Metronome(1.second)) {
      logic {m =>
        testMe
        m.map(_ => "another message!")
      }
    }
  }
}