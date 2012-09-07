package uy.com.netlabs.esb
package endpoint

import scala.concurrent.util.{Duration, duration}, duration._

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
  def apply(every: Duration, initialDelay: Duration = Duration.Zero) = new EndpointFactory[Metronome[Unit]] {
    def apply(f: Flow) = new Metronome(f, (), initialDelay, every)
  }
  def apply[P](pulse: P, every: Duration, initialDelay: Duration = Duration.Zero) = new EndpointFactory[Metronome[P]] {
    def apply(f: Flow) = new Metronome(f, pulse, initialDelay, every)
  }
}