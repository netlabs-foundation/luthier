package uy.com.netlabs.esb

import scala.language.implicitConversions
import scala.annotation.implicitNotFound

/**
 * A MessageFactory as it name implies, is the source of messages. If you want to create a message
 * you need one of these.
 */
@implicitNotFound("A Message factory is needed by this functionality, note that a message serves well for such purpose.\nIf you would like to use always the same message you could mark it as implicit.")
trait MessageFactory {
  def apply[Payload](p: Payload): Message[Payload]
}
object MessageFactory {
  implicit def factoryFromMessage[P](m: Message[P]) = new MessageFactory {
    def apply[Payload](p: Payload) = m.map(_ => p)
  }
  implicit def factoryFromImplicitMessage[P](implicit m: Message[P]) = new MessageFactory {
	  def apply[Payload](p: Payload) = m.map(_ => p)
  }
}