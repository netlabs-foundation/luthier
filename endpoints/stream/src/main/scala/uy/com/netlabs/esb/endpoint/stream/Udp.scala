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

    case class EF[S, P, R] private[Channel] (channel: DatagramChannel, reader: Consumer[S, R], writer: P => Array[Byte], readBuffer: Int, ioWorkers: Int) extends EndpointFactory[SocketClientEndpoint[S, P, R]] {
      def apply(f: Flow) = new SocketClientEndpoint[S, P, R](f, channel, reader, writer, readBuffer, ioWorkers)
    }
    def apply[S, P, R](channel: DatagramChannel, reader: Consumer[S, R], writer: P => Array[Byte] = null, readBuffer: Int = 1024 * 5, ioWorkers: Int = 2) = EF(channel, reader, writer, readBuffer, ioWorkers)

    class SocketClientEndpoint[S, P, R](val flow: Flow,
        channel: DatagramChannel,
        reader: Consumer[S, R],
        writer: P => Array[Byte],
        readBuffer: Int,
        ioWorkers: Int) extends base.BaseSource with base.BaseResponsible with base.BasePullEndpoint with base.BaseSink with Askable {
      type Payload = R
      type SupportedTypes = P :: TypeNil
      type Response = R
      
      @volatile
      private[this] var stop = false
      def start() {
        val initializeInboundEndpoint = onEventHandler != null || 
        (onRequestHandler != null && {require(writer != null, "Responsible must define how to serialize responses"); true})
        if (initializeInboundEndpoint) {
          Future {
            while (!stop) {
              val in = newReceviedMessage(syncConsumer.consume(channel.read))
              if (onEventHandler != null) onEventHandler(in)
              else onRequestHandler(in) onComplete {
                case Success(response) => channel.write(ByteBuffer.wrap(writer(response.payload.value.asInstanceOf[P])))
                case Failure(err) => log.error(err, s"Error processing request $in")
              }
            }
          } onFailure {case ex =>
            log.error(ex, s"Stoping flow $flow because of error")
            flow.dispose()
          }
        }
      }
      
      def dispose {
        stop = true
        scala.util.Try(channel.close())
        ioProfile.dispose()
      }

      private val syncConsumer = Consumer.Synchronous(reader, readBuffer)
      protected def retrieveMessage(mf: uy.com.netlabs.esb.MessageFactory): uy.com.netlabs.esb.Message[Payload] = {
        mf(syncConsumer.consume(channel.read))
      }

      val ioProfile = base.IoProfile.threadPool(ioWorkers)
      protected def pushMessage[MT: SupportedType](msg): Unit = {
        channel.write(ByteBuffer.wrap(writer(msg.payload.asInstanceOf[P])))
      }

      def ask[MT: SupportedType](msg, timeOut): Future[Message[Response]] = Future {
        pushMessage(msg)(null) //by pass the evidence..
        retrieveMessage(msg)
      }(ioExecutionContext)
    }

  }
}