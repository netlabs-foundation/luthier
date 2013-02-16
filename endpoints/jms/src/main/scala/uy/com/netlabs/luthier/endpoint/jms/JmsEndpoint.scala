package uy.com.netlabs.luthier
package endpoint.jms

import typelist._
import javax.jms.{ ConnectionFactory, Connection, Queue, MessageListener,
                  ExceptionListener, Session, Message => jmsMessage,
                  TextMessage, ObjectMessage, BytesMessage, IllegalStateException }
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import scala.util.{ Try, Success, Failure }
import language._

private[jms] trait BaseJmsEndpoint extends endpoint.base.BaseSource with endpoint.base.BaseSink {
  val flow: Flow
  def createDestination(session: Session): javax.jms.Destination
  val connectionFactory: ConnectionFactory
  @volatile var connection: Connection = connectionFactory.createConnection
  val messageSelector: String
  val ioThreads: Int

  @volatile protected var instantiatedSessions = Vector.empty[Session]
  private class SessionThreadLocal extends ThreadLocal[Session] {
    override def initialValue = {
      val res = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
      instantiatedSessions :+= res
      res
    }
  }
  @volatile protected var threadLocalSession: ThreadLocal[Session] = new SessionThreadLocal

  private[this] val scheduledAttempt = new concurrent.SyncVar[Unit]()
  def attemptConnection(): Boolean = {
    if (scheduledAttempt.isSet) return false//if there is already an attempt scheduled, then this does nothing
    log.warning("Attempting to reestablish the JMS connection")
    try {
      val newCon = connectionFactory.createConnection()
      newCon setExceptionListener ExceptionHandler
      connection = newCon
      log.info("JMS reconnection successful, reconfiguring endpoint")
      //call start again, so that Sources or Responsible may rebind themselves
      start()
      threadLocalSession = new SessionThreadLocal
      log.info("Endpoint reconfiguration complete.")
      true
    } catch {case ex: Exception =>
        scheduledAttempt.put(())
        log.error(ex, "Reconnection failed. Retrying in 5 seconds")
        flow.scheduleOnce(5 seconds){
          scheduledAttempt.take()
          attemptConnection()
        }
        false
    }
  }
  
  object ExceptionHandler extends ExceptionListener {
    def onException(jmsException) {
      jmsException.getCause match {
        case io: java.io.IOException => 
          log.warning("IOException found on JMS connection...")
          try connection.close() catch {case _: Exception =>}
          instantiatedSessions = Vector.empty //no instantiatedSessions
          attemptConnection()
        case _ => log.error(jmsException, s"Unexpected JMS exception")
      }
    }
  }
  connection setExceptionListener ExceptionHandler
  
  /* Supported types on writing */
  type SupportedTypes = String :: Array[Byte] :: java.io.Serializable :: TypeNil
  type SupportedResponseTypes = SupportedTypes
  /* Receiving payload */
  type Payload = Any
  type Response = Any

  val ioProfile = endpoint.base.IoProfile.threadPool(ioThreads)

  protected def configureSourceOnStart(session: Session, destination: javax.jms.Destination) {
    if (onEventHandler != null) {
      session.createConsumer(destination).setMessageListener(new MessageListener {
          def onMessage(m: jmsMessage) {
            Try(messageArrived(jmsMessageToEsbMessage(newReceviedMessage, m))) match {
              case Failure(ex) => log.error(ex, "Failed deliverying event")
              case _           =>
            }
          }
        })
    }
  }

  def dispose() {
    instantiatedSessions foreach (s => Try(s.close))
    Try(connection.close())
    ioProfile.dispose
  }

  @inline
  protected final def handlingSessionClosed[R](op: => R): R = {
    try op
    catch {
      case ex: IllegalStateException => 
        if (attemptConnection()) op //if reconnection was successfully now, then retry the op
        else throw ex
    }
  }
  
  protected def pushMessage[Payload: SupportedType](msg: Message[Payload]) {
    println("TD_ID: " + threadLocalSession)
    implicit val session = threadLocalSession.get()
    handlingSessionClosed {
      val producer = session.createProducer(createDestination(session))
      producer.send(msg)
      producer.close()
    }
  }

  @inline private def optional[T, R](value: T, map: T => R = (v: T) => v.asInstanceOf[R]): R = Option(value).map(map).getOrElse(null.asInstanceOf[R])

  protected def jmsMessageToEsbMessage(mf: Any => Message[_], m: jmsMessage): Message[Any] = {
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
    res.replyTo = optional(m.getJMSReplyTo, JmsDestination.apply)
    res.correlationId = m.getJMSCorrelationID
    res.correlationGroupSize = if (m.propertyExists("correlation-group-size")) m.getIntProperty("correlation-group-size") else 0
    res.correlationSequence = if (m.propertyExists("correlation-seq")) m.getIntProperty("correlation-seq") else 0
    res
  }

  implicit protected def esbMessageToJmsMessage(m: Message[_ <: Any])(implicit s: Session): jmsMessage = {
    val res = m.payload match {
      case null                               => s.createObjectMessage(null)
      case str: String                        => s.createTextMessage(str)
      case bytes: Array[Byte]                 => val res = s.createBytesMessage; res.writeBytes(bytes); res
      case serializable: java.io.Serializable => s.createObjectMessage(serializable)
      case other                              => throw new IllegalArgumentException(s"Unsupported jms payload with type ${other.getClass} = $other")
    }
    res.setJMSReplyTo(optional(m.replyTo, JmsUtils.pathToDestination(_: Destination, s)))
    if (m.correlationGroupSize > 0) {
      res.setIntProperty("correlation-group-size", m.correlationGroupSize)
      res.setIntProperty("correlation-seq", m.correlationSequence)
    }
    m.header.inbound.foreach((kv: (String, Any)) => res.setObjectProperty(kv._1, kv._2))
    res
  }
}

