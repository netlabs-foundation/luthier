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
package jms

import typelist._
import akka.event.LoggingAdapter
import javax.jms.{ ConnectionFactory, Connection, Queue, MessageListener,
                  ExceptionListener, Session, Message => jmsMessage,
                  TextMessage, ObjectMessage, BytesMessage, IllegalStateException,
                  DeliveryMode, TemporaryQueue}
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import scala.util.{ Try, Success, Failure }
import language._

class Jms(connectionFactory: ConnectionFactory) {

//  private[this] var jmsOperationsPerConnection = Map.empty[Connection, JmsOperations]
  private[this] var _jmsOperations: JmsOperations = _
  private[this] def jmsOperations(ap: AppContext) =  this.synchronized {
    if (_jmsOperations == null) {
      val aliased = connectionFactory
      _jmsOperations = new JmsOperations {lazy val connectionFactory = aliased; lazy val appContext = ap}
    }
    _jmsOperations
//    val conn: Connection = connectionFactory.createConnection()
//    if (jmsOperationsPerConnection.contains(conn)) jmsOperationsPerConnection(conn)
//    else {
//      val aliased = connectionFactory
//      val res = new JmsOperations {lazy val connectionFactory = aliased; lazy val appContext = ap}
//      jmsOperationsPerConnection = jmsOperationsPerConnection.updated(conn, res)
//      res
//    }
  }

  private case class EFQ(queue: String, messageSelector: String, ioThreads: Int, autoHandleExceptions: Boolean, deliveryMode: Int) extends EndpointFactory[JmsQueueEndpoint] {
    def apply(f: Flow) = new JmsQueueEndpoint(f, queue, jmsOperations(f.appContext), messageSelector, ioThreads, autoHandleExceptions, deliveryMode)
  }
  def queue(queue: String, messageSelector: String = null, ioThreads: Int = 4,
            autoHandleExceptions: Boolean = true, deliveryMode: Int = jmsMessage.DEFAULT_DELIVERY_MODE): EndpointFactory[JmsQueueEndpoint] =
              EFQ(queue, messageSelector, ioThreads, autoHandleExceptions, deliveryMode)
  private case class EFT(topic: String, messageSelector: String, ioThreads: Int, autoHandleExceptions: Boolean, deliveryMode: Int) extends EndpointFactory[JmsTopicEndpoint] {
    def apply(f: Flow) = new JmsTopicEndpoint(f, topic, jmsOperations(f.appContext), messageSelector, ioThreads, autoHandleExceptions, deliveryMode)
  }
  def topic(topic: String, messageSelector: String = null, ioThreads: Int = 4,
            autoHandleExceptions: Boolean = true, deliveryMode: Int = jmsMessage.DEFAULT_DELIVERY_MODE): EndpointFactory[JmsTopicEndpoint] =
              EFT(topic, messageSelector, ioThreads, autoHandleExceptions, deliveryMode)
  private case class EFD(dest: JmsDestination, messageSelector: String, ioThreads: Int, autoHandleExceptions: Boolean, deliveryMode: Int) extends EndpointFactory[JmsDestEndpoint] {
    def apply(f: Flow) = new JmsDestEndpoint(f, dest, jmsOperations(f.appContext), messageSelector, ioThreads, autoHandleExceptions, deliveryMode)
  }
  def apply(dest: JmsDestination, messageSelector: String = null, ioThreads: Int = 4,
            autoHandleExceptions: Boolean = true, deliveryMode: Int = jmsMessage.DEFAULT_DELIVERY_MODE): EndpointFactory[JmsDestEndpoint] =
              EFD(dest, messageSelector, ioThreads, autoHandleExceptions, deliveryMode)
}

/**
 * This interfaces represents JMS doable JMS operations, abstracted from the actual implementation, so that we can
 * provide them resiliently, handling connection problems and stuff.
 */
