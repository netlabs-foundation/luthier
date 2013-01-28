package uy.com.netlabs.esb
package endpoint
package irc

import language.implicitConversions
import scala.util._
import scala.concurrent._, duration._
import typelist._

import jerklib._
import jerklib.{ events, listeners }, events._, listeners._

import java.net.InetSocketAddress

object Irc {
  def apply(
    channel: String,
    session: Session,
    closeSessionOnDispose: Boolean = false,
    ioWorkers: Int = 4) = EF(channel, session, closeSessionOnDispose, ioWorkers)

  case class EF private[Irc] (
      channel: String,
      session: Session,
      closeSessionOnDispose: Boolean,
      ioWorkers: Int) extends EndpointFactory[IrcEndpoint] {
    def apply(f) = new IrcEndpoint(f, channel, session, closeSessionOnDispose, ioWorkers)
  }

  case class IrcEndpoint private[Irc] (
      flow: Flow,
      channel: String,
      session: Session,
      closeSessionOnDispose: Boolean,
      ioWorkers: Int) extends base.BaseSource with base.BaseResponsible with base.BaseSink with Askable {

    type Payload = InMessage
    type SupportedResponseTypes = OutMessage :: RequestMessage :: TypeNil
    type SupportedTypes = SupportedResponseTypes
    type Response = Payload

    lazy val ioProfile = base.IoProfile.threadPool(ioWorkers)
    def start {
      if (onEventHandler != null || onRequestHandler != null) {
        session.addIRCEventListener(new IRCEventListener {
          def receiveEvent(e) {
            
            //TODO: fix NPE in me.getChannel.getName
            val inMessage = e match {
              case cce: ConnectionCompleteEvent                         => session.join(channel); return
              case jce: JoinCompleteEvent                               => log.info(s"Join completed to channel $channel"); return
              case me: MessageEvent if me.getChannel.getName == channel => InMessage(me.getNick, me.getChannel.getName, me.getMessage, false)
              case me: NoticeEvent if me.getChannel.getName == channel  => InMessage(me.byWho, me.toWho, me.getNoticeMessage, true) 
              case nickInUse: NickInUseEvent =>
                log.error(s"Nick already taken: ${nickInUse.getInUseNick}.")
                flow.dispose()
                return
              case other =>
                log.info("Other type of event: " + other + " - " + other.getClass)
                return //not interested in other type of events
            }
            if (onEventHandler != null) messageArrived(newReceviedMessage(inMessage))
            else if (onRequestHandler != null) requestArrived(newReceviedMessage(inMessage), {
              case Success(response) => pushMessage(response.map(_.value.asInstanceOf[OutMessage]))
              case Failure(ex)       => log.error(ex, "Error on flow response for request " + inMessage)
            })
          }
        })
      }
    }
    def dispose {
      if (closeSessionOnDispose) session.close("")
    }

    def pushMessage[Payload: SupportedType](msg: Message[Payload]) {
      msg.mapTo[IrcMessage].payload match {
        case OutMessage(dest, message, _) if dest.matches("^[0-9#&]") => session.sayChannel(session.getChannel(dest), message)
        case OutMessage(dest, message, _)                             => session.sayPrivate(dest, message)
      }
    }

    def ask[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration) = {
      push(msg) flatMap { _ =>
        val res = Promise[Message[InMessage]]()
        val tempListener = new listeners.IRCEventListener {
          def receiveEvent(e) {
            val in = e match {
              case me: MessageEvent => InMessage(me.getNick, me.getChannel.getName, me.getMessage, false)
              case me: NoticeEvent  => InMessage(me.byWho, me.toWho, me.getNoticeMessage, true)
              case _                => return //not interested in other type of events
            }
            msg.payload match {
              case om: OutMessage =>
                if (in.to == session.getNick) {
                  res.success(msg map (_ => in))
                  session.getIRCEventListeners().remove(this)
                }
              case rm: RequestMessage =>
                if (rm.responseFilter(in)) {
                  res.success(msg map (_ => in))
                  session.getIRCEventListeners().remove(this)
                }
            }
          }
        }
        session.addIRCEventListener(tempListener)
        res.future
      }
    }
  }

  //Utility functions

  def listUsersInChannel[FlowType <: Flow {
    type InboundEndpointTpe <: IrcEndpoint
  }]()(implicit flowRun: FlowRun[FlowType]) = {
    import flowRun.flow.rootEndpoint._
    val channeru = session.getChannel(channel)
    channeru.getNicks()
  }
}

sealed trait IrcMessage {
  def message: String
  def notice: Boolean
}
case class OutMessage(dest: String, message: String, notice: Boolean = false) extends IrcMessage
case class RequestMessage(dest: String, message: String, responseFilter: InMessage => Boolean, notice: Boolean = false) extends IrcMessage
object OutMessage {
  def unapply(msg: IrcMessage) = msg match {
    case om: OutMessage     => Some((om.dest, om.message, om.notice))
    case rm: RequestMessage => Some((rm.dest, rm.message, rm.notice))
    case _                  => None
  }
}
case class InMessage(from: String, to: String, message: String, notice: Boolean = false) extends IrcMessage