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
package endpoint
package amqp

import com.rabbitmq.client._
import uy.com.netlabs.luthier.typelist._
import uy.com.netlabs.luthier.endpoint.base._
import collection.JavaConverters._
import scala.concurrent.{Channel => _, _}, duration._
import scala.util._

case class AmqpDestination(queue: String) extends Destination

trait AmqpEndpoint extends Endpoint {
  val manager: Amqp
  val queue: Queue
  val exchange: Exchange
  def start() {
    if (exchange != Exchange.Default) {
      manager.channel.exchangeDeclare(exchange.name, exchange.tpe.name, exchange.durable, exchange.autoDelete,
                                      exchange.args.asJava)
    }
    manager.channel.queueDeclare(queue.name, queue.durable, queue.exclusive, queue.autoDelete, queue.args.asJava)
  }
  def dispose() {
  }

  protected def mergeMessageWithProps(m: Message[_], props: AMQP.BasicProperties) = {
    val res = props.builder()
    m.replyTo match {
      case d: AmqpDestination if d != null => res.replyTo(d.queue)
      case _ =>
    }
    res.correlationId(m.correlationId)
    res.build()
  }
}

/**
 * Implementation of Source, and Responsible for the AMQP protocol.
 * Note that Responsible behaviour will reply to the replyTo property received by the endpoint.
 */
class AmqpInEndpoint(val flow: Flow, val manager: Amqp, val bindingKeys: Seq[String], val queue: Queue,
                     val exchange: Exchange, ioThreads: Int) extends AmqpEndpoint with BaseSource with BaseResponsible {
  type Payload = Array[Byte]
  type SupportedResponseTypes = Array[Byte] :: TypeNil
  val ioProfile = IoProfile.threadPool(ioThreads, flow.name + "-amqp-ep")
  private lazy val registerInManger: Unit = {
    manager.instantiatedInboundEndpoints :+= this
  }
  override def start() {
    super.start()
    registerInManger //only gets registered the first time in the manager
    if (exchange != Exchange.Default) {
      for (key <- bindingKeys)
        manager.channel.queueBind(queue.name, exchange.name, key)
    }
    val consumer = new DefaultConsumer(manager.channel) {
      def reply(origProps: AMQP.BasicProperties, to: Destination) = (res: Try[Message[OneOf[_, SupportedResponseTypes]]]) => {
        res match {
          case Success(msg@Message(oneOf)) =>
            to match {
              case d: AmqpDestination if d != null =>
                println("Sending message to " + d.queue)
                manager.channel.basicPublish("", d.queue, mergeMessageWithProps(msg, origProps), oneOf.valueAs[Array[Byte]])
              case other => log.error("Failed to reply to client because replyTo destination is invalid. Destination is:" + other)
            }
          case Failure(ex) =>
            log.error(ex, "Failed to reply to client.")
        }
      }
      override def handleDelivery(consumerTag, envelope, props, body) {
        val msg = newReceviedMessage(body)
        msg.correlationId = props.getCorrelationId
        props.getReplyTo match {
          case s if s != null && s.nonEmpty => msg.replyTo = AmqpDestination(s)
          case _ =>
        }
        if (onEventHandler != null) {
          messageArrived(msg)
        } else { //its a responsible
          requestArrived(msg, reply(props, msg.replyTo))
        }
      }
    }
    manager.channel.basicConsume(queue.name, true, consumer)
  }
  override def dispose() {
    super.dispose()
    ioProfile.dispose()
    manager.instantiatedInboundEndpoints = manager.instantiatedInboundEndpoints.filterNot(this.==)
  }
}

class AmqpOutEndpoint(val flow: Flow, val manager: Amqp, val bindingKeys: Seq[String], val queue: Queue,
                      val exchange: Exchange, val messageProperties: AMQP.BasicProperties, ioThreads: Int)
extends AmqpEndpoint with BasePullEndpoint with BaseSink /*with Askable*/ {
  val ioProfile = IoProfile.threadPool(ioThreads, flow.name + "-amqp-ep")
  type Payload = Option[Array[Byte]]
  type Response = Array[Byte]
  type SupportedTypes = Array[Byte] :: TypeNil

  private class OneMessageConsumer(channel: Channel) extends DefaultConsumer(channel) {
    override def handleDelivery(consumerTag, envelope, properties, body) {
      channel.basicCancel(consumerTag) //I will not receive any more messages
    }
  }

  protected def retrieveMessage(mf: MessageFactory): Message[Payload] = {
    val basicGet = manager.channel.basicGet(queue.name, true)
    if (basicGet != null) {
      val properties = basicGet.getProps
      val res = mf(Some(basicGet.getBody))
      res.correlationId = properties.getCorrelationId
      properties.getReplyTo match {
        case s if s != null && s.nonEmpty => res.replyTo = AmqpDestination(s)
        case _ =>
      }
      res
    } else mf(None)
  }
  protected def pushMessage[Payload: SupportedType](msg: Message[Payload]) {
    val props = mergeMessageWithProps(msg, messageProperties)
    for (key <- bindingKeys)
      manager.channel.basicPublish(exchange.name, key, props, msg.payload.asInstanceOf[Array[Byte]])
  }

  /*def askImpl[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration): Future[Message[Response]] = {
   val rndUuid = java.util.UUID.randomUUID.toString
   val resultPromise = Promise[Message[Response]]()
   @volatile var consumerTag: String = null //holder for the consumer that might get instantiated or not.
   Future {
   val props = messageProperties.builder.correlationId(rndUuid).build()
   manager.channel.basicPublish(exchange.name, bindingKeys.head, props, msg.payload.asInstanceOf[Array[Byte]])
   } map {_ =>
   val consumer = new DefaultConsumer(manager.channel) {
   override def handleDelivery(ct, envelope, properties, body) {
   if (properties.getCorrelationId != rndUuid) {
   manager.channel.basicReject(envelope.getDeliveryTag, true)
   return
   }
   manager.channel.basicCancel(consumerTag) //I will not receive any more messages
   consumerTag = null // prevent IOException in the timeout event.
   manager.channel.basicAck(envelope.getDeliveryTag, false)
   val res = msg map (_ => body)
   res.correlationId = properties.getCorrelationId
   properties.getReplyTo match {
   case s if s != null && s.nonEmpty => res.replyTo = AmqpDestination(s)
   case _ =>
   }
   resultPromise.trySuccess(res)
   }
   }
   if (!resultPromise.isCompleted) //it might have timed out in between.
   consumerTag = manager.channel.basicConsume(queue.name, false, consumer)
   }
   flow.scheduleOnce(timeOut) {
   resultPromise.tryFailure(new TimeoutException(s"Timeout $timeOut expired"))
   if (consumerTag != null) try manager.channel.basicCancel(consumerTag)
   catch {case ex: Exception => log.error(ex, "Failed cancelling temporal consumer")}
   }

   resultPromise.future
   }*/

  override def dispose() {
    super.dispose()
    ioProfile.dispose()
  }
}
