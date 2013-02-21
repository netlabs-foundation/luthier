package uy.com.netlabs.luthier

import collection.mutable.Map
import language.implicitConversions
import scala.reflect.ClassTag
import scala.annotation.unchecked.uncheckedVariance

/**
 * Dead simple definition of Message that will not last much. Right now is just a prototype
 */
trait Message[+Payload] {
  def payload: Payload
  def payload_=(a: Payload @uncheckedVariance) //possibly, a very bad idea. Just possibly...

  def header: Message.header

  def replyTo: Destination
  def replyTo_=(replyTo: Destination)

  def correlationId: String
  def correlationId_=(correlationId: String)

  def correlationGroupSize: Int
  def correlationGroupSize_=(groupSize: Int)
  def correlationSequence: Int
  def correlationSequence_=(seq: Int)

  @inline
  def as[R] = this.asInstanceOf[Message[R]]
  def map[R](f: Payload => R) = Message(f(payload), header.inbound, header.outbound, replyTo, correlationId, correlationGroupSize, correlationSequence)
}
trait MessageProxy[Payload] extends Message[Payload] {
  def peer: Message[Payload]
  def payload = peer.payload
  def payload_=(a: Payload) = peer.payload = a

  // = collection.concurrent.TrieMap.empty
  def header = peer.header

  def replyTo = peer.replyTo
  def replyTo_=(replyTo: Destination) = peer.replyTo = replyTo

  def correlationId = peer.correlationId
  def correlationId_=(correlationId: String) = peer.correlationId = correlationId

  def correlationGroupSize = peer.correlationGroupSize
  def correlationGroupSize_=(groupSize: Int) = peer.correlationGroupSize = groupSize
  def correlationSequence = peer.correlationSequence
  def correlationSequence_=(seq: Int) = peer.correlationSequence = seq
}
/**
 * A root message is the message that started a flow.
 */
trait RootMessage[+FlowType <: Flow] extends Message[FlowType#InboundEndpointTpe#Payload] {
  def flowRun: FlowRun[FlowType]
}

object Message {
  trait header {
    val inbound: collection.concurrent.Map[String, Any]
    val outbound: collection.concurrent.Map[String, Any]

    //swap the inbound headers with the outbound headers
    def swap() {
      val prevIn = inbound.clone()
      inbound.clear
      inbound ++= outbound
      outbound.clear
      outbound ++= prevIn
    }
  }

  private[Message] case class MessageImpl[Payload](var payload: Payload,
                                                   inboundHeader: Map[String, Any],
                                                   outboundHeader: Map[String, Any],
                                                   var replyTo: Destination,
                                                   var correlationId: String,
                                                   var correlationGroupSize: Int,
                                                   var correlationSequence: Int) extends Message[Payload] {
    object header extends Message.header {
      val inbound = inboundHeader match {
        case conc: collection.concurrent.Map[String, Any] => conc
        case map => new collection.concurrent.TrieMap[String, Any]() ++= map
      }
      val outbound = outboundHeader match {
        case conc: collection.concurrent.Map[String, Any] => conc
        case map => new collection.concurrent.TrieMap[String, Any]() ++= map
      }
    }
  }
  private[luthier] def apply[Payload](payload: Payload,
                                  inboundHeader: Map[String, Any] = collection.concurrent.TrieMap.empty,
                                  outboundHeader: Map[String, Any] = collection.concurrent.TrieMap.empty,
                                  replyTo: Destination = null,
                                  correlationId: String = null,
                                  correlationGroupSize: Int = 0,
                                  correlationSequence: Int = 0): Message[Payload] = MessageImpl(payload, inboundHeader, outboundHeader,
    replyTo, correlationId, correlationGroupSize, correlationSequence)
}