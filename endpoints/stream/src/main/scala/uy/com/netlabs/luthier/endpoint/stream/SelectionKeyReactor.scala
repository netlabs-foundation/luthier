package uy.com.netlabs.luthier
package endpoint.stream

import java.nio.channels.SelectionKey

/**
 * Attached to a SelectionKey, this class allows registering for
 * the valid actions of a SelectionKey
 */
class SelectionKeyReactor {
  @volatile var beforeProcessingInterests: SelectionKey => Unit = _
  @volatile var reader: SelectionKey => Unit = _
  @volatile var writer: SelectionKey => Unit = _
  @volatile var connector: SelectionKey => Unit = _
  @volatile var acceptor: SelectionKey => Unit = _
  @volatile var afterProcessingInterests: SelectionKey => Unit = _
  
  def react(key: SelectionKey) {
    if (beforeProcessingInterests != null) beforeProcessingInterests(key)
    if (key.isReadable() && reader != null) reader(key)
    if (key.isWritable() && writer != null) writer(key)
    if (key.isConnectable() && connector != null) connector(key)
    if (key.isAcceptable() && acceptor!= null) acceptor(key)
    if (afterProcessingInterests != null) afterProcessingInterests(key)
  }
}