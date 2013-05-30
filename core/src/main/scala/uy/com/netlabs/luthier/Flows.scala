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

import scala.language._
import language.experimental.macros
import scala.reflect.macros.Context
import scala.concurrent.Future
import scala.util._
import typelist._


/**
 * This class defines a scope where flows can be defined.
 */
trait Flows {
  import uy.com.netlabs.luthier.{ Flow => GFlow }
  def appContext: AppContext
  /**
   * Implicit appContext for Flows defintions. Separated from the appContext abstract def due to variable shadowing
   * on implementation.
   */
  protected implicit def flowsAppContext = appContext
  implicit val flowLogSource = new akka.event.LogSource[GFlow] {
    def genString(f) = f.appContext.name + ":" + f.name
  }

  @volatile var registeredFlows = Set.empty[GFlow]

  @scala.annotation.implicitNotFound("There is no ExchangePattern for ${T}.")
  sealed trait ExchangePattern[T <: InboundEndpoint, ResponseType] {
    private[Flows] def setup(t: T, f: Flow[T, ResponseType]): Unit
  }
  object ExchangePattern {
    implicit def OneWay[In <: Source] = new ExchangePattern[In, Unit] {
      private[Flows] def setup(endpoint, flow) {
        endpoint.onEvent(flow.runFlow(_))
      }
    }
    implicit def RequestResponse[In <: Responsible] = new ExchangePattern[In, Future[Message[OneOf[_, In#SupportedResponseTypes]]]] {
      private[Flows] def setup(endpoint, flow) {
        endpoint.onRequest{m =>
          val res = flow.runFlow(m).mapTo[Future[Message[OneOf[_, endpoint.SupportedResponseTypes]]]] //the result of running the flow is the one this is being mappedTo, but is encapsulated in the florRun future, hence, flatten it
          res.flatMap(identity)(flow.workerActorsExecutionContext)
        }
      }
    }
  }

  abstract class Flow[E <: InboundEndpoint, ResponseType](val name: String)(endpoint: EndpointFactory[E])(implicit val exchangePattern: ExchangePattern[E, ResponseType]) extends GFlow {
    protected def registerInParentFlows = true
    if (registerInParentFlows) registeredFlows += this
    type InboundEndpointTpe = E
    type Logic = RootMessage[this.type] => ResponseType
    type LogicResult = ResponseType
    val appContext = Flows.this.appContext
    val log = akka.event.Logging(appContext.actorSystem, this)
    val flowsContext = Flows.this //backreference
    val rootEndpoint = endpoint(this)
    exchangePattern.setup(rootEndpoint, this)
  }

  private[this] val anonFlowsDiposer = new scala.concurrent.ExecutionContext {
    import java.util.concurrent._
    val executor = Executors.newFixedThreadPool(1, new ThreadFactory {
        def newThread(r) = {
          val t = new Thread(r, "Anonymous flows disposer")
          t setDaemon true
          t
        }
      })
    def execute(r) = {executor.execute(r)}
    def reportFailure(t) = appContext.actorSystem.log.error(t, "Error disposing anonymous flow")
  }
  private[this] val anonFlowsIncId = new java.util.concurrent.atomic.AtomicLong()
  private[this] val anonFlowsDisposer = {
    import java.util.concurrent._
    val res = Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
        def newThread(r: Runnable) = {
          val res = new Thread(r, "Temporal flows disposer")
          res setDaemon true
          res
        }
      })
    scala.concurrent.ExecutionContext.fromExecutor(res)
  }
  /**
   * Convenient method to execute a code in a flow run context.
   * A special flow will be constructed for the passed code, it will run it immediately, and the be disposed.
   * This construct is useful when you must perform request using endpoints, instead of defining general purpose flows.
   *
   * '''Notes:''' Al methods that need an implicit MessageFactory, will need to be passed one explicitly (use the message)
   * unless you declare an implicit flowRun like this:
   *
   * {{{<pre>
   *   inFlow {(flow, dummyMsg) =>
   *     implicit val flowRun = dummyMsg.flowRun
   *     //your code here
   *   }
   * </pre>}}}
   * Also, to instantiate endpoints like you do in normal Flows, you need the implicit conversions declared in the flow
   * so its wise to imports its members. Summing up, to properly use this method, you should use it with this preamble:
   * {{{<pre>
   *   inFlow {(flow, dummyMsg) =>
   *     import flow._
   *     implicit val flowRun = dummyMsg.flowRun
   *     //your code here
   *   }
   * </pre>}}}
   *
   * @param code The code to be run in the flow. Its a function wich will be passed the flow instance, as well as the dummy
   *             root message.
   * @return The last expression in the code wrapped in a Future.
   */
  def inFlow[R](code: (Flow[_, Unit], RootMessage[Flow[_, Unit]]) => R): Future[R]= {
    val result = scala.concurrent.Promise[R]()
    val flowName = ("anon" + anonFlowsIncId.incrementAndGet + "@" + new Exception().getStackTrace()(2)).replace("$", "_").replaceAll("[()<>]", ";")
    val flow = new Flow(flowName)(new endpoint.base.DummySource) {
      logLifecycle = false
      override def registerInParentFlows = false
      workers = 1 //it doesn't make sense to allocate more actors
      logic {m =>
        try result.success(code(this, m))
        catch {case ex: Exception => result.failure(ex)}
      }
    }
    flow.rootEndpoint.runLogic
    val res = result.future
    res.onComplete {// code already got executed, can request the flow to stop
      case Success(f: Future[_]) => //if whatever the result, it is another future, then we await for it to dispose the flow
        f.onComplete{_ => flow.dispose()}(anonFlowsDiposer)
      case _ => flow.dispose() //under any other case, we dispose the flow right away.
    }(anonFlowsDiposer)
    res
  }

  /**
   * Helper method that starts all the flows registered in this container
   */
  def startAllFlows() {registeredFlows foreach (_.start())}
  /**
   * Helper method that stops all the flows registered in this container
   */
  def stopAllFlows() {registeredFlows foreach (_.dispose())}
}
