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
package uy.com.netlabs.luthier.endpoint.stream

import java.nio.ByteBuffer
import java.io.EOFException
import java.nio.channels.ReadableByteChannel
import scala.util.Try

/**
 * Iteratee based concept of Consumer, though this one only feeds on chunks of bytes. No more
 * generalization needed.
 */
trait Consumer[State, Prod] {

  sealed trait ConsumerResult {
    def state: State
  }
  case class ByProduct(content: Seq[Try[Prod]], state: State) extends ConsumerResult
  case class NeedMore(state: State) extends ConsumerResult

  def initialState: State
  def consume(input: Input[State]): ConsumerResult
}

object Consumer {

  /**
   * Helper class that implements the state handling of a consumer.
   * It uses the consumer to iterate over the content, saving the
   * produced state in between, and feeding it in each call.
   *
   * Given that the consume method is designed to return one value,
   * if more was available when called, the rest of it is buffered until consumed.
   * If there is data buffered, no read operation is performed.
   * Also note that is buffering is unbounded and it may cause EOM the consumer
   * is slower than the producer.
   */
  case class Synchronous[State, Prod](consumer: Consumer[State, Prod], readBuffer: Int) {

    private val inBff = ByteBuffer.allocate(readBuffer)
    private var lastState = consumer.initialState
    private var bufferedInput: Seq[Try[Prod]] = Seq.empty
    def consume(readOp: ByteBuffer => Int): Try[Prod] = {
      if (bufferedInput.nonEmpty) {
        debug("Buffered input was nonEmtpy: " + bufferedInput)
        val res = bufferedInput.head
        bufferedInput = bufferedInput.tail
        res
      } else {
        def iterate(): Try[Prod] = {
          inBff.clear()
          val read = readOp(inBff)
          if (read == -1) throw new EOFException
          inBff.flip
          val r = synchronized {
            val r = consumer.consume(Content(lastState, inBff))
            lastState = r.state
            r
          }
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
    @inline
    final def withLastState[R](f: State => (State, R)): R = synchronized {
      val res = f(lastState)
      lastState = res._1
      res._2
    }
  }

  /**
   * Produced a function that works as a reader using the given consumer.
   * Such function is intended to be stepped as content arrive from an
   * asyncrhonous channel. The `handler` param received will be called
   * every time that output is ready.
   */
  def Asynchronous[State, Prod](consumer: Consumer[State, Prod])(handler: Try[Prod] => Unit): ByteBuffer => Unit = {
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