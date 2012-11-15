package uy.com.netlabs.esb
package endpoint
package stream

import language._

import java.nio._
import java.nio.channels._

import scala.util._
import scala.concurrent._, duration._

import typelist._

private[stream] trait StreamEndpointComponent {

  protected type ClientType <: Disposable
  protected type ClientConn <: SelectableChannel
  protected type ServerType <: ServerComponent
  protected def newClient(server: ServerType, conn: ClientConn, readBuffer: ByteBuffer): ClientType

  protected trait ServerComponent extends base.BaseSource {
    type Payload = ClientType

    val flow: Flow
    val readBuffer: Int
    val serverTypeProof: this.type <:< ServerType
    val serverChannel: SelectableChannel with InterruptibleChannel
    def serverChannelAccept(): ClientConn

    def accept() = {
      Option(serverChannelAccept()) map { s =>
        s.configureBlocking(false)
        newClient(serverTypeProof(this), s, ByteBuffer.allocate(readBuffer))
      }
    }

    lazy val selector = Selector.open()

    @volatile private[StreamEndpointComponent] var currentClients = Set.empty[ClientType]
    /**
     * This method should be called by the server with the new Client.
     */
    private def clientArrived(client: ClientType) {
      currentClients += client
      messageArrived(newReceviedMessage(client))
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
        val selector = ServerComponent.this.selector
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

  protected trait ClientComponent extends Disposable {
    val server: ServerType
    val conn: ClientConn
    val readBuffer: ByteBuffer
    val key: SelectionKey = conn.register(server.selector, SelectionKey.OP_READ)
    val clientTypeProof: this.type <:< ClientType

    protected[StreamEndpointComponent] def disposeImpl() {
      server.currentClients -= clientTypeProof(this)
      key.cancel()
      try conn.close() catch { case _: Exception => }
      debug(s"$conn disposed")
    }
  }

  protected trait HandlerComponent[S, P, R, SRT <: TypeList] extends Source with Responsible with EndpointFactory[HandlerComponent[S, P, R, SRT]] {
    //abstracts
    val client: ClientType
    val reader: Consumer[S, P]
    def registerReader(reader: ByteBuffer => Unit): Unit
    def processResponseFromRequestedMessage(m: Message[OneOf[_, SupportedResponseTypes]])

    type Payload = P    
    type SupportedResponseTypes = SRT
    implicit var flow: uy.com.netlabs.esb.Flow = _
    private var state: S = null.asInstanceOf[S]

    { //do setup
      client.onDispose { _ => flow.dispose }
      registerReader(
        Consumer.Asynchronous(reader) { t =>
          if (t.isSuccess) {
            val p = t.get
            if (onEventHandler != null) onEventHandler(newReceviedMessage(p))
            else {
              val resp = onRequestHandler(newReceviedMessage(p))
              resp.onComplete {
                case Success(m) => processResponseFromRequestedMessage(m)
                case Failure(err) => log.error(err, "Failed to respond to client")
              }(flow.workerActorsExecutionContext)
            }
          } else log.error(t.failed.get, s"Failure reading from client $client")
        }
      )
    }

    def start() {}
    def dispose() {}
    def apply(f) = { flow = f; this }

    def canEqual(that) = that == this
  }
}