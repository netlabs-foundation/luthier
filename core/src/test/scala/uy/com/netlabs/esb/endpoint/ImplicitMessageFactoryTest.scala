package uy.com.netlabs.esb
package endpoint

import org.scalatest.{ BeforeAndAfter, FunSpec }
import scala.concurrent.duration._

class ImplicitMessageFactoryTest extends BaseFlowsTest {
  describe("An implicit request of MessageFactory inside the logic block") {
    it("should succeed") {
      new Flows {
        val appContext = testApp
        val fakeEndpoint: EndpointFactory[Source] = new base.DummySource
        new Flow("test")(fakeEndpoint) {
          logic { m =>
            val fr = flowRun //this call should succeed
            val mf = implicitly[MessageFactory] //a flowrun is a messageFactory
            m.map(_ => "another message!")
          }
          logic {m2 =>
            val mf2: MessageFactory = m2
            m2
          }
        }
      }
      new Flows {
        val appContext = testApp
        val fakeEndpoint: EndpointFactory[Source] = new base.DummySource
        new Flow("test2")(fakeEndpoint) {
          logic { m =>
            val implicitRootMessage: RootMessage[this.type] = implicitly //this call should succeed by means of the macro
            implicitRootMessage
          }
        }
      }
    }
  }
}