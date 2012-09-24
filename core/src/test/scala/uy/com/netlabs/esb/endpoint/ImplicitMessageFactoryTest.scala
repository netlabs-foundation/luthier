package uy.com.netlabs.esb
package endpoint

import org.scalatest.{ BeforeAndAfter, FunSpec }
import scala.concurrent.util.duration._

class ImplicitMessageFactoryTest extends BaseFlowsTest {
  describe("An implicit request of MessageFactory inside the logic block") {
    it("should succeed") {
      new Flows {
        val appContext = testApp
        val fakeEndpoint: EndpointFactory[Source] = new EndpointFactory[Source] {
          def canEqual(that: Any) = that.asInstanceOf[AnyRef] eq this
          def apply(f: uy.com.netlabs.esb.Flow) = new base.BaseSource {
            implicit def flow = f
            def start() {}
            def dispose() {}
          }
        }
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