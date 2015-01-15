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
package endpoint.jms

import shapeless._, typelist._
import javax.jms.{ MessageListener, Message => jmsMessage }
import scala.concurrent.{ Future }
import scala.concurrent.duration._
import scala.util.{ Try, Success, Failure }
import language._

private[jms] trait BaseJmsEndpoint extends endpoint.base.BaseSource with endpoint.base.BasePushable {
  val flow: Flow
  def createDestination(): javax.jms.Destination
  val jmsOperations: JmsOperations
  val messageSelector: String
  val ioThreads: Int
  val autoHandleExceptions: Boolean
  val deliveryMode: Int

  /* Supported types on writing */
  type SupportedType = String :: Array[Byte] :: <::<[java.io.Serializable] :: HNil
  type SupportedResponseType = OneOf[_, SupportedType]
  /* Receiving payload */
  type Payload = SupportedResponseType
  type Response = SupportedResponseType

  val ioProfile = endpoint.base.IoProfile.threadPool(ioThreads, flow.name + "-jms-ep")

  protected def configureSourceOnStart(destination: javax.jms.Destination) {
    jmsOperations.start()
    if (onEventHandler != null) {
      jmsOperations.registerMessageListener(destination, new MessageListener {
          def onMessage(m: jmsMessage) {
            val mappedValue: Message[Payload] = try jmsOperations.jmsMessageToEsbMessage(newReceviedMessage, m)
            catch {
              case ex: Exception =>
                if (autoHandleExceptions) {
                  log.error(ex, "Failed reading JMS Message")
                  return
                }
                newReceviedMessage(new OneOf(ex))
            }
            Try(messageArrived(mappedValue)) match {
              case Failure(ex) => log.error(ex, "Failed deliverying event")
              case _           =>
            }
          }
        }, flow)
      log.info("Configured responsible consumer on destination " + destination)
    }
  }

  def dispose() {
    Try(jmsOperations.dispose())
    ioProfile.dispose
  }

  protected def pushMessage[Payload: TypeIsSupported](msg: Message[Payload]): Unit = {
    jmsOperations.sendMessage(msg, createDestination(), deliveryMode)
  }
}

class JmsQueueEndpoint(val flow: Flow,
                       queue: String,
                       val jmsOperations: JmsOperations,
                       val messageSelector: String,
                       val ioThreads: Int,
                       val autoHandleExceptions: Boolean,
                       val deliveryMode: Int)
extends BaseJmsEndpoint
   with endpoint.base.BaseResponsible
   with Askable {

  def createDestination(): javax.jms.Destination = jmsOperations.createQueue(queue)

  def start() {
    val destination = createDestination()
    configureSourceOnStart(destination)
    if (onRequestHandler != null) {
      jmsOperations.registerMessageListener(destination, new MessageListener {
          def onMessage(m: jmsMessage) {
            val esbMessage: Message[Payload] = try jmsOperations.jmsMessageToEsbMessage(newReceviedMessage, m)
            catch {
              case ex: Exception =>
                if (autoHandleExceptions) {
                  log.error(ex, "Failed reading JMS Message")
                  jmsOperations.sendMessage(ex, m.getJMSReplyTo, deliveryMode)
                  return
                }
                newReceviedMessage(new OneOf(ex))
            }
            requestArrived(esbMessage, sendMessage(_, m.getJMSReplyTo))
          }
        }, flow)
      log.info("Configured responsible consumer on destination " + destination)
    }
  }

  def ask[Payload: TypeIsSupported](msg: Message[Payload], timeOut: FiniteDuration): Future[Message[Response]] = {
    jmsOperations.ask(msg, timeOut, createDestination(), deliveryMode)
  }

  protected def sendMessage(msg: Try[Message[SupportedResponseType]], dest: javax.jms.Destination) {
    try msg match {
      case Success(m) => jmsOperations.sendMessage(m.map(_.unsafeValue), dest, deliveryMode)(null) //force the evidence..
      case Failure(ex) => jmsOperations.sendMessage(ex, dest, deliveryMode)
    }
    catch {
      case ex: Exception => log.error(ex, "Failed delivering request")
    }
  }
}
class JmsTopicEndpoint(val flow: Flow,
                       topic: String,
                       val jmsOperations: JmsOperations,
                       val messageSelector: String,
                       val ioThreads: Int,
                       val autoHandleExceptions: Boolean,
                       val deliveryMode: Int)
extends BaseJmsEndpoint {

  def createDestination(): javax.jms.Destination = jmsOperations.createTopic(topic)

  def start() {
    configureSourceOnStart(createDestination())
  }
}
class JmsDestEndpoint(val flow: Flow,
                      dest: JmsDestination,
                      val jmsOperations: JmsOperations,
                      val messageSelector: String,
                      val ioThreads: Int,
                      val autoHandleExceptions: Boolean,
                      val deliveryMode: Int)
extends BaseJmsEndpoint {

  def createDestination(): javax.jms.Destination = dest.destination

  def start() {
    configureSourceOnStart(createDestination())
  }
}