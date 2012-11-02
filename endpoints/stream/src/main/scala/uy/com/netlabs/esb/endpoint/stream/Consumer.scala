package uy.com.netlabs.esb.endpoint.stream

import java.nio.ByteBuffer
import java.io.EOFException
import java.nio.channels.ReadableByteChannel

/**
 * Iteratee based concept of Consumer, though this one only feeds on chunks of bytes. No more
 * generalization needed.
 */
trait Consumer[State, Prod] {

  sealed trait ConsumerResult {
    def state: State
  }
  case class ByProduct(content: Seq[Prod], state: State) extends ConsumerResult
  case class NeedMore(state: State) extends ConsumerResult

  def initialState: State
  def consume(input: Input[State]): ConsumerResult
}

object Consumer {

  /**
   * Helper class that implements the state handling of a consumer.
   * It uses the consumer to iterate over the content, saving the
   * produced state in between, and feeding it in each call.
   */
  case class Synchronous[State, Prod](consumer: Consumer[State, Prod], readBuffer: Int) {

    private val inBff = ByteBuffer.allocate(readBuffer)
    private var lastState = consumer.initialState
    private var bufferedInput: Seq[Prod] = Seq.empty
    def consume(readOp: ByteBuffer => Int): Prod = {
      if (bufferedInput.nonEmpty) {
        debug("Buffered input was nonEmtpy: " + bufferedInput)
        val res = bufferedInput.head
        bufferedInput = bufferedInput.tail
        res
      } else {
        def iterate(): Prod = {
          inBff.clear()
          val read = readOp(inBff)
          if (read == -1) throw new EOFException
          inBff.flip
          val r = consumer.consume(Content(lastState, inBff))
          lastState = r.state
          r match {
            case consumer.NeedMore(_) => iterate()
            case consumer.ByProduct(content, state) =>
              bufferedInput = content.tail
              content.head
          }
        }
        iterate()
      }
    }
  }

  def Asynchronous[State, Prod](consumer: Consumer[State, Prod])(handler: Prod => Unit): ByteBuffer => Unit = {
    var state = consumer.initialState
    content => {
      debug("Reading " + content.remaining() + " bytes")
      val res = if (!content.hasRemaining()) consumer.consume(NoMoreInput(state))
      else consumer.consume(Content(state, content))
      debug("Reading result: " + res)
      state = res.state
      res match {
        case consumer.ByProduct(ps, s) =>
          ps foreach handler
        case _ =>
      }
    }
  }
}

sealed trait Input[State] {
  def state: State
}
case class NoMoreInput[State](state: State) extends Input[State]
case class Content[State](state: State, buffer: ByteBuffer) extends Input[State]