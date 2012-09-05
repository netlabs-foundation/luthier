package uy.com.netlabs.esb

import scala.language._
import scala.concurrent.Future

trait Flows {
  import uy.com.netlabs.esb.{ Flow => GFlow }
  implicit def appContext: AppContext
  implicit def long2Duration(l: Long) = new scala.concurrent.util.DurationLong(l)
  implicit def message2Future[M <: Message[_]](m: M) = Future.successful(m)

  implicit def SelectRequestResponse[R <: Responsible](r: EndpointFactory[R]) = new Flows.SelectRequestResponse(r)
  implicit def SelectOneWay[S <: Source](s: EndpointFactory[S]) = new Flows.SelectOneWay(s)

  @volatile var registeredFlows = Set.empty[GFlow]

  @scala.annotation.implicitNotFound("There is no ExchangePattern for ${T}.")
  sealed trait ExchangePattern[T <: InboundEndpoint, ResponseType] {
    def registerLogic: T => (Message[T#Payload] => ResponseType) => Unit
  }
  object ExchangePattern {
    implicit def OneWayCommunicationPattern[In <: Source] = new ExchangePattern[In, Unit] {
      def registerLogic = (i: In) => (i.onEvent _).asInstanceOf[(Message[In#Payload] => Unit) => Unit]
    }
    implicit def RequestResponseCommunicationPattern[In <: Responsible] = new ExchangePattern[In, Future[Message[_]]] {
      def registerLogic = (i: In) => (i.onRequest _).asInstanceOf[(Message[In#Payload] => Future[Message[_]]) => Unit]
    }
  }

  abstract class Flow[E <: InboundEndpoint, ResponseType](val name: String)(endpoint: EndpointFactory[E])(implicit val exchangePattern: ExchangePattern[E, ResponseType]) extends GFlow {
    registeredFlows += this
    type Logic = Message[rootEndpoint.Payload] => ResponseType
    val rootEndpoint = endpoint(this)
    val appContext = Flows.this.appContext

    def logic(l: Logic) {
      exchangePattern.registerLogic(rootEndpoint)(l.asInstanceOf[Message[E#Payload] => ResponseType])
    }

  }

}
object Flows {
  class SelectRequestResponse[R <: Responsible](val r: EndpointFactory[R]) {
    type RR = Responsible { type Payload = R#Payload }
    def RequestResponse: EndpointFactory[RR] = r.asInstanceOf[EndpointFactory[RR]]
  }
  class SelectOneWay[S <: Source](val s: EndpointFactory[S]) {
    type SS = Source { type Payload = S#Payload }
    def OneWay: EndpointFactory[SS] = s.asInstanceOf[EndpointFactory[SS]]
  }
}