package uy.com.netlabs.esb
package endpoint

import org.scalatest.{ BeforeAndAfter, FunSpec }
import scala.concurrent.util.duration._

class ImplicitMessageFactoryTest extends BaseFlowsTest {
  describe("An implicit request of MessageFactory inside the logic block") {
    it("should succeed") {
      new Flows {
        val appContext = testApp
        val fakeEndpoint: EndpointFactory[Source] = new base.DummySource
        new Flow("test")(fakeEndpoint) {
          logic { m =>
            val mf = implicitly[MessageFactory]
            m.map(_ => "another message!")
          }
        }
      }
    }
  }
}