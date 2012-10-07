package uy.com.netlabs.esb
package endpoint
package stream

import language._

import java.net._
import java.nio.ByteBuffer
import java.nio.channels._
import scala.util._
import scala.concurrent._
import scala.concurrent.util.duration._

import typelist._

/**
 * Trait Streams provides the infrastructure to write an asynchronous IO source endpoint
 * that works using subflows to handle clients. The perfect example for extending this trait
 * is a tcp server, where each accepted client is represented by a socket, and a sequence
 * of subflows are instantiated to handle its request.
 *
 * This trait should also server good for udp and sctp.
 */
trait Streams {

  private def keyDescr(k: SelectionKey) = s"Key($k),R:${k.isReadable()},W:${k.isWritable()},A:${k.isAcceptable()}. Chnl: ${k.channel()}"

  /**
   * Base trait for the server endpoint, the one who accepts the clients. For example,
   * for tcp, its the one with the serversocket.
   *
   * Implementors define the base server socket and the procudure that creates
   * the client's channel, typically by calling accept on the server channel.
   * This class is in charge of using a selector for proper asynchronous IO.
   */
  protected[Streams] trait ServerEndpoint[ClientG <: Client] extends base.BaseSource {
    type Payload = ClientG
    type ServerChannel <: SelectableChannel

    val serverChannel: ServerChannel
    /**
     * SelectionKey that represents the accept operation for the defined serverChannel.
     */
    val clientAcceptOp: Int
    def accept(): Option[ClientG]
    lazy val selector = Selector.open()

    @volatile private[Streams] var currentClients = Set.empty[Client]
    /**
     * This method should be called by the server with the new Client.
     */
    private def clientArrived(client: ClientG) {
      currentClients += client
      messageArrived(messageFactory(client))
    }
    private var selectingFuture: Future[Unit] = _
    private var stopServer = false

    def start() {
      val selKey = serverChannel.register(selector, clientAcceptOp, () => {
        var it = accept()
        while (it.isDefined) {
          clientArrived(it.get)
          it = accept()
        }
      })
      selectingFuture = flow.blocking {
        while (!stopServer) {
          println(Console.MAGENTA + "Selecting..." + Console.RESET)
          if (selector.select() > 0) {
            println(Console.YELLOW + s"${selector.selectedKeys().size} keys selected" + Console.RESET)
            val sk = selector.selectedKeys().iterator()
            while (sk.hasNext()) {
              val k = sk.next()
              println(Console.GREEN + "Processing " + keyDescr(k) + Console.RESET)
              k.attachment().asInstanceOf[Function0[Unit]]()
              println(Console.GREEN + "Done with " + keyDescr(k) + Console.RESET)
              sk.remove()
            }
          }
        }
      }
    }
    def dispose() {
      stopServer = true
      Try(selector.wakeup())
      Try(Await.ready(selectingFuture, 5.seconds))
      Try(serverChannel.close())
      currentClients foreach (_.dispose())
    }
  }

  /**
   * The Client is the payload returned by the ServerEndpoint so that
   * subflows can be created from it.
   *
   * Clients are passed to Handlers, which contain the logic to read and write to it.
   * Also, clients are the wrappers around the selectable channel used for the
   * asynchronous IO.
   */
  trait Client {
    val server: ServerEndpoint[_ >: this.type]
    val channel: SelectableChannel with ByteChannel
    val readBuffer: ByteBuffer

    var readers = Set[ByteBuffer => Unit]() //set of readers
    private var sendPending = Vector.empty[ByteBuffer]
    def addPending(buff: ByteBuffer) {
      if (key.isValid()) {
        sendPending :+= buff
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE)
        server.selector.wakeup() //so that he register my recent update of interests
      } else println("Key is invalid")
    }

    val key: SelectionKey = channel.register(server.selector, SelectionKey.OP_READ)
    key.attach(() => {
      var reachedEOF = false
      if (key.isReadable()) {
        readBuffer.clear
        reachedEOF = channel.read(readBuffer) == -1
        readBuffer.flip
        readers foreach (_(readBuffer.duplicate()))
      }
      if (key.isWritable()) {
        var canWrite = true
        sendPending
        while (canWrite && sendPending.nonEmpty) {
          val (h, t) = (sendPending.head, sendPending.tail)
          val wrote = channel.write(h)
          if (h.hasRemaining()) { //could not write all of the content
            canWrite = false
          } else {
            sendPending = sendPending.tail
          }
        }
      }
      println("pendings: " + sendPending)
      if (sendPending.isEmpty) key.interestOps(SelectionKey.OP_READ)
      if (reachedEOF) dispose()
    })

    private[Streams] def dispose() {
      server.currentClients -= this
      key.cancel()
      disposeClient()
      println(s"$channel disposed")
    }
    protected def disposeClient()
  }

  /**
   * Handlers are endpoint factories and the endpoint per se. They are source or
   * responsible endpoints, so there is no need to produce a different endpoint.
   * This class is desgined to work in a subflow for a server endpoint, registring
   * the reader and serializer into the client.
   */
  class Handler[S, P, R] private[Streams] (val client: Client,
                                           val reader: Consumer[S, P],
                                           val serializer: R => Array[Byte]) extends Source with Responsible with EndpointFactory[Handler[S, P, R]] {
    type Payload = P
    type SupportedResponseTypes = R :: TypeNil
    implicit var flow: uy.com.netlabs.esb.Flow = _
    private var state: S = null.asInstanceOf[S]
    def start() {
      state = reader.initialState
      client.readers += { content =>
        flow.doWork {
          println("Reading " + content.remaining() + " bytes")
          val res = if (!content.hasRemaining()) reader.consume(NoMoreInput(state))
          else reader.consume(Content(state, content))
          println("Reading result: " + res)
          state = res.state
          res match {
            case reader.ByProduct(ps, s) =>
              if (onSource != null)
                ps foreach (p => onSource(messageFactory(p)))
              else
                ps foreach { p =>
                  val resp = onRequest(messageFactory(p))
                  resp.onComplete {
                    case Success(m) => client addPending ByteBuffer.wrap(serializer(m.payload.value.asInstanceOf[R]))
                    case Failure(err) => log.error(err, "Failed to respond to client")
                  }(flow.workerActorsExecutionContext)
                }
            case _ =>
          }
        }.onComplete {case r => println(client + " loop result: " + r)}(flow.workerActorsExecutionContext)
      }
    }
    def dispose() { flow.dispose }
    def apply(f) = { flow = f; this }

    def canEqual(that) = that == this

    private var onSource: Message[Payload] => Unit = _
    private var onRequest: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]] = _
    def onEvent(thunk) { onSource = thunk }
    def cancelEventListener(thunk) { throw new UnsupportedOperationException }
    def onRequest(thunk) { onRequest = thunk }
    def cancelRequestListener(thunk) { throw new UnsupportedOperationException }

  }
  def closeClient(client: Message[Client]) {
    client.payload.dispose
  }

  object Handler {
    def apply[S, P, R](message: Message[Client],
                       reader: Consumer[S, P]): EndpointFactory[Source { type Payload = Handler[S, P, R]#Payload }] = new Handler(message.payload, reader, null)
    def apply[S, P, R](message: Message[Client],
                       reader: Consumer[S, P],
                       serializer: R => Array[Byte]): EndpointFactory[Responsible {
      type Payload = Handler[S, P, R]#Payload
      type SupportedResponseTypes = Handler[S, P, R]#SupportedResponseTypes
    }] = new Handler(message.payload, reader, serializer)

  }

}