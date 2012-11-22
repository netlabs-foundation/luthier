package uy.com.netlabs.esb
package endpoint
package stream

import java.net._
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels._
import scala.util._
import scala.concurrent._, duration._
import language._

import typelist._

object Tcp extends StreamEndpointServerComponent {

  protected type ClientType = SocketClient
  protected type ClientConn = SocketChannel
  protected type ServerType = ServerSocketEndpoint
  def newClient(server, conn, readBuffer) = new SocketClient(server, conn, readBuffer)

  object Server {
    case class EF private[Server] (addr: SocketAddress, readBuffer: Int) extends EndpointFactory[ServerSocketEndpoint] {
      def apply(f) = new ServerSocketEndpoint(f, addr, readBuffer)
    }

    def apply(port: Int, readBuffer: Int) = EF(new InetSocketAddress(port), readBuffer)
  }

  class ServerSocketEndpoint private[Tcp] (val flow: Flow, socketAddress: SocketAddress, val readBuffer: Int) extends ServerComponent {
    val serverChannel = {
      val res = ServerSocketChannel.open()
      res.configureBlocking(false)
      res.bind(socketAddress)
      res.setOption(StandardSocketOptions.SO_REUSEADDR, true: java.lang.Boolean)
      res
    }
    def serverChannelAccept() = serverChannel.accept()
    val serverTypeProof = implicitly[this.type <:< ServerType]
  }

  private[Tcp] class SocketClient(val server: ServerSocketEndpoint, val conn: SocketChannel, val readBuffer: ByteBuffer) extends ClientComponent {
    val multiplexer = new ChannelMultiplexer(key, server.selector, readBuffer, conn.read, conn.write, this, server.log)
    val clientTypeProof = implicitly[this.type <:< ClientType]
  }

  def SocketConn(addr: String, port: Int): SocketChannel = SocketConn(new InetSocketAddress(addr, port))
  def SocketConn(addr: InetSocketAddress): SocketChannel = {
    val res = SocketChannel.open()
    res.setOption(StandardSocketOptions.SO_REUSEADDR, true: java.lang.Boolean)
    res.connect(addr)
    res
  }

  /**
   * Handlers are endpoint factories and the endpoint per se. They are source or
   * responsible endpoints, so there is no need to produce a different endpoint.
   * This class is desgined to work in a subflow for a server endpoint, registring
   * the reader and serializer into the client.
   */
  class Handler[S, P, R] private[Tcp] (val client: SocketClient,
                                       val reader: Consumer[S, P],
                                       val serializer: R => Array[Byte],
                                       val onReadWaitAction: ReadWaitAction[S, P]) extends HandlerComponent[S, P, R, R :: TypeNil] {
    def registerReader(reader) = client.multiplexer.readers += reader
    def processResponseFromRequestedMessage(m) = client.multiplexer addPending ByteBuffer.wrap(serializer(m.payload.value.asInstanceOf[R]))

  }
  def closeClient(client: Message[SocketClient]) {
    client.payload.dispose
  }

  object Handler {
    def apply[S, P, R](message: Message[SocketClient],
                       reader: Consumer[S, P],
                       onReadWaitAction: ReadWaitAction[S, P] = ReadWaitAction.DoNothing) = new Handler(message.payload, reader, null, onReadWaitAction).OneWay
    def apply[S, P, R](message: Message[SocketClient],
                       reader: Consumer[S, P],
                       serializer: R => Array[Byte],
                       onReadWaitAction: ReadWaitAction[S, P] = ReadWaitAction.DoNothing) = new Handler(message.payload, reader, serializer, onReadWaitAction).RequestResponse
  }

  /**
   * Simple Tcp client.
   * Given the nature of a single socket, there is no need selectors. An simple implementation
   * of the base endpoints will suffice.
   */
  object Client {
    import typelist._
    import scala.concurrent._

    case class EF[S, P, R] private[Client] (socket: SocketChannel, reader: Consumer[S, R], writer: P => Array[Byte],
        onReadWaitAction: ReadWaitAction[S, R], readBuffer: Int, ioWorkers: Int) extends EndpointFactory[SocketClientEndpoint[S, P, R]] {
      def apply(f: Flow) = new SocketClientEndpoint[S, P, R](f, socket, reader, writer, onReadWaitAction, readBuffer, ioWorkers)
    }
    def apply[S, P, R](socket: SocketChannel, reader: Consumer[S, R], writer: P => Array[Byte] = null,
        onReadWaitAction: ReadWaitAction[S, R] = ReadWaitAction.DoNothing, readBuffer: Int = 1024 * 5, ioWorkers: Int = 2) = 
          EF(socket, reader, writer, onReadWaitAction, readBuffer, ioWorkers)

    class SocketClientEndpoint[S, P, R](val flow: Flow,
                                        val channel: SocketChannel,
                                        val reader: Consumer[S, R],
                                        val writer: P => Array[Byte],
                                        val onReadWaitAction: ReadWaitAction[S, R],
                                        val readBuffer: Int,
                                        val ioWorkers: Int) extends IO.IOChannelEndpoint with base.BasePullEndpoint {

      type ConsumerState = S
      type ConsumerProd = R
      type OutPayload = P
      def send(message) {
        channel.write(ByteBuffer.wrap(writer(message.payload)))
      }
      protected val readBytes: ByteBuffer => Int = channel.read(_: ByteBuffer)

      protected def retrieveMessage(mf: uy.com.netlabs.esb.MessageFactory): uy.com.netlabs.esb.Message[Payload] = {
        mf(syncConsumer.consume(channel.read).get)
      }
    }
  }
}