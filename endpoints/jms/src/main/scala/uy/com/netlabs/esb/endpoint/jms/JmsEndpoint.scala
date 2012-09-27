package uy.com.netlabs.esb
package endpoint.jms

import typelist._
import javax.jms.{ Connection, Queue, MessageListener, Session, Message => jmsMessage, TextMessage, ObjectMessage, BytesMessage }
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.util.{ Duration, duration }, duration._
import scala.util.{ Try, Success, Failure }
import language._

private[jms] trait BaseJmsEndpoint extends endpoint.base.BaseSource with endpoint.base.BaseSink {
  val flow: Flow
  def createDestination(session: Session): javax.jms.Destination
  val connection: Connection
  val messageSelector: String
  val ioThreads: Int

  @volatile protected var instantiatedSessions = Vector.empty[Session]
  protected val threadLocalSession = new ThreadLocal[Session] {
    override def initialValue = {
      val res = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
      instantiatedSessions :+= res
      res
    }
  }

  /* Supported types on writing */
  type SupportedTypes = String :: Array[Byte] :: java.io.Serializable :: TypeNil
  type SupportedResponseTypes = SupportedTypes
  /* Receiving payload */
  type Payload = Any
  type Response = Any

  private[this] val ioExecutor = java.util.concurrent.Executors.newFixedThreadPool(ioThreads)
  implicit val ioExecutionContext = ExecutionContext.fromExecutor(ioExecutor)

  protected def configureSourceOnStart(session: Session, destination: javax.jms.Destination) {
    if (onEvents.nonEmpty) {
      session.createConsumer(destination).setMessageListener(new MessageListener {
        def onMessage(m: jmsMessage) {
          Try(messageArrived(jmsMessageToEsbMessage(messageFactory, m))) match {
            case Failure(ex) => log.error(ex, "Failed deliverying event")
            case _           =>
          }
        }
      })
    }
  }

  def dispose() {
    instantiatedSessions foreach (s => Try(s.close))
    ioExecutor.shutdownNow
  }

  protected def pushMessage[Payload: SupportedType](msg: Message[Payload]) {
    implicit val session = threadLocalSession.get()
    val producer = session.createProducer(createDestination(session))
    producer.send(msg)
    producer.close()
  }

  @inline private def optional[T, R](value: T, map: T => R = (v: T) => v.asInstanceOf[R]): R = Option(value).map(map).getOrElse(null.asInstanceOf[R])

  protected def jmsMessageToEsbMessage(mf: MessageFactory, m: jmsMessage): Message[Any] = {
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
    res.header ++= Map("INBOUND" -> properties)
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
    m.header.get("INBOUND").foreach(map => map.foreach((kv: (String, Any)) => res.setObjectProperty(kv._1, kv._2)))
    res
  }
}

class JmsQueueEndpoint(val flow: Flow,
                       queue: String,
                       val connection: Connection,
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
    if (onRequests.nonEmpty) {
      session.createConsumer(destination).setMessageListener(new MessageListener {
        def onMessage(m: jmsMessage) {
          Try(requestArrived(jmsMessageToEsbMessage(messageFactory, m), sendMessage(_, m.getJMSReplyTo))) match {
            case Failure(ex) => log.error(ex, "Failed delivering request")
            case _           =>
          }
        }
      })
    }
    connection.start()
  }

  def ask[Payload: SupportedType](msg: Message[Payload], timeOut: Duration): Future[Message[Response]] = {
    implicit val session = threadLocalSession.get()
    val producer = session.createProducer(session.createQueue(queue))
    val tempQueue = session.createTemporaryQueue
    val m = msg: jmsMessage
    m.setJMSReplyTo(tempQueue)
    //The next future executes in the ioExecutionContext
    Future(producer.send(m)) map { _ =>
      producer.close()
      val consumer = session.createConsumer(tempQueue)
      val res = consumer.receive(timeOut.toMillis)
      consumer.close()
      jmsMessageToEsbMessage(msg, res)
    }
  }

  protected def sendMessage(msg: Try[Message[OneOf[_, SupportedResponseTypes]]], dest: javax.jms.Destination) {
    implicit val session = threadLocalSession.get()
    val producer = session.createProducer(dest)
    msg match {
      case Success(m) => producer.send(m.map(_.value))
      case Failure(ex) => producer.send(session.createObjectMessage(ex))
    }
    
    producer.close()
  }
}
class JmsTopicEndpoint(val flow: Flow,
                       topic: String,
                       val connection: Connection,
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
  private case class EFQ(queue: String, connection: Connection, messageSelector: String, ioThreads: Int) extends EndpointFactory[JmsQueueEndpoint] {
    def apply(f: Flow) = new JmsQueueEndpoint(f, queue, connection, messageSelector, ioThreads)
  }
  def queue(queue: String, connection: Connection, messageSelector: String = null, ioThreads: Int = 4): EndpointFactory[JmsQueueEndpoint] = EFQ(queue, connection, messageSelector, ioThreads)
  private case class EFT(topic: String, connection: Connection, messageSelector: String, ioThreads: Int) extends EndpointFactory[JmsTopicEndpoint] {
    def apply(f: Flow) = new JmsTopicEndpoint(f, topic, connection, messageSelector, ioThreads)
  }
  def topic(topic: String, connection: Connection, messageSelector: String = null, ioThreads: Int = 4): EndpointFactory[JmsTopicEndpoint] = EFT(topic, connection, messageSelector, ioThreads)
}