package uy.com.netlabs.esb

import collection.mutable.Map
import language.implicitConversions
import scala.reflect.ClassTag

/**
 * Dead simple definition of Message that will not last much. Right now is just a prototype
 */
trait Message[Payload] {
  def payload: Payload
  def payload_=(a: Payload)

  def header: Map[String, Map[String, _ <: Any]]

  def replyTo: Destination
  def replyTo_=(replyTo: Destination)

  def correlationId: String
  def correlationId_=(correlationId: String)

  def correlationGroupSize: Int
  def correlationGroupSize_=(groupSize: Int)
  def correlationSequence: Int
  def correlationSequence_=(seq: Int)

  def mapTo[R] = this.asInstanceOf[Message[R]]
  def map[R](f: Payload => R) = Message(f(payload), header, replyTo, correlationId, correlationGroupSize, correlationSequence)
}
trait MessageProxy[Payload] extends Message[Payload] {
  def peer: Message[Payload]
  def payload = peer.payload
  def payload_=(a: Payload) = peer.payload = a

  def header: Map[String, Map[String, _ <: Any]] = peer.header

  def replyTo = peer.replyTo
  def replyTo_=(replyTo: String) = peer.replyTo = replyTo

  def correlationId = peer.correlationId
  def correlationId_=(correlationId: String) = peer.correlationId = correlationId

  def correlationGroupSize = peer.correlationGroupSize
  def correlationGroupSize_=(groupSize: Int) = peer.correlationGroupSize = groupSize
  def correlationSequence = peer.correlationSequence
  def correlationSequence_=(seq: Int) = peer.correlationSequence = seq
}

object Message {
  private[Message] case class MessageImpl[Payload](var payload: Payload,
                                                   val header: Map[String, Map[String, _ <: Any]],
                                                   var replyTo: Destination,
                                                   var correlationId: String,
                                                   var correlationGroupSize: Int,
                                                   var correlationSequence: Int) extends Message[Payload]
  def apply[Payload](payload: Payload,
                     header: Map[String, Map[String, _ <: Any]] = Map.empty,
                     replyTo: Destination = null,
                     correlationId: String = null,
                     correlationGroupSize: Int = 0,
                     correlationSequence: Int = 0): Message[Payload] = MessageImpl(payload, header, replyTo, correlationId, correlationGroupSize, correlationSequence)
}