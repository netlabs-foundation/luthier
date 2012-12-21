package uy.com.netlabs.esb

import scala.concurrent._, duration._

class FlowPatternsTest extends endpoint.BaseFlowsTest {
  describe("Retrying with 5 attempts") {
    it("should perform 5 retries before giving up") {
      new Flows {
        val appContext = testApp
        @volatile var count = 0
        val run = inFlow { (flow, msg) =>
          import flow._
          Await.result(retryAttempts(5, "fail op")(blocking { println("Counting!"); count += 1 })(_ => true)(msg.flowRun), 2.second)
        }
        Await.result(run, 2.second)
        assert(count === 5)
      }
    }
  }
}