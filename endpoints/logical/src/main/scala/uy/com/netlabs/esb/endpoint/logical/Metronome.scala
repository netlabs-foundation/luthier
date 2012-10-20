package uy.com.netlabs.esb
package endpoint.logical

import scala.concurrent.duration._
import uy.com.netlabs.esb.EndpointFactory
import uy.com.netlabs.esb.Flow

class Metronome[P](f: Flow, pulse: P, initialDelay: FiniteDuration, every: FiniteDuration) extends endpoint.base.BaseSource {
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
  private val DefaultInitialDelay = 1.milli
  private case class EF[P](pulse: P, every: FiniteDuration, initialDelay: FiniteDuration) extends EndpointFactory[Metronome[P]] {
    def apply(f: Flow) = new Metronome(f, pulse, initialDelay, every)
  }
  def apply(every: FiniteDuration, initialDelay: FiniteDuration = DefaultInitialDelay): EndpointFactory[Metronome[Unit]] = EF((), every, initialDelay)
  def apply[P](pulse: P, every: FiniteDuration, initialDelay: FiniteDuration = DefaultInitialDelay): EndpointFactory[Metronome[P]] = EF(pulse, every, initialDelay)
}