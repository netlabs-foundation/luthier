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
package endpoint.sharedjms

import uy.com.netlabs.luthier.endpoint

import typelist._
import language._
import akka.actor.Cancellable
import javax.jms.{ MessageListener, Message => jmsMessage, TemporaryQueue }
import scala.util.Try
import scala.collection._
import scala.concurrent._, duration._

object SharedJms {
  /* Supported types on writing */
  type SupportedTypes = String :: Array[Byte] :: java.io.Serializable :: TypeNil
  type SupportedType[Payload] = TypeSupportedByTransport[SupportedTypes, Payload]

  private[SharedJms] trait RequiredJmsFunctions {
    def startEndpoint()
    def stopEndpoint(reg: endpoint.jms.TemporaryQueueRegistration)
    def registerTemporaryQueue(l: MessageListener, quc: TemporaryQueue=>Unit, f: Flow): (endpoint.jms.TemporaryQueueRegistration, TemporaryQueue)
    def push[Payload: SupportedType](msg: Message[Payload]): Future[Unit]
    def jmsMessageToEsbMessage(mf: Any => Message[_], m: jmsMessage): Message[Any]
  }

  private[SharedJms] case class EF(val requiredJmsFunctions: Flow => RequiredJmsFunctions) extends EndpointFactory[SharedJmsEndpoint] {
    def apply(f: Flow) = new SharedJmsEndpoint(f, requiredJmsFunctions(f))
  }
  def apply(epf: EndpointFactory[endpoint.jms.JmsQueueEndpoint])(implicit i1:DummyImplicit) = EF { case f: Flow =>
    val ep: endpoint.jms.JmsQueueEndpoint = epf(f)
    new RequiredJmsFunctions() {
      def startEndpoint() = ep.start()
      def stopEndpoint(reg: endpoint.jms.TemporaryQueueRegistration) { ep.jmsOperations.unregisterTemporaryQueue(reg); ep.dispose() }
      def registerTemporaryQueue(l: MessageListener, quc: TemporaryQueue=>Unit, f: Flow): (endpoint.jms.TemporaryQueueRegistration, TemporaryQueue) =
        ep.jmsOperations.registerTemporaryQueue(l, quc, f)
      def push[Payload: SupportedType](msg: Message[Payload]): Future[Unit] = ep.push(msg)
      def jmsMessageToEsbMessage(mf: Any => Message[_], m: jmsMessage): Message[Any] = ep.jmsOperations.jmsMessageToEsbMessage(mf, m)
    }
  }
  def apply(epf: EndpointFactory[endpoint.jms.JmsTopicEndpoint]) = EF { case f: Flow =>
    val ep: endpoint.jms.JmsTopicEndpoint = epf(f)
    new RequiredJmsFunctions() {
      def startEndpoint() = ep.start()
      def stopEndpoint(reg: endpoint.jms.TemporaryQueueRegistration) { ep.jmsOperations.unregisterTemporaryQueue(reg); ep.dispose() }
      def registerTemporaryQueue(l: MessageListener, quc: TemporaryQueue=>Unit, f: Flow): (endpoint.jms.TemporaryQueueRegistration, TemporaryQueue) =
        ep.jmsOperations.registerTemporaryQueue(l, quc, f)
      def push[Payload: SupportedType](msg: Message[Payload]): Future[Unit] = ep.push(msg)
      def jmsMessageToEsbMessage(mf: Any => Message[_], m: jmsMessage): Message[Any] = ep.jmsOperations.jmsMessageToEsbMessage(mf, m)
    }
  }

  class SharedJmsEndpoint(val flow: Flow,
                          val requiredJmsFunctions: RequiredJmsFunctions) extends Askable {
    type SupportedTypes = SharedJms.SupportedTypes
    type Response = Any

    val correlationIdPrefix = f"${java.lang.System.currentTimeMillis}%x"
    val atomicLong = new java.util.concurrent.atomic.AtomicLong
    var temporaryQueue: TemporaryQueue = null
    var temporaryQueueRegistration: endpoint.jms.TemporaryQueueRegistration = null
    val activeTransactions = new mutable.HashMap[Long, (Promise[Message[Response]], Message[Any], Cancellable)]()
                    with mutable.SynchronizedMap[Long, (Promise[Message[Response]], Message[Any], Cancellable)]

    val messageListener = new MessageListener {
      @Override
      def onMessage(m: jmsMessage) {
        val correlationId: String = try {m.getJMSCorrelationID} catch { case ex: Exception =>
          log.error(ex, "Reading correlationId (response messages must come with utf-8 correlationId)")
          return
        }
        val (id, (promise, origMsg, timeoutTimer)) = try {
          val id: Long = java.lang.Long.parseLong(correlationId split "-" last, 16)
          (id, activeTransactions(id))
        } catch { case e: Exception =>
          log.warning(s"Received message with unknown/invalid/timed-out correlationId=${correlationId}")
          return
        }
        promise tryComplete Try {
          timeoutTimer.cancel()
          activeTransactions -= id
          requiredJmsFunctions.jmsMessageToEsbMessage(p => origMsg.map(_ => p), m)
        }
      }
    }

    def start {
      println("started")
      requiredJmsFunctions.startEndpoint()
      val (reg, tq) = requiredJmsFunctions.registerTemporaryQueue(messageListener, {q => temporaryQueue = q}, flow)
      temporaryQueueRegistration = reg
      temporaryQueue = tq
    }

    def dispose {
      requiredJmsFunctions.stopEndpoint(temporaryQueueRegistration)
    }

    @Override
    def askImpl[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration): Future[Message[Response]] = {
      val promise = Promise[Message[Response]]()
      val id = atomicLong.incrementAndGet
      msg.correlationId = f"${correlationIdPrefix}%s-$id%x"
      msg.replyTo = "jms:queue://" + temporaryQueue.getQueueName

      val timeoutTimer = appContext.actorSystem.scheduler.scheduleOnce(timeOut) {
        promise tryFailure new java.util.concurrent.TimeoutException(s"$timeOut ellapsed")
        activeTransactions -= id
      }(appContext.actorSystem.dispatcher)
      activeTransactions += id -> (promise, msg, timeoutTimer)

      // the last paramater may be anything, it just needs an evidence...
      val resp: Future[Unit] = requiredJmsFunctions.push(msg)(null)

      resp.onFailure({ case t =>
        timeoutTimer.cancel
        activeTransactions -= id
        promise.tryFailure(t)
      })(appContext.actorSystem.dispatcher)

      promise.future
    }
  }
}
