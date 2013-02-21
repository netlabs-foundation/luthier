package uy.com.netlabs.luthier
package endpoint
package stream

import language.implicitConversions
import scala.util._, scala.concurrent._, duration._
import typelist._

import java.nio._, channels._


object IO {
  trait InputChannelEndpoint extends base.BaseSource {
    type ConsumerState
    type ConsumerProd
    val reader: Consumer[ConsumerState, ConsumerProd]
    type Payload = ConsumerProd

    val channel: java.io.Closeable
    val readBuffer: Int
    val onReadWaitAction: ReadWaitAction[ConsumerState, ConsumerProd]
    protected val readBytes: ByteBuffer => Int

    @volatile
    private var stop = false
    @volatile
    private var lastScheduledOnWaitAction: akka.actor.Cancellable = _
    protected val syncConsumer = Consumer.Synchronous(reader, readBuffer)
    def start() = {
      def consumerHandler(updateWaitAction: Boolean): Try[ConsumerProd] => Unit = read => {
        if (updateWaitAction) updateOnWaitAction() //received input asynchronously, so update the on wait action
        if (read.isSuccess) {
          val in = newReceviedMessage(read.get)
          handleReadMessage(in)
        } else log.error(read.failed.get, s"Failure reading from socket $channel")
      }
      def updateOnWaitAction() {
        if (lastScheduledOnWaitAction != null) {
          lastScheduledOnWaitAction.cancel()
        }
        lastScheduledOnWaitAction = flow.scheduleOnce(onReadWaitAction.maxWaitTime.millis) {
          val res = syncConsumer.withLastState { prev =>
            val r = onReadWaitAction(prev, reader)
            r.state -> r
          }
          res match {
            case byprod: reader.ByProduct => byprod.content foreach consumerHandler(false)
            case _ =>
          }
          //There is only one onWaitAction executed per timeout, that's why we do not self schedule.
          lastScheduledOnWaitAction = null
        }
      }

      if (shouldInitialize) {
        updateOnWaitAction()
        flow.blocking {
          try {
            val handler = consumerHandler(true)
            while (!stop) {
              handler(syncConsumer.consume(readBytes))
            }
          } catch {
            case ex: Exception =>
              log.error(ex, s"Stoping flow $flow because of error")
              flow.dispose()
          }
        }
      }
    }
    protected def shouldInitialize: Boolean = onEventHandler != null
    protected def handleReadMessage(m: Message[Payload]) {
      onEventHandler(m)
    }

    def dispose {
      stop = true
      if (lastScheduledOnWaitAction != null) lastScheduledOnWaitAction.cancel()
      scala.util.Try(channel.close())
    }
  }
  trait OutputChannelEndpoint extends base.BaseSink {
    type OutPayload
    type SupportedTypes = OutPayload :: TypeNil

    val ioWorkers: Int
    val ioProfile = base.IoProfile.threadPool(ioWorkers)

    def send(message: Message[OutPayload])
    protected def pushMessage[MT: SupportedType](msg): Unit = {
      send(msg.as[OutPayload]) //bypass creation of a contained, since the SupportedType implicit gives us that guarantee already
    }

    def dispose {
      ioProfile.dispose()
    }
  }

  trait IOChannelEndpoint extends InputChannelEndpoint with OutputChannelEndpoint with base.BaseResponsible with Askable {
    type Response = Payload
    type SupportedResponseTypes = SupportedTypes

    override protected def shouldInitialize: Boolean = onEventHandler != null || onRequestHandler != null
    override protected def handleReadMessage(m: Message[Payload]) {
      if (onEventHandler != null) onEventHandler(m)
      else onRequestHandler(m) onComplete {
        case Success(response) => send(response.map(_.value).as)
        case Failure(err) => log.error(err, s"Error processing request $m")
      }
    }
    def ask[MT](msg: Message[MT], timeOut: FiniteDuration)(implicit ev: SupportedType[MT]): Future[Message[Response]] = Future {
      pushMessage(msg)(ev)
      msg map (_ => syncConsumer.consume(readBytes).get)
    }(ioExecutionContext)

    override def dispose {
      super[InputChannelEndpoint].dispose()
      super[OutputChannelEndpoint].dispose()
    }
  }

  trait InputStreamEndpoint extends InputChannelEndpoint {
    val input: ReadableByteChannel
    val channel = input
    protected val readBytes: ByteBuffer => Int = channel.read
  }
  trait OutputStreamEndpoint extends OutputChannelEndpoint {
    val output: WritableByteChannel
    val outChannel = output
    def serialize(p: OutPayload): ByteBuffer
    def send(message: Message[OutPayload]) {
      outChannel.write(serialize(message.payload))
    }
  }
  class InputStreamEndpointImpl[S, P] private[IO] (val flow: Flow,
                                                   val input: ReadableByteChannel,
                                                   val reader: Consumer[S, P],
                                                   val onReadWaitAction: ReadWaitAction[S, P],
                                                   val readBuffer: Int,
                                                   val ioWorkers: Int) extends InputStreamEndpoint with base.BasePullEndpoint {
    type ConsumerState = S
    type ConsumerProd = P

    val ioProfile = base.IoProfile.threadPool(ioWorkers)
    protected def retrieveMessage(mf) = mf(syncConsumer.consume(channel.read).get)
  }

