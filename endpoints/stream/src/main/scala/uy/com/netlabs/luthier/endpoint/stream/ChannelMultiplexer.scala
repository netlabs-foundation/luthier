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
        sendPending
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