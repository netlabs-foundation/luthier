package uy.com.netlabs.esb
package endpoint
package stream

import java.net._
import java.nio.ByteBuffer
import java.nio.channels._

object Tcp extends Streams {
  object Server {
    case class EF private[Server] (addr: SocketAddress, readBuffer: Int) extends EndpointFactory[ServerSocketEndpoint] {
      def apply(f) = new ServerSocketEndpoint(f, addr, readBuffer)
    }

    def apply(port: Int, readBuffer: Int) = EF(new InetSocketAddress(port), readBuffer)
  }
  
  class ServerSocketEndpoint private[Tcp] (val flow: Flow, socketAddress: SocketAddress, readBuffer: Int) extends ServerEndpoint[SocketClient] {
    type ServerChannel = ServerSocketChannel
	val serverChannel = {
	  val res = ServerSocketChannel.open()
	  res.configureBlocking(false)
	  res.bind(socketAddress)
	  res.setOption(StandardSocketOptions.SO_REUSEADDR, true: java.lang.Boolean)
	  res
	}
	val clientAcceptOp = SelectionKey.OP_ACCEPT
	def accept() = {
	  Option(serverChannel.accept()) map {s =>
	    s.configureBlocking(false)
	    new SocketClient(this, s, ByteBuffer.allocate(readBuffer))
	  }
	}
  }
  
  class SocketClient(val server: ServerSocketEndpoint, val channel: SocketChannel, val readBuffer: ByteBuffer) extends Client {
    protected def disposeClient() {
      try channel.close() catch {case _: Exception => }
    }
  }

}