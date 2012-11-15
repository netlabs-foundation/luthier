package uy.com.netlabs.esb
package endpoint
package stream

import com.sun.nio.sctp._
import java.net._
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels._
import scala.util._
import scala.concurrent._, duration._
import language._

import typelist._

object Sctp extends StreamEndpointComponent {

  protected type ClientType = SctpClient
  protected type ClientConn = SctpChannel
  protected type ServerType = ServerChannelEndpoint
  def newClient(server, conn, readBuffer) = new SctpClient(server, conn, readBuffer)

  object Server {
    case class EF private[Server] (addr: SocketAddress, readBuffer: Int) extends EndpointFactory[ServerChannelEndpoint] {
      def apply(f) = new ServerChannelEndpoint(f, addr, readBuffer)
    }

    def apply(port: Int, readBuffer: Int) = EF(new InetSocketAddress(port), readBuffer)
  }

  class ServerChannelEndpoint private[Sctp] (val flow: Flow, socketAddress: SocketAddress, val readBuffer: Int) extends ServerComponent {
    val serverChannel = {
      val res = SctpServerChannel.open()
      res.configureBlocking(false)
      res.bind(socketAddress)
      res
    }
    def serverChannelAccept() = serverChannel.accept()
    val serverTypeProof = implicitly[this.type <:< ServerType]
  }

  private[Sctp] class SctpClient(val server: ServerChannelEndpoint, val conn: SctpChannel, val readBuffer: ByteBuffer) extends ClientComponent {
    val clientTypeProof = implicitly[this.type <:< ClientType]
    val selector = server.selector

    var readers = Map.empty[Int, ByteBuffer => Unit] //set of readers
    var cachedOutputStreams = Map.empty[Int, MessageInfo]
    private var sendPending = Vector.empty[(Int, ByteBuffer)]
    def addPending(buff: (Int, ByteBuffer)) {
      if (key.isValid()) {
        sendPending :+= buff
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE)
        selector.wakeup() //so that he register my recent update of interests
      } /*else key is invalid*/
    }
    val reactor = new SelectionKeyReactor
    @volatile var reachedEOF = false
    reactor.afterProcessingInterests = key => {
      try {
        debug("Client operating: " + keyDescr(key))
        var reachedEOF = false
        if (key.isReadable()) {
          def iterate() {
            readBuffer.clear
            val recievedMessageInfo = conn.receive(readBuffer, null, new NotificationHandler[Null] {
              def handleNotification(notif, attachment) = notif match {
                case a: AssociationChangeNotification => a.event match {
                  case AssociationChangeNotification.AssocChangeEvent.COMM_LOST |
                    AssociationChangeNotification.AssocChangeEvent.SHUTDOWN |
                    AssociationChangeNotification.AssocChangeEvent.RESTART =>
                    reachedEOF = true //hackish.. but legal. Pot gets you doing stuff...
                    HandlerResult.RETURN
                  case _ => HandlerResult.CONTINUE
                }
                case _: SendFailedNotification | _: ShutdownNotification =>
                  reachedEOF = true //hackish.. but legal. Pot gets you doing stuff...
                  HandlerResult.RETURN
                case _ => HandlerResult.CONTINUE
              }
            })
            if (recievedMessageInfo != null) {
              reachedEOF = recievedMessageInfo.bytes == -1
              readBuffer.flip
              debug("  Reading")
              readers.get(recievedMessageInfo.streamNumber()) foreach (_(readBuffer.duplicate()))
              debug("  Done")
              iterate()
            }
          }
          iterate()
        }
        if (key.isWritable()) {
          var canWrite = true
          sendPending
          debug("Start writing")
          while (canWrite && sendPending.nonEmpty) {
            val ((stream, buffer), t) = (sendPending.head, sendPending.tail)
            val mi = cachedOutputStreams.getOrElse(stream, MessageInfo.createOutgoing(conn.association(), null, stream))
            cachedOutputStreams += stream -> mi
            val wrote = conn.send(buffer, mi)
            if (buffer.hasRemaining()) { //could not write all of the content
              canWrite = false
            } else {
              sendPending = sendPending.tail
            }
          }
        }
        debug("pendings: " + sendPending)
        if (sendPending.isEmpty) key.interestOps(SelectionKey.OP_READ)
        if (reachedEOF || !key.channel.isOpen()) dispose()
      } catch {
        case ex: Exception => server.log.error(ex, "Error on client " + keyDescr(key) + ". Disposing"); dispose()
      }
    }
    key.attach(reactor)
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
  class Handler[S, P, R] private[Sctp] (val client: SctpClient,
                                        val streamId: Int,
                                        val reader: Consumer[S, P],
                                        val serializer: R => Array[Byte]) extends HandlerComponent[S, P, R, (Int, R) :: TypeNil] {
    def registerReader(reader) = client.readers += streamId -> reader
    def processResponseFromRequestedMessage(m) = {
      val (stream, r) = m.payload.value.asInstanceOf[(Int, R)]
      client addPending stream -> ByteBuffer.wrap(serializer(r))
    }
  }
  def closeClient(client: Message[SctpClient]) {
    client.payload.dispose
  }