  case class ISEF[S, P] private[IO] (input: ReadableByteChannel,
                                     reader: Consumer[S, P],
                                     onReadWaitAction: ReadWaitAction[S, P],
                                     readBuffer: Int,
                                     ioWorkers: Int) extends EndpointFactory[InputStreamEndpointImpl[S, P]] {
    def apply(f: Flow) = new InputStreamEndpointImpl(f, input, reader, onReadWaitAction, readBuffer, ioWorkers)
  }
  def fromInChannel[S, P](input: ReadableByteChannel,
                          reader: Consumer[S, P],
                          onReadWaitAction: ReadWaitAction[S, P] = ReadWaitAction.DoNothing,
                          readBuffer: Int = 1024 * 5,
                          ioWorkers: Int = 4) = ISEF(input, reader, onReadWaitAction, readBuffer, ioWorkers)
  def fromInputStream[S, P](input: java.io.InputStream,
                            reader: Consumer[S, P],
                            onReadWaitAction: ReadWaitAction[S, P] = ReadWaitAction.DoNothing,
                            readBuffer: Int = 1024 * 5,
                            ioWorkers: Int = 4) = ISEF(Channels newChannel input, reader, onReadWaitAction, readBuffer, ioWorkers)

  class OutputStreamEndpointImpl[O] private[IO] (val flow: Flow,
                                                 val output: WritableByteChannel,
                                                 val serializer: O => ByteBuffer,
                                                 val ioWorkers: Int) extends OutputStreamEndpoint {
    type OutPayload = O
    def serialize(p) = serializer(p)
    def start {}
  }

  case class OSEF[O] private[IO] (output: WritableByteChannel,
                                  serializer: O => ByteBuffer,
                                  ioWorkers: Int) extends EndpointFactory[OutputStreamEndpointImpl[O]] {
    def apply(f: Flow) = new OutputStreamEndpointImpl(f, output, serializer, ioWorkers)
  }
  def fromOutChannel[O](output: WritableByteChannel,
                        serializer: O => ByteBuffer,
                        ioWorkers: Int = 4) = OSEF(output, serializer, ioWorkers)
  def fromOutputStream[O](output: java.io.OutputStream,
                          serializer: O => ByteBuffer,
                          ioWorkers: Int = 4) = OSEF(Channels newChannel output, serializer, ioWorkers)

  class IOEndpointImpl[S, P, O] private[IO] (val flow: Flow,
                                             val input: ReadableByteChannel,
                                             val reader: Consumer[S, P],
                                             val onReadWaitAction: ReadWaitAction[S, P],
                                             val readBuffer: Int,
                                             val output: WritableByteChannel,
                                             val serializer: O => ByteBuffer,
                                             val ioWorkers: Int) extends IOChannelEndpoint with InputStreamEndpoint with OutputStreamEndpoint with base.BasePullEndpoint {
    type ConsumerState = S
    type ConsumerProd = P
    type OutPayload = O

    protected def retrieveMessage(mf) = mf(syncConsumer.consume(channel.read).get)
    def serialize(p: OutPayload) = serializer(p)
  }
  case class IOEF[S, P, O] private[IO] (input: ReadableByteChannel,
                                        reader: Consumer[S, P],
                                        onReadWaitAction: ReadWaitAction[S, P],
                                        readBuffer: Int,
                                        output: WritableByteChannel,
                                        serializer: O => ByteBuffer,
                                        ioWorkers: Int) extends EndpointFactory[IOEndpointImpl[S, P, O]] {
    def apply(f) = new IOEndpointImpl(f, input, reader, onReadWaitAction, readBuffer, output, serializer, ioWorkers)
  }

  case class Pipe(in: ReadableByteChannel, out: WritableByteChannel)
  object Pipe {
    implicit def toPipe(channel: ReadableByteChannel with WritableByteChannel) = new Pipe(channel, channel)
    implicit def channelsPair(in: ReadableByteChannel, out: WritableByteChannel) = new Pipe(in, out)
  }

  def apply[S, P, O](pipe: Pipe,
                     reader: Consumer[S, P],
                     serializer: O => ByteBuffer,
                     onReadWaitAction: ReadWaitAction[S, P] = ReadWaitAction.DoNothing,
                     readBuffer: Int = 1024 * 5,
                     ioWorkers: Int = 4) = IOEF(pipe.in, reader, onReadWaitAction, readBuffer, pipe.out, serializer, ioWorkers)

}