protected[jms] trait JmsOperations {

  protected val connectionFactory: ConnectionFactory
  protected val appContext: AppContext
  protected val log: LoggingAdapter = appContext.actorSystem.log
  @volatile var connection: Connection = connectionFactory.createConnection
  connection.start()

  @volatile protected var instantiatedSessions = Vector.empty[Session]
  private class SessionThreadLocal extends ThreadLocal[Session] {
    override def initialValue = {
      val res = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
      instantiatedSessions :+= res
      res
    }
  }
  @volatile protected var threadLocalSession: ThreadLocal[Session] = new SessionThreadLocal

  private[this] val reconnectionExecutor = java.util.concurrent.Executors.newFixedThreadPool(1)
  private[this] val reconnectionExecutionContext = ExecutionContext.fromExecutor(reconnectionExecutor)
  private[this] val scheduledAttempt = new concurrent.SyncVar[Unit]()
  def attemptConnection(): Boolean = scheduledAttempt.synchronized {
    if (scheduledAttempt.isSet) return false//if there is already an attempt scheduled, then this does nothing
    log.warning("Attempting to reestablish the JMS connection")
    try {
      val newCon = connectionFactory.createConnection()
      newCon setExceptionListener ExceptionHandler
      connection = newCon
      log.info("JMS reconnection successful, re-registering listeners")
      threadLocalSession = new SessionThreadLocal
      //call start again, so that Sources or Responsible may rebind themselves
      rebindMessageListeners()
      log.info("Registration completed.")
      true
    } catch {case ex: Exception =>
        scheduledAttempt.put(())
        log.error(ex, "Reconnection failed. Retrying in 5 seconds")
        appContext.actorSystem.scheduler.scheduleOnce(5 seconds){
          scheduledAttempt.take()
          attemptConnection()
        }(reconnectionExecutionContext)
        false
    }
  }

  object ExceptionHandler extends ExceptionListener {
    def onException(jmsException) {
      jmsException.getCause match {
        case io: java.io.IOException =>
          log.warning("IOException found on JMS connection...")
          try connection.close() catch {case ex: Exception =>
              log.error(ex, "Further exception trying to dispose broken JMS connection")
          }
          instantiatedSessions = Vector.empty //no instantiatedSessions
          attemptConnection()
        case _ => log.error(jmsException, s"Unexpected JMS exception")
      }
    }
  }
  connection setExceptionListener ExceptionHandler

  @inline
  protected final def handlingSessionClosed[R](op: => R): R = {
    try op
    catch {
      case ex: IllegalStateException =>
        if (attemptConnection()) op //if reconnection was successfully now, then retry the op
        else throw ex
    }
  }

  def createQueue(q: String): javax.jms.Queue = threadLocalSession.get.createQueue(q)
  def createTopic(t: String): javax.jms.Topic = threadLocalSession.get.createTopic(t)

  /**
   * Synchronously sends a message using a JMS producer.
   * If it is unable to send the message, it will throw an exception.
   */
  def sendMessage[Payload](msg: Message[Payload], destination: javax.jms.Destination, deliveryMode: Int)
  (implicit typeSupportedEv: TypeSupportedByTransport[String :: Array[Byte] :: java.io.Serializable :: TypeNil, Payload]) {
    implicit val session = threadLocalSession.get()
    handlingSessionClosed {
      val producer = session.createProducer(destination)
      try {
        producer.setDeliveryMode(deliveryMode)
        producer.send(msg)
      } finally Try(producer.close())
    }
  }
  /**
   * Synchronously sends a payload using a JMS producer.
   * If it is unable to send the message, it will throw an exception.
   */
  def sendMessage[Payload](msg: Payload, destination: javax.jms.Destination, deliveryMode: Int)
  (implicit containedEv: Contained[String :: Array[Byte] :: java.io.Serializable :: TypeNil, Payload]) {
    implicit val session = threadLocalSession.get()
    handlingSessionClosed {
      val producer = session.createProducer(destination)
      try {
        producer.setDeliveryMode(deliveryMode)
        producer.send(payloadToJmsMessage(msg, session))
      } finally Try(producer.close())
    }
  }

  /**
   * Performs a JMS ask, which implies sending a message and asynchronously listening for the response
   * on an temporary queue.
   */
  def ask[Payload](msg: Message[Payload], timeOut: FiniteDuration, destination: javax.jms.Destination, deliveryMode: Int)
  (implicit typeSupportedEv: TypeSupportedByTransport[String :: Array[Byte] :: java.io.Serializable :: TypeNil, Payload],
   executionContext: ExecutionContext): Future[Message[Any]] = {
    implicit val session = threadLocalSession.get()
    val jmsResponse = Promise[Message[Any]]()
    @volatile var tempQueue : javax.jms.TemporaryQueue = null
    @volatile var consumer : javax.jms.MessageConsumer = null
    val timeoutTimer = appContext.actorSystem.scheduler.scheduleOnce(timeOut) {
      jmsResponse tryFailure new java.util.concurrent.TimeoutException(s"$timeOut ellapsed")
      Future {
        try consumer.close() catch {case ex: Exception => log.error(ex, "Could not dispose consumer")}
        try tempQueue.delete() catch {case ex: Exception => log.error(ex, "Could not delete temporary queue " + tempQueue)}
      }
    }(appContext.actorSystem.dispatcher)

    Future {
      handlingSessionClosed {
        tempQueue = session.createTemporaryQueue
        val producer = session.createProducer(destination)
        try {
          producer.setDeliveryMode(deliveryMode)
          val m = msg: jmsMessage
          m.setJMSReplyTo(tempQueue)
          //The next future executes in the ioExecutionContext
          producer.send(m)
        } finally producer.close()
        consumer = session.createConsumer(tempQueue)
        consumer setMessageListener new MessageListener {
          def onMessage(m) {
            timeoutTimer.cancel()
            if (timeoutTimer.isCancelled) {
              try consumer.close() catch {case ex: Exception => log.error(ex, "Could not dispose consumer")}
              try tempQueue.delete() catch {case ex: Exception => log.error(ex, "Could not delete temporary queue " + tempQueue)}
            }
            jmsResponse trySuccess jmsMessageToEsbMessage(p => msg.map(_ => p), m)
          }
        }
      }
    }.onFailure { case t =>
      if (consumer != null)
          try consumer.close() catch {case ex: Exception => log.error(ex, "Could not dispose consumer")}
      if (tempQueue != null)
          try tempQueue.delete() catch {case ex: Exception => log.error(ex, "Could not delete temporary queue " + tempQueue)}
      jmsResponse.tryFailure(t)
    }
    jmsResponse.future
  }

  @volatile
  private[this] var registeredListeners = Vector.empty[(javax.jms.Destination, MessageListener, Flow)]
  /**
   * Registers a message listener to the JMS broker.
   */
  def registerMessageListener(destination: javax.jms.Destination, l: MessageListener, f: Flow) {
    log.info(s"Registering listener for flow ${f.name}")
    registeredListeners :+= (destination, l, f)
    threadLocalSession.get().createConsumer(destination).setMessageListener(l)
  }

  @volatile
  private[this] var registeredTemporaryListeners = Vector.empty[(MessageListener, TemporaryQueue=>Unit, Flow)]

  def registerTemporaryQueue(l: MessageListener, queueUpdaterCallback: TemporaryQueue=>Unit, f: Flow): TemporaryQueue = {
    log.info(s"Registering listener for flow ${f.name}")
    val session = threadLocalSession.get
    val temporaryQueue: TemporaryQueue = session.createTemporaryQueue
    val consumer = try {
      session.createConsumer(temporaryQueue)
    } catch { case t: Throwable =>
      Try(temporaryQueue.delete())
      throw t
    }
    try {
      consumer.setMessageListener(l)
    } catch { case t: Throwable =>
      Try(consumer.close())
      Try(temporaryQueue.delete)
      throw t
    }
    registeredTemporaryListeners :+= (l, queueUpdaterCallback, f)
    temporaryQueue
  }

  private def rebindMessageListeners() {
    val session = threadLocalSession.get()
    for ((dest, l, f) <- registeredListeners) {
      session.createConsumer(dest).setMessageListener(l)
      log.info(s"Listener for flow ${f.name} re-registered")
    }
    for ((l, queueUpdaterCallback, f) <- registeredTemporaryListeners) {
      val queue = session.createTemporaryQueue
      queueUpdaterCallback(queue)
      session.createConsumer(queue).setMessageListener(l)
      log.info(s"Temporary listener for flow ${f.name} re-registered")
    }
    if (registeredListeners.nonEmpty || registeredTemporaryListeners.nonEmpty) connection.start()
  }

  private[this] val endpointReferenceCount = new java.util.concurrent.atomic.AtomicInteger(0)
  /**
   * Starts necessary resources.
   */
  def start() {
    endpointReferenceCount.incrementAndGet
  }

  /**
   * Disposes any associated resources to the JMS broker.
   */
  def dispose() {
    val res = endpointReferenceCount.decrementAndGet
    if (res == 0) {
      Try(connection.close())
    }
  }


  private def payloadToJmsMessage(p: Any, s: Session) = p match {
    case null                               => s.createObjectMessage(null)
    case str: String                        => s.createTextMessage(str)
    case bytes: Array[Byte]                 => val res = s.createBytesMessage; res.writeBytes(bytes); res
    case serializable: java.io.Serializable => s.createObjectMessage(serializable)
    case other                              => throw new IllegalArgumentException(s"Unsupported jms payload with type ${other.getClass} = $other")
  }

  implicit def esbMessageToJmsMessage(m: Message[_ <: Any])(implicit s: Session): jmsMessage = {
    val res = payloadToJmsMessage(m.payload, s)
    val replyTo: javax.jms.Destination = m.replyTo match {
      case null => null
      case d => JmsUtils.pathToDestination(d, s)
    }
    res.setJMSReplyTo(replyTo)
    res.setJMSCorrelationID(m.correlationId)
    if (m.correlationGroupSize > 0) {
      res.setIntProperty("correlation-group-size", m.correlationGroupSize)
      res.setIntProperty("correlation-seq", m.correlationSequence)
    }
    m.header.outbound.foreach((kv: (String, Any)) => res.setObjectProperty(kv._1, kv._2))
    res
  }

  def jmsMessageToEsbMessage(mf: Any => Message[_], m: jmsMessage): Message[Any] = {
    val payload = m match {
      case tm: TextMessage   => tm.getText
      case om: ObjectMessage => om.getObject
      case bm: BytesMessage  => bm.readBytes(new Array[Byte](bm.getBodyLength.toInt))
      case _                 => m
    }
    import collection.JavaConversions._
    import collection.mutable.Map
    val properties = Map(m.getPropertyNames.map { case n: String => n -> m.getObjectProperty(n) }.toSeq: _*)
    val res = mf(payload)
    res.header.inbound ++= properties
    res.replyTo = m.getJMSReplyTo match {
      case null => null
      case d => JmsDestination(d)
    }
    res.correlationId = m.getJMSCorrelationID
    res.correlationGroupSize = if (m.propertyExists("correlation-group-size")) m.getIntProperty("correlation-group-size") else 0
    res.correlationSequence = if (m.propertyExists("correlation-seq")) m.getIntProperty("correlation-seq") else 0
    res
  }
}