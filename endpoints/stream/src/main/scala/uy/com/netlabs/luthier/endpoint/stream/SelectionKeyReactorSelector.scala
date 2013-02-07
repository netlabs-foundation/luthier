package uy.com.netlabs.luthier.endpoint.stream

import java.nio.channels._

trait SelectionKeyReactorSelector {
  val selector: Selector
  def mustStop: Boolean

  def selectionLoop() {
    while (!mustStop) {
      debug(Console.MAGENTA + "Selecting..." + Console.RESET)
      if (selector.select() > 0) {
        debug(Console.YELLOW + s"${selector.selectedKeys().size} keys selected" + Console.RESET)
        val sk = selector.selectedKeys().iterator()
        while (sk.hasNext()) {
          val k = sk.next()
          debug(Console.GREEN + "Processing " + keyDescr(k) + Console.RESET)
          k.attachment().asInstanceOf[SelectionKeyReactor].react(k)
          debug(Console.GREEN + "Done with " + keyDescr(k) + Console.RESET)
          sk.remove()
        }
      }
    }
  }
}