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

object Udp {
  /**
   * Simple Udp client.
   * Given the nature of a single datagram channels, there is no need for selectors. An simple implementation
   * of the base endpoints will suffice.
   */
  object Channel {
    import typelist._
    import scala.concurrent._

    case class EF[S, P, R] private[Channel] (channel: DatagramChannel,
                                             reader: Consumer[S, R],
                                             writer: P => Array[Byte],
                                             onReadWaitAction: ReadWaitAction[S, R],
                                             readBuffer: Int,
                                             ioWorkers: Int) extends EndpointFactory[SocketClientEndpoint[S, P, R]] {
      def apply(f: Flow) = new SocketClientEndpoint[S, P, R](f, channel, reader, writer, onReadWaitAction, readBuffer, ioWorkers)
    }
    def apply[S, P, R](channel: DatagramChannel, reader: Consumer[S, R], writer: P => Array[Byte] = null,
                       onReadWaitAction: ReadWaitAction[S, R] = ReadWaitAction.DoNothing, readBuffer: Int = 1024 * 5, ioWorkers: Int = 2) =
                         EF(channel, reader, writer, onReadWaitAction, readBuffer, ioWorkers)

    class SocketClientEndpoint[S, P, R](val flow: Flow,
                                        val channel: DatagramChannel,
                                        val reader: Consumer[S, R],
                                        val writer: P => Array[Byte],
                                        val onReadWaitAction: ReadWaitAction[S, R],
                                        val readBuffer: Int,
                                        val ioWorkers: Int) extends IO.IOChannelEndpoint with base.BasePullable {

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