package uy.com.netlabs.esb
package endpoint
package stream

import java.nio._, charset._
import java.util.concurrent.LinkedBlockingQueue

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
          case v @ Vector(head) if head endsWith (newline) => ByProduct(v, new StringBuilder)
          case v @ Vector(head) => NeedMore(new StringBuilder(head)) //this head contains the previous accumulated state, plus whatever chars I read now
          case v => if (v.last endsWith newline) ByProduct(v, new StringBuilder)
          else ByProduct(v.init, new StringBuilder(v.last))
        }
      case NoMoreInput(state) => ByProduct(Vector(state.toString), state)
    }
  }

  val chunks = new Consumer[Unit, Array[Byte]] {
    def initialState = ()
    def consume(input) = input match {
      case Content(state, buffer) => //produce a chunk
        val chunk = new Array[Byte](buffer.remaining())
        buffer.get(chunk)
        ByProduct(Vector(chunk), state)
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
    val (pipe, streamer) = {
      val out = new java.io.PipedOutputStream
      val in = new java.io.PipedInputStream(out)
      in -> java.nio.channels.Channels.newChannel(out)
    }
    @volatile var noMoreContent = false
    val queue = new LinkedBlockingQueue[R]
    
    flow.blocking {
      while (!noMoreContent) {
        queue put reader(pipe)
      }
    }
    def initialState = ()

    def consume(input) = {
      def tryToFetchElements(): ConsumerResult = {
        var r: R = null.asInstanceOf[R]
        val res = new collection.mutable.ArrayBuffer[R]
        while ({r = queue.poll(); r != null}) res += r
        //if there is nothing just now, give a reasonable amount of time to finish processing and try to fetch
        if ({r = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS); r != null}) {
          res += r
        }
        if (res.nonEmpty) ByProduct(res, input.state)
        else NeedMore(input.state)
      }
      input match {
        case Content(state, buffer) => streamer.write(buffer)
        case NoMoreInput(state) => noMoreContent = true
      }
      tryToFetchElements()
    }
  }
}