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
                                        val ioWorkers: Int) extends IO.IOChannelEndpoint with base.BasePullEndpoint {

      type ConsumerState = S
      type ConsumerProd = R
      type OutPayload = P
      def send(message) {
        channel.write(ByteBuffer.wrap(writer(message.payload)))
      }
      protected val readBytes: ByteBuffer => Int = channel.read(_: ByteBuffer)

      protected def retrieveMessage(mf: uy.com.netlabs.esb.MessageFactory): uy.com.netlabs.esb.Message[Payload] = {
        mf(syncConsumer.consume(channel.read).get)
      }
    }
  }
}