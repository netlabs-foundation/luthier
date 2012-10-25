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

object Tcp {

  object Server {
    case class EF private[Server] (addr: SocketAddress, readBuffer: Int) extends EndpointFactory[ServerSocketEndpoint] {
      def apply(f) = new ServerSocketEndpoint(f, addr, readBuffer)
    }

    def apply(port: Int, readBuffer: Int) = EF(new InetSocketAddress(port), readBuffer)
  }

  class ServerSocketEndpoint private[Tcp] (val flow: Flow, socketAddress: SocketAddress, readBuffer: Int) extends base.BaseSource {
    type Payload = SocketClient
    val serverChannel = {
      val res = ServerSocketChannel.open()
      res.configureBlocking(false)
      res.bind(socketAddress)
      res.setOption(StandardSocketOptions.SO_REUSEADDR, true: java.lang.Boolean)
      res
    }
    def accept() = {
      Option(serverChannel.accept()) map { s =>
        s.configureBlocking(false)
        new SocketClient(this, s, ByteBuffer.allocate(readBuffer))
      }
    }

    lazy val selector = Selector.open()

    @volatile private[Tcp] var currentClients = Set.empty[SocketClient]
    /**
     * This method should be called by the server with the new Client.
     */
    private def clientArrived(client: SocketClient) {
      currentClients += client
      messageArrived(messageFactory(client))
    }
    private var selectingFuture: Future[Unit] = _
    private var stopServer = false

    def start() {
      val selKey = serverChannel.register(selector, SelectionKey.OP_ACCEPT, new SelectionKeyReactor)
      selKey.attachment().asInstanceOf[SelectionKeyReactor].acceptor = key => {
        var it = accept()
        while (it.isDefined) {
          clientArrived(it.get)
          it = accept()
        }
      }
      selectingFuture = flow blocking new SelectionKeyReactorSelector {
        val selector = ServerSocketEndpoint.this.selector
        def mustStop = stopServer
      }.selectionLoop
    }
    def dispose() {
      stopServer = true
      Try(selector.wakeup())
      Try(Await.ready(selectingFuture, 5.seconds))
      Try(serverChannel.close())
      currentClients foreach (_.dispose())
    }
  }

  private[Tcp] class SocketClient(val server: ServerSocketEndpoint, val channel: SocketChannel, val readBuffer: ByteBuffer) extends Disposable {
    val key: SelectionKey = channel.register(server.selector, SelectionKey.OP_READ)
    val multiplexer = new ChannelMultiplexer(key, server.selector, readBuffer, channel.read, channel.write, this, server.log)

    protected[Tcp] def disposeImpl() {
      server.currentClients -= this
      key.cancel()
      try channel.close() catch { case _: Exception => }
      debug(s"$channel disposed")
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
   * Handlers are endpoint factories and the endpoint per se. They are source or
   * responsible endpoints, so there is no need to produce a different endpoint.
   * This class is desgined to work in a subflow for a server endpoint, registring
   * the reader and serializer into the client.
   */
  class Handler[S, P, R] private[Tcp] (val client: SocketClient,
                                       val reader: Consumer[S, P],
                                       val serializer: R => Array[Byte]) extends Source with Responsible with EndpointFactory[Handler[S, P, R]] {
    type Payload = P
    type SupportedResponseTypes = R :: TypeNil
    implicit var flow: uy.com.netlabs.esb.Flow = _
    private var state: S = null.asInstanceOf[S]

    { //do setup
      client.onDispose { _ => flow.dispose }
      client.multiplexer.readers +=
        Consumer.Asynchronous(reader) { p =>
          if (onSource != null) onSource(flow.messageFactory(p))
          else {
            val resp = onRequest(messageFactory(p))
            resp.onComplete {
              case Success(m) => client.multiplexer addPending ByteBuffer.wrap(serializer(m.payload.value.asInstanceOf[R]))
              case Failure(err) => log.error(err, "Failed to respond to client")
            }(flow.workerActorsExecutionContext)
          }
        }
    }

    def start() {}
    def dispose() {}
    def apply(f) = { flow = f; this }

    def canEqual(that) = that == this

    private var onSource: Message[Payload] => Unit = _
    private var onRequest: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]] = _
    //I start the flow as soon as the logic is given to me.
    def onEvent(thunk) { flow.start; onSource = thunk }
    def cancelEventListener(thunk) { throw new UnsupportedOperationException }
    def onRequest(thunk) { flow.start; onRequest = thunk }
    def cancelRequestListener(thunk) { throw new UnsupportedOperationException }

  }
  def closeClient(client: Message[SocketClient]) {
    client.payload.dispose
  }

  object Handler {
    def apply[S, P, R](message: Message[SocketClient],
                       reader: Consumer[S, P]): EndpointFactory[Source { type Payload = Handler[S, P, R]#Payload }] = new Handler(message.payload, reader, null)
    def apply[S, P, R](message: Message[SocketClient],
                       reader: Consumer[S, P],
                       serializer: R => Array[Byte]): EndpointFactory[Responsible {
      type Payload = Handler[S, P, R]#Payload
      type SupportedResponseTypes = Handler[S, P, R]#SupportedResponseTypes
    }] = new Handler(message.payload, reader, serializer)

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

      private val syncConsumer = Consumer.Synchronous(reader, readBuffer)
      protected def retrieveMessage(mf: uy.com.netlabs.esb.MessageFactory): uy.com.netlabs.esb.Message[Payload] = {
        mf(syncConsumer.consume(socket.read))
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