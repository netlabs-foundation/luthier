package uy.com.netlabs.esb
package endpoint

import scala.concurrent.Future
import scala.concurrent.util.Duration
import scala.util._
import language._

object PollingFeatures {

  class PollAskableEndpoint[A <: Askable, P](f: Flow, endpoint: EndpointFactory[A], initialDelay: Duration, every: Duration, message: Message[P])(implicit ev: TypeSupportedByTransport[A#SupportedTypes, P]) extends endpoint.base.BaseSource {
    lazy val dest = endpoint(f)
    type Payload = A#Response

    var scheduledAction: akka.actor.Cancellable = _
    def start() {
      dest.start()
      scheduledAction = flow.schedule(initialDelay, every) {
        flow.log.debug(s"Flow ${flow.name} polling")
        implicit val ec = flow.workerActorsExecutionContext
        dest.ask(message)(ev.asInstanceOf[TypeSupportedByTransport[dest.SupportedTypes, P]]) onComplete {
          case Success(response)  => messageArrived(response.asInstanceOf[Message[Payload]])
          case Failure(err) => flow.log.error(err, s"Poller ${flow.name} failed")
        }
      }
    }
    def dispose() {
      scheduledAction.cancel()
      dest.dispose()
    }
    val flow = f
  }
  class PollPullEndpoint[A <: PullEndpoint](f: Flow, endpoint: EndpointFactory[A], initialDelay: Duration, every: Duration) extends endpoint.base.BaseSource {
    lazy val dest = endpoint(f)
    type Payload = A#Payload

    var scheduledAction: akka.actor.Cancellable = _
    def start() {
      dest.start()
      implicit val ec = flow.workerActorsExecutionContext
      scheduledAction = appContext.actorSystem.scheduler.schedule(initialDelay, every) {
        flow.log.debug(s"Flow ${flow.name} polling")
        dest.pull onComplete {
          case Success(response)  => messageArrived(response.asInstanceOf[Message[Payload]])
          case Failure(err) => flow.log.error(err, s"Poller ${flow.name} failed")
        }
      }
    }
    def dispose() {
      scheduledAction.cancel()
      dest.dispose()
    }
    val flow = f
  }
  def Poll[A <: Askable, P](endpoint: EndpointFactory[A], every: Duration, message: Message[P], initialDelay: Duration = Duration.Zero)(implicit ev: TypeSupportedByTransport[A#SupportedTypes, P]) = new EndpointFactory[PollAskableEndpoint[A, P]] {
    def apply(f: Flow) = new PollAskableEndpoint(f, endpoint, initialDelay, every, message)
  }
  def Poll[A <: PullEndpoint](endpoint: EndpointFactory[A], every: Duration, initialDelay: Duration = Duration.Zero) = new EndpointFactory[PollPullEndpoint[A]] {
    def apply(f: Flow) = new PollPullEndpoint(f, endpoint, initialDelay, every)
  }

}