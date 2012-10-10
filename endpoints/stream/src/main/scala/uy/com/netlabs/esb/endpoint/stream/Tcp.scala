package uy.com.netlabs.esb
package endpoint
package stream

import java.net._
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels._

object Tcp extends Streams {
  object Server {
    case class EF private[Server] (addr: SocketAddress, readBuffer: Int) extends EndpointFactory[ServerSocketEndpoint] {
      def apply(f) = new ServerSocketEndpoint(f, addr, readBuffer)
    }

    def apply(port: Int, readBuffer: Int) = EF(new InetSocketAddress(port), readBuffer)
  }

  class ServerSocketEndpoint private[Tcp] (val flow: Flow, socketAddress: SocketAddress, readBuffer: Int) extends ServerEndpoint[SocketClient] {
    type ServerChannel = ServerSocketChannel
    val serverChannel = {
      val res = ServerSocketChannel.open()
      res.configureBlocking(false)
      res.bind(socketAddress)
      res.setOption(StandardSocketOptions.SO_REUSEADDR, true: java.lang.Boolean)
      res
    }
    val clientAcceptOp = SelectionKey.OP_ACCEPT
    def accept() = {
      Option(serverChannel.accept()) map { s =>
        s.configureBlocking(false)
        new SocketClient(this, s, ByteBuffer.allocate(readBuffer))
      }
    }
  }

  private[Tcp] class SocketClient(val server: ServerSocketEndpoint, val channel: SocketChannel, val readBuffer: ByteBuffer) extends Client {
    protected def disposeClient() {
      try channel.close() catch { case _: Exception => }
    }
  }

  def SocketConn(addr: String, port: Int): SocketChannel = SocketConn(new InetSocketAddress(addr, port))
  def SocketConn(addr: InetSocketAddress): SocketChannel = {
    val res = SocketChannel.open()
    res.setOption(StandardSocketOptions.SO_REUSEADDR, true: java.lang.Boolean)
    res.connect(addr)
    res
  }

  /**
   * Simple Tcp client.
   * Given the nature of a single socket, there is no need selectors. An simple implementation
   * of the base endpoints will suffice.
   */
  object Client {
    import typelist._
    import scala.concurrent._

    case class EF[S, P, R] private[Client] (socket: SocketChannel, reader: Consumer[S, R], writer: P => Array[Byte], readBuffer: Int, ioWorkers: Int) extends EndpointFactory[SocketClientEndpoint[S, P, R]] {
      def apply(f: Flow) = new SocketClientEndpoint[S, P, R](f, socket, reader, writer, readBuffer, ioWorkers)
    }
    def apply[S, P, R](socket: SocketChannel, reader: Consumer[S, R], writer: P => Array[Byte] = null, readBuffer: Int, ioWorkers: Int = 2) = EF(socket, reader, writer, readBuffer, ioWorkers)

    class SocketClientEndpoint[S, P, R](val flow: Flow, socket: SocketChannel, reader: Consumer[S, R], writer: P => Array[Byte], readBuffer: Int, ioWorkers: Int) extends base.BasePullEndpoint with base.BaseSink with Askable {
      type Payload = R
      type SupportedTypes = P :: TypeNil
      type Response = R
      def start() {

      }
      def dispose {
        scala.util.Try(socket.close())
        ioExecutor.shutdown()
      }

      private val inBff = ByteBuffer.allocate(readBuffer)
      private var lastState = reader.initialState
      private var bufferedInput: Seq[R] = Seq.empty
      protected def retrieveMessage(mf: uy.com.netlabs.esb.MessageFactory): uy.com.netlabs.esb.Message[Payload] = {
        if (bufferedInput.nonEmpty) {
          val res = bufferedInput.head
          bufferedInput = bufferedInput.tail
          mf(res)
        } else {
          def iterate(): R = {
            inBff.clear()
            val read = socket.read(inBff)
            inBff.flip
            val r = reader.consume(Content(lastState, inBff))
            lastState = r.state
            r match {
              case reader.NeedMore(_) =>
                if (read < 0) throw new EOFException()
                else iterate()
              case reader.ByProduct(content, state) =>
                bufferedInput = content.tail
                content.head
            }
          }
          mf(iterate())
        }
      }

      val ioExecutor = java.util.concurrent.Executors.newFixedThreadPool(ioWorkers)
      val ioExecutionContext = ExecutionContext.fromExecutor(ioExecutor)
      protected def pushMessage[MT: SupportedType](msg): Unit = {
        socket.write(ByteBuffer.wrap(writer(msg.payload.asInstanceOf[P])))
      }

      def ask[MT: SupportedType](msg, timeOut): Future[Message[Response]] = Future {
        pushMessage(msg)(null) //by pass the evidence..
        retrieveMessage(msg)
      }(ioExecutionContext)
    }

  }
}