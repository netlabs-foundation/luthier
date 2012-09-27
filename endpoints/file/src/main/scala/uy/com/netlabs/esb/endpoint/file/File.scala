package uy.com.netlabs.esb
package endpoint.file

import typelist._
import scala.concurrent.Future
import java.nio.file._
import scala.concurrent.ExecutionContext

object File {
  private case class EF(path: String, charset: String, ioThreads: Int) extends EndpointFactory[FileEndpoint] {
    def apply(f: Flow) = new FileEndpoint(f, path, charset, ioThreads)
  }
  def apply(path: String, charset: String = "UTF-8", ioThreads: Int = 1): EndpointFactory[FileEndpoint] = EF(path, charset, ioThreads)
}
class FileEndpoint(f: Flow, path: String, charset: String, ioThreads: Int) extends endpoint.base.BaseSink with endpoint.base.BasePullEndpoint {
  type Payload = Array[Byte]
  type SupportedTypes = Iterable[Byte] :: Array[Byte] :: String :: java.io.Serializable :: TypeNil

  implicit val flow = f
  
  private[this] lazy val ioExecutor = java.util.concurrent.Executors.newFixedThreadPool(ioThreads)
  implicit lazy val ioExecutionContext = ExecutionContext.fromExecutor(ioExecutor)
  
  protected def pushMessage[Payload: SupportedType](msg: Message[Payload]) {
    msg.payload match {
      case it: Iterable[Byte @unchecked] => Files.write(Paths.get(path), it.toArray, StandardOpenOption.CREATE)
      case arr: Array[Byte] => Files.write(Paths.get(path), arr, StandardOpenOption.CREATE)
      case str: String => Files.write(Paths.get(path), str.getBytes(charset), StandardOpenOption.CREATE)
      case obj: java.io.Serializable =>
        val out = new java.io.ObjectOutputStream(Files.newOutputStream(Paths.get(path), StandardOpenOption.CREATE))
        try out.writeObject(obj)
        finally {
          if (out != null) out.close
        }
      case other => throw new UnsupportedOperationException("Validated type was invalid?")
    }
  }

  protected def retrieveMessage(mf) = mf(Files.readAllBytes(Paths.get(path)))

  def dispose() {
    ioExecutor.shutdown()
  }
  def start() {}
}