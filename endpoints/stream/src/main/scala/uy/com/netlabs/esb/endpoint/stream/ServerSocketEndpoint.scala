package uy.com.netlabs.esb
package endpoint
package stream

import java.net._
import scala.util._

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

  class Reader[P] private[Streams] (val socket: SocketEndpointFactory,
                                    val reader: P) extends Source with EndpointFactory[Reader[P]] {
    type Payload = P
    implicit def flow: uy.com.netlabs.esb.Flow = ???
    def start() {
      //TODO: somehow register my self to the SocketEndpointFactory
    }
    def dispose() {}
    def apply(flow) = this

    def canEqual(that) = that == this

    private var logic: Message[Payload] => Unit = _
    def onEvent(thunk) { logic = thunk }
    def cancelEventListener(thunk) { throw new UnsupportedOperationException }

  }
  def closeClient()(implicit flow: Flow { val rootEndpoint: Reader[_] }) {
    //TODO: close the client
  }
  def reply(content: Any)(implicit flow: Flow { val rootEndpoint: Reader[_] }) {
    //TODO: send content
  }

  object Reader {
    def apply[P](message: Message[SocketEndpointFactory], reader: P) = new Reader(message.payload, reader)
  }

  val test = new Flows {
    val appContext = AppContext.quick("streams")
    new Flow("ss")(TcpServer(1500)) {
      logic { m =>
        new Flow("clientHandler")(Reader(m, "stringReader")) {
          def logic { m: Message[String] =>
            val msg: String = m.payload
            reply(msg)
          }
        }
        new Flow("clientHandler")(Reader(m, "stringReader")) {
          def logic { m: Message[String] =>
            m.payload match {
              case "exit" => closeClient() 
              case _ => //do nothing
            }
          }
        }
      }
    }
  }
}