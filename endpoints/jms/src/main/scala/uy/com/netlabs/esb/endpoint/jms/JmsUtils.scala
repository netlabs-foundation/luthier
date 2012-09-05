package uy.com.netlabs.esb.endpoint.jms

import uy.com.netlabs.esb.Destination
import javax.jms.{ Destination => JXMSDestination, Session, Topic, Queue }

object JmsUtils {

  val QueuePrefix = "jms:queue"
  val TopicPrefix = "jms:topic"
  private val pathSep = "://"

//  def destinationToPath(d: JXMSDestination) = d match {
//    case q: Queue => QueuePrefix + pathSep + q.toString
//    case t: Topic => TopicPrefix + pathSep + t.getTopicName
//    case _        => throw new IllegalArgumentException(s"Unkown destination $d: ${d.getClass}")
//  }
  def pathToDestination(p: Destination, s: Session) = p match {
    case u: Destination.UnresolvedDestination =>
      u.path.split(pathSep, 2) match {
        case Array(QueuePrefix, rest) => s.createQueue(rest)
        case Array(TopicPrefix, rest) => s.createTopic(rest)
        case _                        => throw new IllegalArgumentException(s"Invalid path(${u.path}). Expected paths should start with $QueuePrefix$pathSep or $TopicPrefix$pathSep")
      }
    case d: JmsDestination => d.destination
  }
}