class JmsQueueEndpoint(val flow: Flow,
                       queue: String,
                       val connectionFactory: ConnectionFactory,
                       val messageSelector: String,
                       val ioThreads: Int)
extends BaseJmsEndpoint
   with endpoint.base.BaseResponsible
   with Askable {

  def createDestination(session: Session): javax.jms.Destination = session.createQueue(queue)

  def start() {
    val session = threadLocalSession.get()
    val destination = createDestination(session)
    configureSourceOnStart(session, destination)
    if (onRequestHandler != null) {
      session.createConsumer(destination).setMessageListener(new MessageListener {
          def onMessage(m: jmsMessage) {
            Try(requestArrived(jmsMessageToEsbMessage(newReceviedMessage, m), sendMessage(_, m.getJMSReplyTo))) match {
              case Failure(ex) => log.error(ex, "Failed delivering request")
              case _           =>
            }
          }
        })
    }
    connection.start()
  }

  def ask[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration): Future[Message[Response]] = {
    implicit val session = threadLocalSession.get()
    handlingSessionClosed {
      val producer = session.createProducer(session.createQueue(queue))
      val tempQueue = session.createTemporaryQueue
      val m = msg: jmsMessage
      m.setJMSReplyTo(tempQueue)
      //The next future executes in the ioExecutionContext
      Future(producer.send(m)) flatMap { _ =>
        producer.close()
        val jmsResponse = Promise[Message[Any]]()
        val consumer = session.createConsumer(tempQueue)
        consumer setMessageListener new MessageListener {
          def onMessage(m) {
            consumer.close()
            jmsResponse success jmsMessageToEsbMessage(p => msg.map(_ => p), m)
          }
        }
        jmsResponse.future
      }
    }
  }

  protected def sendMessage(msg: Try[Message[OneOf[_, SupportedResponseTypes]]], dest: javax.jms.Destination) {
    implicit val session = threadLocalSession.get()
    handlingSessionClosed {
      val producer = session.createProducer(dest)
      msg match {
        case Success(m) => producer.send(m.map(_.value))
        case Failure(ex) => producer.send(session.createObjectMessage(ex))
      }
      producer.close()
    }
  }
}
class JmsTopicEndpoint(val flow: Flow,
                       topic: String,
                       val connectionFactory: ConnectionFactory,
                       val messageSelector: String,
                       val ioThreads: Int)
extends BaseJmsEndpoint {

  def createDestination(session: Session): javax.jms.Destination = session.createTopic(topic)

  def start() {
    val session = threadLocalSession.get()
    val destination = createDestination(session)
    configureSourceOnStart(session, destination)
    connection.start()
  }
}
object Jms {
  private case class EFQ(queue: String, connectionFactory: ConnectionFactory, messageSelector: String, ioThreads: Int) extends EndpointFactory[JmsQueueEndpoint] {
    def apply(f: Flow) = new JmsQueueEndpoint(f, queue, connectionFactory, messageSelector, ioThreads)
  }
  def queue(queue: String, connectionFactory: ConnectionFactory, messageSelector: String = null, ioThreads: Int = 4): EndpointFactory[JmsQueueEndpoint] = EFQ(queue, connectionFactory, messageSelector, ioThreads)
  private case class EFT(topic: String, connectionFactory: ConnectionFactory, messageSelector: String, ioThreads: Int) extends EndpointFactory[JmsTopicEndpoint] {
    def apply(f: Flow) = new JmsTopicEndpoint(f, topic, connectionFactory, messageSelector, ioThreads)
  }
  def topic(topic: String, connectionFactory: ConnectionFactory, messageSelector: String = null, ioThreads: Int = 4): EndpointFactory[JmsTopicEndpoint] = EFT(topic, connectionFactory, messageSelector, ioThreads)
}