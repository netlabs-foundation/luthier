package uy.com.netlabs.esb

/**
 * A MessageFactory as it name implies, is the source of messages. If you want to create a message
 * you need one of these.
 */
trait MessageFactory {
  def apply[Payload](p: Payload): Message[Payload]
}
object MessageFactory {
  implicit def factoryFromMessage[P](m: Message[P]) = new MessageFactory {
    def apply[Payload](p: Payload) = m.map(_ => p)
  }
}