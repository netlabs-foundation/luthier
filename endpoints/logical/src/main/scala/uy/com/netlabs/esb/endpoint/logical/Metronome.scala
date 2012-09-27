package uy.com.netlabs.esb
package endpoint

import scala.concurrent.util.Duration
import scala.concurrent.util.duration._
import uy.com.netlabs.esb.EndpointFactory
import uy.com.netlabs.esb.Flow

class Metronome[P](f: Flow, pulse: P, initialDelay: Duration, every: Duration) extends endpoint.base.BaseSource {
  type Payload = P
  
  var scheduledAction: akka.actor.Cancellable = _
  def start() {
    scheduledAction = flow.schedule(initialDelay, every) {
      messageArrived(Message(pulse))
    }
  }
  def dispose() {
    scheduledAction.cancel()
  }
  val flow = f
}

object Metronome {
  private case class EF[P](pulse: P, every: Duration, initialDelay: Duration) extends EndpointFactory[Metronome[P]] {
    def apply(f: Flow) = new Metronome(f, pulse, initialDelay, every)
  }
  def apply(every: Duration, initialDelay: Duration = Duration.Zero): EndpointFactory[Metronome[Unit]] = EF((), every, initialDelay)
  def apply[P](pulse: P, every: Duration, initialDelay: Duration = Duration.Zero): EndpointFactory[Metronome[P]] = EF(pulse, every, initialDelay)
}