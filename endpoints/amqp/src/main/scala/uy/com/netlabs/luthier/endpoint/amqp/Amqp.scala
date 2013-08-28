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
import com.rabbitmq.client.ConnectionFactory
import java.util.concurrent.ExecutorService

/**
 * This class serves as a factory for AMQP endpoints.
 * Each instance of this class manages the resource associated with an AMQP connection,
 * using them effectively among endpoints instantiated by it.
 */
class Amqp(val connectionFactory: ConnectionFactory,
           connectionThreadPool: ExecutorService = null)(implicit appContext: AppContext) extends Disposable {

  @volatile private[this] var _connection = new ConnectionInstance()
  @volatile private[this] var declaredTempQueues = Seq.empty[String]
  private class ConnectionInstance {
    val conn: Connection = connectionFactory.newConnection(connectionThreadPool)
    val channel = conn.createChannel()
  }
  def connection() = _connection.conn
  def channel = _connection.channel

  protected def disposeImpl {
    _connection.conn.close()
  }

  case class OutEF private[Amqp](bindingKeys: Seq[String], queue: Queue, exchange: Exchange,
                                 messageProperties: AMQP.BasicProperties, ioThreads: Int) extends EndpointFactory[AmqpOutEndpoint] {
    def apply(f) = new AmqpOutEndpoint(f, Amqp.this, bindingKeys, queue, exchange, messageProperties, ioThreads)
  }
  def apply(bindingKey: String, exchange: Exchange = Exchange.Default, queue: Queue = null,
            messageProperties: AMQP.BasicProperties = MessageProperties.BASIC, ioThreads: Int = 4): OutEF =
              apply(Seq(bindingKey), exchange, queue, messageProperties, ioThreads)
  def apply(bindingKeys: Seq[String], exchange: Exchange = Exchange.Default, queue: Queue = null,
            messageProperties: AMQP.BasicProperties = MessageProperties.BASIC, ioThreads: Int = 4): OutEF = {
    require(bindingKeys.length > 0, "You must provide at least 1 binding key.")
    val queueToUse = queue match {
      case null => Queue(bindingKeys.head)
      case q => q
    }
    OutEF(bindingKeys, queueToUse, exchange, messageProperties, ioThreads)
  }
  case class InEF private[Amqp](bindingKeys: Seq[String], queue: Queue,
                                exchange: Exchange, ioThreads: Int) extends EndpointFactory[AmqpInEndpoint] {
    def apply(f) = new AmqpInEndpoint(f, Amqp.this, bindingKeys, queue, exchange, ioThreads)
  }
  def consume(bindingKey: String, queue: Queue = null, exchange: Exchange = Exchange.Default, ioThreads: Int = 4): InEF =
    consume(Seq(bindingKey), queue, exchange, ioThreads)
  def consume(bindingKeys: Seq[String], queue: Queue = null, exchange: Exchange = Exchange.Default, ioThreads: Int = 4): InEF = {
    require(bindingKeys.length > 0, "You must provide at least 1 binding key.")
    val queueToUse = queue match {
      case null => Queue(bindingKeys.head)
      case q => q
    }
    InEF(bindingKeys, queueToUse, exchange, ioThreads)
  }
}

case class Exchange(name: String, tpe: Exchange.Type,
                    durable: Boolean = false, autoDelete: Boolean = false, internal: Boolean = false,
                    args: Map[String, Object] = Map.empty)
object Exchange {
  sealed trait Type {def name: String}
  case object Direct extends Type {val name = "direct"}
  case object Fanout extends Type {val name = "fanout"}
  case object Topic extends Type {val name = "topic"}
  val Default = new Exchange("", Direct)
}

case class Queue(name: String, durable: Boolean = false, exclusive: Boolean = false, autoDelete: Boolean = false,
                 args: Map[String, Object] = Map.empty)
