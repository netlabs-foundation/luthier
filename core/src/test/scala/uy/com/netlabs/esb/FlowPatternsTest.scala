package uy.com.netlabs.esb

import scala.concurrent._, duration._

class FlowPatternsTest extends endpoint.BaseFlowsTest {
  val totalCount = 15
  describe(s"Retrying with $totalCount attempts") {
    it(s"should perform $totalCount retries before giving up") {
      new Flows {
        val appContext = testApp
        @volatile var count = 0
        val run = inFlow { (flow, msg) =>
          import flow._
          retryAttempts(totalCount, "fail op")(doWork { println("Counting!"); count += 1 })(_ => count != totalCount)(msg.flowRun)
        }
        Await.result(Await.result(run, 1.hour), 1.seconds)
        assert(count === totalCount)
      }
    }
  }
  val maxBackoff = 600.millis
  describe(s"Retrying with $maxBackoff attempts") {
    it(s"should continue backing off retries, until failure.") {
      new Flows {
        val appContext = testApp
        @volatile var count = 0
        val run = inFlow { (flow, msg) =>
          import flow._
          Await.result( //must wait for the result here, or the flow gets disposed preemptively.
          retryBackoff(100, 2, maxBackoff.toMillis, "fail op")(blocking { println("Counting!"); count += 1 })(_ => true)(msg.flowRun)
          ,1.2.seconds)
        }
        Await.result(run, 1.2.seconds)
        assert(count === 4)
      }
    }
  }
}