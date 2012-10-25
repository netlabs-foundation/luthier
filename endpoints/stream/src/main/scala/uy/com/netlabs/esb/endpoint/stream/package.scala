package uy.com.netlabs.esb.endpoint.stream

import java.nio.channels.SelectionKey

object `package` {
  @inline
  private[stream] final def debug(s: =>Any) {
//    println(s)
  }
  
  private[stream] def keyDescr(k: SelectionKey) = s"Key($k),R:${k.isReadable()},W:${k.isWritable()},A:${k.isAcceptable()}. Chnl: ${k.channel()}"
}