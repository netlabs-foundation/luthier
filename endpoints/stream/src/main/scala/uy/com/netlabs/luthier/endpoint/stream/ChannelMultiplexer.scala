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
package endpoint.stream

import java.nio.channels._
import java.nio.ByteBuffer
import akka.event.LoggingAdapter

/**
 * ChannelMultiplexer allows simultaneously use a "channel" by multiple readers
 * and writers.
 * This helper class is central to the streams package since its used to allow
 * multiple registries of consumers and hence, multiple subflows.
 *
 * @param key The SelectionKey corresponding to the channel. Its interests are
 *            dynamically updated during the life span of the multiplexer.
 * @param selector The Selector to which the key is registered.
 *                 When a write is queued in the multiplexer, it updates the
 *                 SelectionKey's interestsOps to include writing, but if the
 *                 is already in a select, it won't select the updated interest
 *                 until another interest fires up. In order to fix this, a wakeup
 *                 call must be performed on the selector.
 * @param readBuffer Buffer used for reading.
 * @param readOp Typically channel.read
 * @param writeOp Typically channel.write
 * @param channelOwner The instance that encloses this multiplexer and that has
 *                     the reference to the real channel. This instance will be
 *                     disposed when the channel associated with the key is
 *                     detected as closed.
 * @param log Log facility.
 */
class ChannelMultiplexer(val key: SelectionKey,
                         val selector: Selector,
                         val readBuffer: ByteBuffer,
                         val readOp: ByteBuffer => Int,
                         val writeOp: ByteBuffer => Int,
                         val channelOwner: Disposable,
                         val log: LoggingAdapter) {
  var readers = Set[ByteBuffer => Unit]() //set of readers
  private var sendPending = Vector.empty[ByteBuffer]
  def addPending(buff: ByteBuffer) {
    if (key.isValid()) {
      sendPending :+= buff
      key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE)
      selector.wakeup() //so that he register my recent update of interests
    } /*else key is invalid*/
  }
  val reactor = new SelectionKeyReactor
  @volatile var reachedEOF = false
  reactor.afterProcessingInterests = key => {
    try {
      debug("Client operating: " + keyDescr(key))
      var reachedEOF = false
      if (key.isReadable()) {
        readBuffer.clear
        reachedEOF = readOp(readBuffer) == -1
        readBuffer.flip
        debug("  Reading")
        readers foreach (_(readBuffer.duplicate()))
        debug("  Done")
      }
      if (key.isWritable()) {
        var canWrite = true
        debug("Start writing")
        while (canWrite && sendPending.nonEmpty) {
          val (h, t) = (sendPending.head, sendPending.tail)
          val wrote = writeOp(h)
          if (h.hasRemaining()) { //could not write all of the content
            canWrite = false
          } else {
            sendPending = sendPending.tail
          }
        }
      }
      debug("pendings: " + sendPending)
      if (sendPending.isEmpty) key.interestOps(SelectionKey.OP_READ)
      if (reachedEOF || !key.channel.isOpen()) channelOwner.dispose()
    } catch {
      case ex: Exception => log.error(ex, "Error on client " + keyDescr(key))
    }
  }
  key.attach(reactor)
}