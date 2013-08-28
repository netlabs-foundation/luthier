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
import scala.concurrent._, duration._
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
}

/**
 * Implementation of Source and Responsible for the AMQP protocol.
 */
class AmqpInEndpoint(val flow: Flow, val manager: Amqp, val bindingKeys: Seq[String], val queue: Queue,
                     val exchange: Exchange, ioThreads: Int) extends AmqpEndpoint with BaseSource with BaseResponsible {
  type Payload = Array[Byte]
  type SupportedResponseTypes = Array[Byte] :: TypeNil
  val ioProfile = IoProfile.threadPool(ioThreads, flow.name + "-amqp-ep")
  override def start() {
    super.start()
    if (exchange != Exchange.Default) {
      for (key <- bindingKeys)
        manager.channel.queueBind(queue.name, exchange.name, key)
    }
    val consumer = new DefaultConsumer(manager.channel) {
      def reply(to: Destination) = (res: Try[Message[OneOf[_, SupportedResponseTypes]]]) => {
        res match {
          case Success(msg@Message(oneOf)) =>
            to match {
              case d: AmqpDestination if d != null => manager.channel.basicPublish(d.queue, "", null, oneOf.valueAs[Array[Byte]])
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
          requestArrived(msg, reply(msg.replyTo))
        }
      }
    }
    manager.channel.basicConsume(queue.name, true, consumer)
  }
  override def dispose() {
    super.dispose()
    ioProfile.dispose()
  }
}

class AmqpOutEndpoint(val flow: Flow, val manager: Amqp, val bindingKeys: Seq[String], val queue: Queue,
                      val exchange: Exchange, val messageProperties: AMQP.BasicProperties, ioThreads: Int)
extends AmqpEndpoint with BasePullEndpoint with BaseSink with Askable {
  val ioProfile = IoProfile.threadPool(ioThreads, flow.name + "-amqp-ep")
  type Payload = Array[Byte]
  type SupportedResponseTypes = Array[Byte] :: TypeNil

  protected def retrieveMessage(mf: MessageFactory): Message[Payload] = {
    val resp = manager.channel.basicGet(queue.name, true)
    val res = mf(resp.getBody)
    res.correlationId = resp.getProps.getCorrelationId
    resp.getProps.getReplyTo match {
      case s if s != null && s.nonEmpty => res.replyTo = AmqpDestination(s)
      case _ =>
    }
    res
  }
  protected def pushMessage[Payload: SupportedType](msg: Message[Payload]) {
    for (key <- bindingKeys)
      manager.channel.basicPublish(exchange.name, key, messageProperties, msg.payload.asInstanceOf[Array[Byte]])
  }

  def askImpl[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration): Future[Message[Response]] = {
    ???
  }

  override def dispose() {
    super.dispose()
    ioProfile.dispose()
  }
}
