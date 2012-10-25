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

object Sctp {

  object Server {
    case class EF private[Server] (addr: SocketAddress, readBuffer: Int) extends EndpointFactory[ServerChannelEndpoint] {
      def apply(f) = new ServerChannelEndpoint(f, addr, readBuffer)
    }

    def apply(port: Int, readBuffer: Int) = EF(new InetSocketAddress(port), readBuffer)
  }

  class ServerChannelEndpoint private[Sctp] (val flow: Flow, socketAddress: SocketAddress, readBuffer: Int) extends base.BaseSource {
    type Payload = SctpClient
    val serverChannel = {
      val res = SctpServerChannel.open()
      res.configureBlocking(false)
      res.bind(socketAddress)
      res
    }
    def accept() = {
      Option(serverChannel.accept()) map { s =>
        s.configureBlocking(false)
        new SctpClient(this, s, ByteBuffer.allocate(readBuffer))
      }
    }

    lazy val selector = Selector.open()

    @volatile private[Sctp] var currentClients = Set.empty[SctpClient]
    /**
     * This method should be called by the server with the new Client.
     */
    private def clientArrived(client: SctpClient) {
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
        val selector = ServerChannelEndpoint.this.selector
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

  private[Sctp] class SctpClient(val server: ServerChannelEndpoint, val channel: SctpChannel, val readBuffer: ByteBuffer) extends Disposable {
    val key: SelectionKey = channel.register(server.selector, SelectionKey.OP_READ)
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
            val recievedMessageInfo = channel.receive(readBuffer, null, new NotificationHandler[Null] {
              def handleNotification(notif, attachment) = notif match {
                case _: AssociationChangeNotification | _: SendFailedNotification | _: ShutdownNotification =>
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
            val mi = cachedOutputStreams.getOrElse(stream, MessageInfo.createOutgoing(channel.association(), null, stream))
            cachedOutputStreams += stream -> mi
            val wrote = channel.send(buffer, mi)
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
        case ex: Exception => server.log.error(ex, "Error on client " + keyDescr(key))
      }
    }
    key.attach(reactor)

    protected[Sctp] def disposeImpl() {
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
  class Handler[S, P, R] private[Sctp] (val client: SctpClient,
                                        val streamId: Int,
                                        val reader: Consumer[S, P],
                                        val serializer: R => Array[Byte]) extends Source with Responsible with EndpointFactory[Handler[S, P, R]] {
    type Payload = P
    type SupportedResponseTypes = (Int, R) :: TypeNil
    implicit var flow: uy.com.netlabs.esb.Flow = _
    private var state: S = null.asInstanceOf[S]

    { //do setup
      client.onDispose { _ => flow.dispose }
      client.readers += streamId ->
        Consumer.Asynchronous(reader) { p =>
          if (onSource != null) onSource(flow.messageFactory(p))
          else {
            val resp = onRequest(messageFactory(p))
            resp.onComplete {
              case Success(m) =>
                val (stream, r) = m.payload.asInstanceOf[(Int, R)]
                client addPending stream->ByteBuffer.wrap(serializer(r))
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
  def closeClient(client: Message[SctpClient]) {
    client.payload.dispose
  }

  object Handler {
    def apply[S, P, R](message: Message[SctpClient],
                       streamId: Int,
                       reader: Consumer[S, P]): EndpointFactory[Source { type Payload = Handler[S, P, R]#Payload }] = new Handler(message.payload, streamId, reader, null)
    def apply[S, P, R](message: Message[SctpClient],
                       streamId: Int,
                       reader: Consumer[S, P],
                       serializer: R => Array[Byte]): EndpointFactory[Responsible {
      type Payload = Handler[S, P, R]#Payload
      type SupportedResponseTypes = Handler[S, P, R]#SupportedResponseTypes
    }] = new Handler(message.payload, streamId, reader, serializer)

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
    def apply[S, P, R](socket: SctpChannel, reader: Consumer[S, R], writer: P => Array[Byte] = null, readBuffer: Int, ioWorkers: Int = 2) = EF(socket, reader, writer, readBuffer, ioWorkers)

    class SctpClientEndpoint[S, P, R](val flow: Flow, socket: SctpChannel, reader: Consumer[S, R], writer: P => Array[Byte], readBuffer: Int, ioWorkers: Int) extends base.BaseSink with Askable {
      type SupportedTypes = (Int, P) :: TypeNil
      type Response = R
      def start() {

      }
      def dispose {
        scala.util.Try(socket.close())
        ioExecutor.shutdown()
      }

      private val syncConsumer = Consumer.Synchronous(reader, readBuffer)

      val ioExecutor = java.util.concurrent.Executors.newFixedThreadPool(ioWorkers)
      val ioExecutionContext = ExecutionContext.fromExecutor(ioExecutor)
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
        msg map (_ => (syncConsumer.consume(readFunction)))
      }(ioExecutionContext)
    }

  }
}