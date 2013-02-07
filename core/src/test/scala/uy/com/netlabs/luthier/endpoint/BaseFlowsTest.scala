package uy.com.netlabs.luthier
package endpoint

import org.scalatest.{ BeforeAndAfter, FunSpec }
import java.nio.file.Paths

class BaseFlowsTest extends FunSpec with BeforeAndAfter {
  var testApp: AppContext = _
  before {
    testApp = new AppContext {
      val name = "Test App"
      val rootLocation = Paths.get(".")
    }
  }
  after {
    testApp.actorSystem.shutdown()
  }
}