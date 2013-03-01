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

import java.nio._, charset._
import java.util.concurrent.LinkedBlockingQueue
import scala.util._

object consumers {
  def lines(newline: String = "\n", charset: String = "utf8") = new Consumer[StringBuilder, String] {
    val decoder = java.nio.charset.Charset.forName(charset).newDecoder()
    decoder.onUnmappableCharacter(CodingErrorAction.REPLACE)
    def initialState = new StringBuilder
    def consume(input) = input match {
      case Content(state, buffer) =>
        val chrBuff = ByteBuffer.allocate((buffer.remaining() * decoder.averageCharsPerByte() * 2).ceil.toInt).asCharBuffer()
        val decRes = decoder.decode(buffer, chrBuff, false) // ignore the result, there is nothing smart to do with it
        chrBuff.flip
        chrBuff.rewind

        val patternLength = newline.length
        @annotation.tailrec
        def iter(lines: Vector[String], currLine: StringBuilder): Vector[String] = {
          if (patternLength > chrBuff.remaining()) {
            lines :+ currLine.append(chrBuff).toString // all processed lines plus whatever content in the currLine and the last chars
          } else {
            if (newline.forall(_ == chrBuff.get())) {
              iter(lines :+ (currLine.toString + newline), new StringBuilder)
            } else {
              chrBuff.position(chrBuff.position - patternLength) //undo the forall
              currLine.append(chrBuff.get) //append this char
              iter(lines, currLine)
            }
          }
        }
        iter(Vector.empty, state) match {
          case Vector(head) if head endsWith (newline) => ByProduct(Seq(Success(head)), new StringBuilder)
          case Vector(head) => NeedMore(new StringBuilder(head)) //this head contains the previous accumulated state, plus whatever chars I read now
          case v => if (v.last endsWith newline) ByProduct(v.init.view.map(s => Success(s)), new StringBuilder)
          else ByProduct(v.init.view.map(s => Success(s)), new StringBuilder(v.last))
        }
      case NoMoreInput(state) => ByProduct(Vector(Success(state.toString)), state)
    }
  }

  val chunks = new Consumer[Unit, Array[Byte]] {
    def initialState = ()
    def consume(input) = input match {
      case Content(state, buffer) => //produce a chunk
        val chunk = new Array[Byte](buffer.remaining())
        buffer.get(chunk)
        ByProduct(Vector(Success(chunk)), state)
      case NoMoreInput(state) => NeedMore(state)
    }
  }

  /**
   * Stream implementation of Consnumer that acts as bridge between asynchronous IO and synchronous.
   * To achieve this (and synchronous IO always implies this) a thread must block for input, that
   * thread is obtained from the flow via flow.blocking. Be aware of this when choosing the amount
   * of io workers for the flow.
   */
  def stream[R](flow: Flow, reader: java.io.InputStream => R): Consumer[Unit, R] = new Consumer[Unit, R] {
    /**
     * Helper object that synchronizes the consumer thread with the feeder thread, letting
     * the later one know when the former finished consuming the bytes fed.
     */
    object Feeder extends java.io.InputStream {
      private val semaphore = new java.util.concurrent.Semaphore(0)
      @volatile private var buffer: ByteBuffer = null

      //when compiled with -optimize, everything is inlined, and the closure is eliminated
      @inline final def usingBuffer(f: ByteBuffer => Int) = {
        var b = buffer
        if (b == null) {
          semaphore.acquire()
          b = buffer
        }
        val res = f(b)
        if (buffer.remaining() == 0) {
          buffer = null
          semaphore.release()
        }
        res
      }
      def read() = usingBuffer(_.get())
      override def read(arr) = read(arr, 0, arr.length)
      override def read(arr, from, to) = usingBuffer { b =>
        val read = math.min(to - from, b.remaining())
        b.get(arr, from, read)
        read
      }
      def feed(buffer: ByteBuffer) {
        this.buffer = buffer
        semaphore.release()
        semaphore.acquire()
      }
    }
    @volatile var noMoreContent = false
    @volatile var queue = Vector.empty[Try[R]]

    flow.blocking {
      while (!noMoreContent) {
        queue :+= Try(reader(Feeder))
      }
    }
    def initialState = ()

    def consume(input) = {
      input match {
        case Content(state, buffer) => Feeder.feed(buffer)
        case NoMoreInput(state) => noMoreContent = true
      }
      val res = queue
      queue = Vector.empty
      if (res.nonEmpty) ByProduct(res, input.state)
      else NeedMore(input.state)
    }
  }
}