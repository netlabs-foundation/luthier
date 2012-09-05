package uy.com.netlabs.esb
package endpoint

import typelist._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import java.nio.file._

object File {
  def apply(path: String, charset: String = "UTF-8") = new EndpointFactory[FileEndpoint] {
    def apply(f: Flow) = new FileEndpoint(f, path, charset)
  }
}
class FileEndpoint(f: Flow, path: String, charset: String) extends Sink with PullEndpoint {
  type Payload = Array[Byte]
  type SupportedTypes = Iterable[Byte] :: Array[Byte] :: String :: java.io.Serializable :: TypeNil

  implicit val flow = f
  def push[Payload: SupportedType](msg: Message[Payload]): Future[Unit] = {
    Future {
      msg.payload match {
        case it: Iterable[Byte @unchecked] => println("a"); Files.write(Paths.get(path), it.toArray, StandardOpenOption.CREATE)
        case arr: Array[Byte]   => println("b"); Files.write(Paths.get(path), arr, StandardOpenOption.CREATE)
        case str: String        => println("Writing " + str); Files.write(Paths.get(path), java.util.Arrays.asList(str), java.nio.charset.Charset.forName(charset), StandardOpenOption.CREATE)
        case obj: java.io.Serializable =>println("d"); 
          val out = new java.io.ObjectOutputStream(Files.newOutputStream(Paths.get(path), StandardOpenOption.CREATE))
          try out.writeObject(obj)
          finally {
            if (out != null) out.close
          }
        case other => throw new UnsupportedOperationException("Validated type was invalid?")
      }
      println("tuve éxito")
    }
  }

  def pull(): Future[Message[Payload]] = {
    Future { Message(Files.readAllBytes(Paths.get(path))) }
  }
  def dispose() {}
  def start() {}
}