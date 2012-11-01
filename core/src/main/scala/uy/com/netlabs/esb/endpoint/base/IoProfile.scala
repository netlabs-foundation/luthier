package uy.com.netlabs.esb.endpoint.base

import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

trait IoProfile {
  def executionContext: ExecutionContext
  def dispose(): Unit
}
object IoProfile {
  def threadPool(threads: Int) = new IoProfile {
    val executor = Executors.newFixedThreadPool(threads)
    val executionContext = ExecutionContext.fromExecutor(executor)
    def dispose() {executor.shutdown()}
  }
}