/**
 * Copyright (c) 2013, Netlabs S.R.L. <contacto@netlabs.com.uy>
 * All rights reserved.
 *
 * This software is dual licensed as GPLv2: http://gnu.org/licenses/gpl-2.0.html,
 * and as the following 3-clause BSD license. In other words you must comply to
 * either of them to enjoy the permissions they grant over this software.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name "netlabs" nor the names of its contributors may be
 *       used to endorse or promote products derived from this software
 *       without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL NETLABS S.R.L.  BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uy.com.netlabs.luthier
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

object Tcp extends StreamEndpointServerComponent {

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
    lazy val serverChannel = {
      val res = ServerSocketChannel.open()
      res.configureBlocking(false)
      res.bind(socketAddress)
      res.setOption(StandardSocketOptions.SO_REUSEADDR, true: java.lang.Boolean)
      res
    }
    def serverChannelAccept() = serverChannel.accept()
  }

  private[Tcp] class SocketClient(val server: ServerSocketEndpoint, val conn: SocketChannel, val readBuffer: ByteBuffer) extends ClientComponent {
    val multiplexer = new ChannelMultiplexer(key, server.selector, readBuffer, conn.read, conn.write, this, server.log)
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
                                       val serializer: R => Array[Byte],
                                       val onReadWaitAction: ReadWaitAction[S, P]) extends HandlerComponent[S, P, R, R :: TypeNil] {
    def registerReader(reader) = client.multiplexer.readers += reader
    def processResponseFromRequestedMessage(m) = client.multiplexer addPending ByteBuffer.wrap(serializer(m.payload.value.asInstanceOf[R]))

  }
  def closeClient(client: Message[SocketClient]) {
    client.payload.dispose
  }
  def writeToClient(client: Message[SocketClient], arr: Array[Byte]) {
    writeToClient(client, ByteBuffer.wrap(arr))
  }
  def writeToClient(client: Message[SocketClient], arr: ByteBuffer) {
    client.payload.multiplexer addPending arr
  }

  object Handler {
    def apply[S, P, R](message: Message[SocketClient],
                       reader: Consumer[S, P],
                       onReadWaitAction: ReadWaitAction[S, P] = ReadWaitAction.DoNothing) = new Handler(message.payload, reader, null, onReadWaitAction).OW
    def apply[S, P, R](message: Message[SocketClient],
                       reader: Consumer[S, P],
                       serializer: R => Array[Byte],
                       onReadWaitAction: ReadWaitAction[S, P] = ReadWaitAction.DoNothing) = new Handler(message.payload, reader, serializer, onReadWaitAction).RR
  }

  /**
   * Simple Tcp client.
   * Given the nature of a single socket, there is no need selectors. An simple implementation
   * of the base endpoints will suffice.
   */
  object Client {
    import typelist._
    import scala.concurrent._

    case class EF[S, P, R] private[Client] (socket: SocketChannel, reader: Consumer[S, R], writer: P => Array[Byte],
        onReadWaitAction: ReadWaitAction[S, R], readBuffer: Int, ioWorkers: Int) extends EndpointFactory[SocketClientEndpoint[S, P, R]] {
      def apply(f: Flow) = new SocketClientEndpoint[S, P, R](f, socket, reader, writer, onReadWaitAction, readBuffer, ioWorkers)
    }
    def apply[S, P, R](socket: SocketChannel, reader: Consumer[S, R], writer: P => Array[Byte] = null,
        onReadWaitAction: ReadWaitAction[S, R] = ReadWaitAction.DoNothing, readBuffer: Int = 1024 * 5, ioWorkers: Int = 2) =
          EF(socket, reader, writer, onReadWaitAction, readBuffer, ioWorkers)

    class SocketClientEndpoint[S, P, R](val flow: Flow,
                                        val channel: SocketChannel,
                                        val reader: Consumer[S, R],
                                        val writer: P => Array[Byte],
                                        val onReadWaitAction: ReadWaitAction[S, R],
                                        val readBuffer: Int,
                                        val ioWorkers: Int) extends IO.IOChannelEndpoint with base.BasePullEndpoint {

      type ConsumerState = S
      type ConsumerProd = R
      type OutPayload = P
      def send(message) {
        channel.write(ByteBuffer.wrap(writer(message.payload)))
      }
      protected val readBytes: ByteBuffer => Int = channel.read(_: ByteBuffer)

      protected def retrieveMessage(mf: uy.com.netlabs.luthier.MessageFactory): uy.com.netlabs.luthier.Message[Payload] = {
        mf(syncConsumer.consume(channel.read).get)
      }
    }
  }
}