  object Handler {
    def apply[S, P, R](message: Message[SctpClient],
                       streamId: Int,
                       reader: Consumer[S, P]) = new Handler(message.payload, streamId, reader, null).OneWay
    def apply[S, P, R](message: Message[SctpClient],
                       streamId: Int,
                       reader: Consumer[S, P],
                       serializer: R => Array[Byte]) = new Handler(message.payload, streamId, reader, serializer).RequestResponse

  }

  /**
   * Simple Sctp client.
   * Given the nature of a single socket, there is no need for selectors. A simple implementation
   * of the base endpoints will suffice.
   *
   * *Implementation notes* When ask is performed, the first message received will be retrieved,
   * in whichever stream it comes.
   */
  object Client {
    import typelist._
    import scala.concurrent._

    case class EF[S, P, R] private[Client] (socket: SctpChannel, reader: Consumer[S, R], writer: P => Array[Byte], readBuffer: Int, ioWorkers: Int) extends EndpointFactory[SctpClientEndpoint[S, P, R]] {
      def apply(f: Flow) = new SctpClientEndpoint[S, P, R](f, socket, reader, writer, readBuffer, ioWorkers)
    }
    def apply[S, P, R](socket: SctpChannel, reader: Consumer[S, R], writer: P => Array[Byte] = null, readBuffer: Int = 1024 * 5, ioWorkers: Int = 2) = EF(socket, reader, writer, readBuffer, ioWorkers)

    class SctpClientEndpoint[S, P, R](val flow: Flow,
                                      socket: SctpChannel,
                                      reader: Consumer[S, R],
                                      writer: P => Array[Byte],
                                      readBuffer: Int,
                                      ioWorkers: Int) extends base.BaseSource with base.BaseResponsible with base.BaseSink with Askable {
      type Payload = R
      type SupportedTypes = (Int, P) :: TypeNil
      type Response = R

      @volatile
      private[this] var stop = false
      def start() {
        val initializeInboundEndpoint = onEventHandler != null ||
          (onRequestHandler != null && { require(writer != null, "Responsible must define how to serialize responses"); true })
        if (initializeInboundEndpoint) {
          Future {
            while (!stop) {
              val read = syncConsumer.consume(readFunction)
              if (read.isSuccess) {
                val in = newReceviedMessage(read.get)
                if (onEventHandler != null) onEventHandler(in)
                else onRequestHandler(in) onComplete {
                  case Success(response) =>
                    val (stream, p) = response.payload.value.asInstanceOf[(Int, P)]
                    socket.send(ByteBuffer.wrap(writer(p)), MessageInfo.createOutgoing(socket.association(), null, stream))
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

      val ioProfile = base.IoProfile.threadPool(ioWorkers)
      protected def pushMessage[MT: SupportedType](msg): Unit = {
        val (stream, p) = msg.payload.asInstanceOf[(Int, P)]
        socket.send(ByteBuffer.wrap(writer(p)), MessageInfo.createOutgoing(socket.association(), null, stream))
      }

      val readFunction = (buff: ByteBuffer) => {
        val mi = socket.receive(buff, null, null)
        mi.bytes()
      }
      def ask[MT: SupportedType](msg, timeOut): Future[Message[Response]] = Future {
        val stream = msg.payload.asInstanceOf[(Int, P)]._1
        pushMessage(msg)(null) //by pass the evidence..
        msg map (_ => syncConsumer.consume(readFunction).get)
      }(ioExecutionContext)
    }

  }
}