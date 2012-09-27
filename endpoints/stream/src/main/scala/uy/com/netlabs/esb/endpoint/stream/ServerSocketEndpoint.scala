package uy.com.netlabs.esb
package endpoint
package stream

import java.net._
import scala.util._

class ServerSocketEndpoint(val flow: Flow, port: Int) extends base.BaseSource {
  type Payload = SocketEndpointFactory
  
  lazy val serverSocket = {
    val r = new ServerSocket()
    r.setReuseAddress(true)
    r.bind(new InetSocketAddress(port))
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
class SocketEndpointFactory private[stream](s: Socket, deregister: (SocketEndpointFactory) => Unit) {
  
  private[stream] def dispose() {}
}