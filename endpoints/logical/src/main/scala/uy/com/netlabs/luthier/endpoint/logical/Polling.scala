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
package endpoint.logical

import scala.concurrent.duration._
import scala.util._
import language._

object Polling {

  class PollAskableEndpoint[A <: Askable, P](f: Flow, endpoint: EndpointFactory[A], initialDelay: FiniteDuration, every: FiniteDuration, message: P)(implicit ev: TypeSupportedByTransport[A#SupportedTypes, P]) extends endpoint.base.BaseSource {
    lazy val dest = endpoint(f)
    type Payload = A#Response
    var scheduledAction: akka.actor.Cancellable = _
    def start() {
      dest.start()
      scheduledAction = flow.schedule(initialDelay, every) {
        flow.log.debug(s"Flow ${flow.name} polling")
        implicit val ec = flow.rawWorkerActorsExecutionContext
        dest.askImpl(newReceviedMessage(message))(ev.asInstanceOf[TypeSupportedByTransport[dest.SupportedTypes, P]]) onComplete {
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
  class PollPullEndpoint[A <: PullEndpoint](f: Flow, endpoint: EndpointFactory[A], initialDelay: FiniteDuration, every: FiniteDuration) extends endpoint.base.BaseSource {
    lazy val dest = endpoint(f)
    type Payload = A#Payload

    var scheduledAction: akka.actor.Cancellable = _
    def start() {
      dest.start()
      implicit val ec = flow.rawWorkerActorsExecutionContext
      scheduledAction = appContext.actorSystem.scheduler.schedule(initialDelay, every) {
        flow.log.debug(s"Flow ${flow.name} polling")
        dest.pull()(newReceviedMessage(())) onComplete {
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
  private case class EFA[A <: Askable, P](endpoint: EndpointFactory[A], every: FiniteDuration, message: P, initialDelay: FiniteDuration)(implicit ev: TypeSupportedByTransport[A#SupportedTypes, P]) extends EndpointFactory[PollAskableEndpoint[A, P]] {
    def apply(f: Flow) = new PollAskableEndpoint(f, endpoint, initialDelay, every, message)
  }
  def Poll[A <: Askable, P](endpoint: EndpointFactory[A], every: FiniteDuration, message: P, initialDelay: FiniteDuration = Duration.Zero)(implicit ev: TypeSupportedByTransport[A#SupportedTypes, P]): EndpointFactory[PollAskableEndpoint[A, P]] = EFA(endpoint, every, message, initialDelay)
  private case class EFP[A <: PullEndpoint, P](endpoint: EndpointFactory[A], every: FiniteDuration, initialDelay: FiniteDuration) extends EndpointFactory[PollPullEndpoint[A]] {
    def apply(f: Flow) = new PollPullEndpoint(f, endpoint, initialDelay, every)
  }
  def Poll[A <: PullEndpoint](endpoint: EndpointFactory[A], every: FiniteDuration, initialDelay: FiniteDuration = Duration.Zero): EndpointFactory[PollPullEndpoint[A]] = EFP(endpoint, every, initialDelay)

}