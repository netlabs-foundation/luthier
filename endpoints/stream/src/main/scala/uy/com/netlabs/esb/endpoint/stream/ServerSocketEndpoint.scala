package uy.com.netlabs.esb
package endpoint
package stream

import language._

import java.net._
import scala.util._
import scala.concurrent._

import typelist._

/*
 * Syntax ideal
 * 
 * new Flow("someFlow")(TcpServer(port, address = ...)) {
 *   def logic {m =>
 *     new Flow("handle incoming messages")(Reader(m, lines)) {
 *       def logic {m =>
 *         m.payload {
 *           case "exit" => closeClient()
 *           case msg => reply()
 *         }
 *       }
 *     }
 *   }
 * }
 */

trait Streams {

  object TcpServer {
    case class EF private[TcpServer] (addr: SocketAddress) extends EndpointFactory[ServerSocketEndpoint] {
      def apply(f) = new ServerSocketEndpoint(f, addr)
    }

    def apply(port: Int) = EF(new InetSocketAddress(port))
  }

  class ServerSocketEndpoint private[Streams] (val flow: Flow, socketAddress: SocketAddress) extends base.BaseSource {
    type Payload = SocketEndpointFactory

    lazy val serverSocket = {
      val r = new ServerSocket()
      r.setReuseAddress(true)
      r.bind(socketAddress)
      r
    }
    @volatile private var currentClients = Set.empty[SocketEndpointFactory]
    object Acceptor extends Thread(flow.name + " - ServerSocket") {
      @volatile var shouldStop = false
      override def run {
        while (!shouldStop) try {
          val s = serverSocket.accept
          val client = new SocketEndpointFactory(s, currentClients -= _)
          currentClients += client
          messageArrived(messageFactory(client))
        } catch {
          case ex: Exception => log.error(ex, "Exception accepting socket")
        }
      }
    }
    def start() {
      serverSocket
    }
    def dispose() {
      Acceptor.shouldStop = true
      serverSocket.close()
      currentClients foreach (_.dispose())
    }
  }

  class SocketEndpointFactory private[stream] (val s: Socket, val deregister: (SocketEndpointFactory) => Unit) {

    private[stream] def dispose() {}
  }

  class Handler[P, R] private[Streams] (val socket: SocketEndpointFactory,
                                        val reader: Consumer[_, P],
                                        val serializer: R => Array[Byte]) extends Source with Responsible with EndpointFactory[Handler[P, R]] {
    type Payload = P
    type SupportedResponseTypes = R :: TypeNil
    implicit def flow: uy.com.netlabs.esb.Flow = ???
    def start() {
      //TODO: somehow register my self to the SocketEndpointFactory
    }
    def dispose() {}
    def apply(flow) = this

    def canEqual(that) = that == this

    private var onSource: Message[Payload] => Unit = _
    private var onRequest: Message[Payload] => Future[Message[OneOf[_, SupportedResponseTypes]]] = _
    def onEvent(thunk) { onSource = thunk }
    def cancelEventListener(thunk) { throw new UnsupportedOperationException }
    def onRequest(thunk) { onRequest = thunk }
    def cancelRequestListener(thunk) { throw new UnsupportedOperationException }

  }
  def closeClient(client: Message[SocketEndpointFactory]) {
    //TODO: close the client
  }

  object Handler {
    def apply[P, R](message: Message[SocketEndpointFactory],
        reader: Consumer[_, P]): EndpointFactory[Source {type Payload = Handler[P,R]#Payload}] = new Handler(message.payload, reader, null)
    def apply[P, R](message: Message[SocketEndpointFactory],
        reader: Consumer[_, P],
        serializer: R => Array[Byte]): EndpointFactory[Responsible {type Payload = Handler[P,R]#Payload
          type SupportedResponseTypes = Handler[P,R]#SupportedResponseTypes}] = new Handler(message.payload, reader, serializer)
    
  }

  val test = new Flows {
    val appContext = AppContext.quick("streams")
    new Flow("ss")(TcpServer(1500)) {
      logic { client =>
        new Flow("clientHandler")(Handler(client, consumers.lines(), serializers.string)) {
          logic { m: Message[String] =>
            val msg: String = m.payload
            m
          }
        }
        new Flow("clientHandler2")(Handler(client, consumers.lines())) {
          logic { m: Message[String] =>
            m.payload match {
              case "exit" => closeClient(client)
              case _ => //do nothing
            }
          }
        }
      }
    }
  }
}