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

object Tcp extends StreamEndpointComponent {
  
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
    val clientTypeProof =  implicitly[this.type <:< ClientType]
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
//                                       val onWaitAction: ReadWaitAction[S, P],
                                       val serializer: R => Array[Byte]) extends HandlerComponent[S, P, R, R :: TypeNil] {
    def registerReader(reader) = client.multiplexer.readers += reader
    def processResponseFromRequestedMessage(m) = client.multiplexer addPending ByteBuffer.wrap(serializer(m.payload.value.asInstanceOf[R])) 

  }
  def closeClient(client: Message[SocketClient]) {
    client.payload.dispose
  }

  object Handler {
    def apply[S, P, R](message: Message[SocketClient],
                       reader: Consumer[S, P]) = new Handler(message.payload, reader, null).OneWay
    def apply[S, P, R](message: Message[SocketClient],
                       reader: Consumer[S, P],
                       serializer: R => Array[Byte]) = new Handler(message.payload, reader, serializer).RequestResponse

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
    def apply[S, P, R](socket: SocketChannel, reader: Consumer[S, R], writer: P => Array[Byte] = null, readBuffer: Int = 1024 * 5, ioWorkers: Int = 2) = EF(socket, reader, writer, readBuffer, ioWorkers)

    class SocketClientEndpoint[S, P, R](val flow: Flow,
                                        socket: SocketChannel,
                                        reader: Consumer[S, R],
                                        writer: P => Array[Byte],
                                        readBuffer: Int,
                                        ioWorkers: Int) extends base.BaseSource with base.BaseResponsible with base.BasePullEndpoint with base.BaseSink with Askable {
      type Payload = R
      type SupportedTypes = P :: TypeNil
      type Response = R

      @volatile
      private[this] var stop = false
      def start() {
        val initializeInboundEndpoint = onEventHandler != null ||
          (onRequestHandler != null && { require(writer != null, "Responsible must define how to serialize responses"); true })
        if (initializeInboundEndpoint) {
          Future {
            val readFunction = socket.read(_: ByteBuffer)
            while (!stop) {
              val read = syncConsumer.consume(readFunction)
              if (read.isSuccess) {
                val in = newReceviedMessage(read.get)
                if (onEventHandler != null) onEventHandler(in)
                else onRequestHandler(in) onComplete {
                  case Success(response) => socket.write(ByteBuffer.wrap(writer(response.payload.value.asInstanceOf[P])))
                  case Failure(err) => log.error(err, s"Error processing request $in")
                }
              } else log.error(read.failed.get, s"Failure reading from socket $socket")
            }
          } onFailure {
            case ex =>
              log.error(ex, s"Stoping flow $flow because of error")
              flow.dispose()
          }
        }
      }

      def dispose {
        stop = true
        scala.util.Try(socket.close())
        ioProfile.dispose()
      }

      private val syncConsumer = Consumer.Synchronous(reader, readBuffer)
      protected def retrieveMessage(mf: uy.com.netlabs.esb.MessageFactory): uy.com.netlabs.esb.Message[Payload] = {
        mf(syncConsumer.consume(socket.read).get)
      }

      val ioProfile = base.IoProfile.threadPool(ioWorkers)
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