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

import scala.language.implicitConversions
import language.{experimental, existentials}, experimental._
import akka.actor.{ Actor, Props, Cancellable }
import akka.routing.RoundRobinPool
import akka.event.LoggingAdapter
import scala.concurrent.{ ExecutionContext, Promise, Future, duration }, duration._
import scala.util._
import typelist._

/**
 * The base definition of everything a flow needs to works. A flow is associated to an AppContext, from which
 * it takes its worker, it takes a root InboundEndpoint, which defines its input type, as well as its return
 * to a logic call. When the InboundEndpoint is a Source endpoint, the flow is one way-only, when it is a
 * Responsible, it is a request-response flow.
 *
 * RootMessages are those which instantiate a run of the flow. They are fully typed with the Flow type and
 * they encapsulate the FlowRun.
 *
 * The FlowRun represents the current run of the flow. This value is available for every run of the logic
 * and its useful for its context properties.
 *
 * The type `InboundEndpointTpe` captures the type of the base InboundEndpoint. This type is usually not used,
 * unless you are defining special primitives that should only work during the run for certain flows. For example:
 *
 * {{{
 *   //For some theoretical endpoint for instant messaging, this methods allows to list
 *   //the connected users.
 *
 *   class SomeInstantMessagingEndpoint {
 *     val conn = ...
 *     ...
 *     def listUsers() = conn.listUsers()
 *   }
 *
 *   /**
      *    * General purpose listUsers, it will find the implicit FlowRun, obtain the endpoint, and call it's listUsers.
      *    * Note how we defined two parameter lists, and the first one is empty, this is so that the method can be called
      *    * like listUsers(). Otherwise, if users accidentally write listUsers() the compiler will complain that no FlowRun
      *    * is being passed.
      *    **/
 *   def listUsers[F <: Flow {type InboundEndpoint = SomeInstantMessagingEndpoint}]()(implicit run: FlowRun[F]) = {
 *      run.flow.listUsers()
 *   }
 *
 *   ...
 *
 *   //Now, when defining the flow, we can call it like
 *   new Flow(new SomeInstantMessagingEndpoint(...)) {
 *     logic {in =>
 *       listUsers()
 *     }
 *   }
 * }}}
 */
trait Flow extends FlowPatterns with Disposable {
  type InboundEndpointTpe <: InboundEndpoint

  def name: String
  val appContext: AppContext
  val rootEndpoint: InboundEndpointTpe
  val log: LoggingAdapter
  var logLifecycle = true
  @volatile private[this] var instantiatedEndpoints = Map.empty[EndpointFactory[_], Endpoint]
  @volatile private[this] var disposed = false

  def start(): Unit = {
    if (logic == null) throw new IllegalStateException(s"Logic has not been defined yet for flow $name.")
    rootEndpoint.start()
    if (logLifecycle) log.info(s"Flow $name started")
    disposed = false
  }
  protected def disposeImpl(): Unit = {
    rootEndpoint.dispose()
    instantiatedEndpoints.values foreach (_.dispose())
    instantiatedEndpoints = Map.empty
    appContext.actorSystem.stop(workerActors)
    if (blockingExecutorInstantiated) blockingExecutor.shutdown() //avoid instantiating the lazy executor if possible
    if (logLifecycle) log.info(s"Flow $name disposed")
    disposed = true
  }
  /**
   * Bind the life of this flow to that of the disposable.
   */
  def bind(d: Disposable): this.type = {
    d.onDispose(_ => dispose())
    this
  }


//  implicit def genericInvalidMessage[V <: Message[_], TL <: TypeList](value: V): Future[Message[OneOf[_, TL]]] = macro Flow.genericInvalidMessageImpl[V, TL]
//  implicit def genericInvalidFutureMessage[V <: Future[Message[_]], TL <: TypeList](value: V): Future[Message[OneOf[_, TL]]] = macro Flow.genericInvalidFutureMessageImpl[V, TL]
//  implicit def genericInvalidResponse[V, TL <: TypeList](value: V): Future[Message[OneOf[_, TL]]] = macro Flow.genericInvalidResponseImpl[V, TL]

  type Logic <: RootMessage[this.type] => LogicResult
  type LogicResult
  private[this] var logic: Logic = _
  def logicImpl(l: Logic): Unit = {
    logic = l
  }
  def logic[R](l: RootMessage[this.type] => R): Unit = macro Flow.logicMacroImpl[R, this.type]
  /**
   * Validates that the passed value of type T is one of the possible response types as defined by the Responsible root endpoint and
   * wraps in a properly typed OneOf instance.
   */
  def OneOf[T, FT <: Flow {type InboundEndpointTpe <: Responsible}](t: T)(implicit fr: FlowRun[FT], ev: Contained[FT#InboundEndpointTpe#SupportedResponseTypes, T]) = {
    new OneOf(t)(ev)
  }

  private class FlowRootMessage(val peer: Message[InboundEndpointTpe#Payload]) extends RootMessage[this.type] with MessageProxy[InboundEndpointTpe#Payload] {
    val enclosingFlow: Flow.this.type = Flow.this
    val self = this
    val flowRun = new FlowRun[enclosingFlow.type] {
      lazy val rootMessage = self
      val flow = enclosingFlow
    }
  }

  def runFlow(payload: InboundEndpointTpe#Payload): Future[LogicResult] = runFlow(Message(payload))
  def runFlow(rootMessage: Message[InboundEndpointTpe#Payload]): Future[LogicResult] = {
    val enclosingFlow: this.type = this
    doWork {
      val msg = new FlowRootMessage(rootMessage)
      val res = logic(msg)
      res match {
        case res: Future[_] => //result from a Responsible
          res.onComplete{ _ =>
            msg.flowRun.flowResponseCompleted()
          }(workerActorsExecutionContext(msg))
        case _ =>
          msg.flowRun.flowResponseCompleted()
      }
      res
    }
  }

  implicit val self: this.type = this
  implicit def messageInLogicImplicit: RootMessage[this.type] = macro Flow.findNearestMessageMacro[this.type]
  implicit def flowRun(implicit rootMessage: RootMessage[this.type]) = rootMessage.flowRun
  implicit def endpointFactory2Endpoint[T <: Endpoint](ef: EndpointFactory[T]): T = {
    instantiatedEndpoints.get(ef) match {
      case Some(endpoint) => endpoint.asInstanceOf[T]
      case None =>
        val res = ef(this)
        instantiatedEndpoints += ef -> res
        res.start()
        res
    }
  }

  /**
   * Number of workers to allocate in the flow's worker pool.
   */
  var workers: Int = 5

  protected def workerActorsReceive: akka.actor.Actor.Receive = {
    case w: Flow.Work[_] =>
      val oldContext = scala.concurrent.BlockContext.current
      val bc = new scala.concurrent.BlockContext {
        def blockOn[T](thunk: => T)(implicit permission: scala.concurrent.CanAwait): T = {
          log.warning("Blocking from a flow actor is discouraged! You'd be better composing futures.")
          oldContext.blockOn(thunk)
        }
      }
      scala.concurrent.BlockContext.withBlockContext(bc) {
        try w.promise.complete(Success(w.task()))
        catch { case ex: Exception => w.promise.complete(Failure(ex)) }
      }
  }
  protected def workerActorsName = name.replace(' ', '-') + "-actors"

  lazy val workerActors = appContext.actorSystem.actorOf(Props(new Actor {
        def receive = workerActorsReceive
      }).withRouter(RoundRobinPool(nrOfInstances = workers)), workerActorsName)
  /**
   * Executes the passed ``task`` asynchronously in a FlowWorker and returns
   * a Future for the computation.
   */
  def doWork[R](task: => R) = {
    if (disposed) {
      val error = "Work was requested for flow actor " + workerActorsName + " but the flow was already shutdown!"
      Future.failed(new Exception(error))
    } else {
      val res = new Flow.Work(() => task)
      workerActors ! res
      res.promise.future
    }
  }
  def scheduleOnce(delay: FiniteDuration)(f: => Unit): Cancellable = {
    appContext.actorSystem.scheduler.scheduleOnce(delay)(f)(blockingExecutorContext)
  }
  def schedule(initialDelay: FiniteDuration, frequency: FiniteDuration)(f: => Unit): Cancellable = {
    appContext.actorSystem.scheduler.schedule(initialDelay, frequency)(f)(blockingExecutorContext)
  }
  /**
   * ExecutionContext that delegates work to the worker actors. Note that probably you will
   * want the {{workerActorsExecutionContext}} instead since this execution context does not register
   * the continuations in the flowRun.
   * @see workerActorsExecutionContext
   */
  val rawWorkerActorsExecutionContext =  new ExecutionContext {
    def execute(runnable: Runnable): Unit = { doWork(runnable.run) }
    def reportFailure(t: Throwable) = appContext.actorSystem.log.error(t, "")
  }
  /**
   * ExecutionContext for concatenation based on flowRuns. This is the execution context selected
   * inside the logic method if none is specified (and if you are using a flow run you should always
   * pick this one).
   */
  def workerActorsExecutionContext(implicit rm: RootMessage[this.type]): ExecutionContext = new ExecutionContext {
    val frm = rm.asInstanceOf[FlowRootMessage]
    @volatile private var used = false
    def execute(runnable: Runnable): Unit = {
      if (used) throw new IllegalStateException("A materialized worker execution context can only be used once. Instead of reutilizing it, materialize a new one")
      doWork {
        runnable.run
      }
    }
    def reportFailure(t: Throwable) = appContext.actorSystem.log.error(t, "")
  }

  /**
   * Number of workers to allocate in the blocking workers pool.
   * Note that this threadpool is only instantiated if used by a call of blocking.
   */
  var blockingWorkers: Int = 10

  @volatile private[this] var blockingExecutorInstantiated = false
  private lazy val blockingExecutor = {
    blockingExecutorInstantiated = true
    java.util.concurrent.Executors.newFixedThreadPool(blockingWorkers, new java.util.concurrent.ThreadFactory {
        val num = new java.util.concurrent.atomic.AtomicInteger
        def newThread(r: Runnable) = {
          new Thread(r, name + "-blocking-worker-" + num.incrementAndGet)
        }
      })
  }
  /**
   * Private executor meant for IO
   */
  private lazy val blockingExecutorContext = ExecutionContext.fromExecutor(blockingExecutor)

  /**
   * Primitive to execute blocking code asynchronously without blocking
   * the flow's worker.
   *
   * @return A future for the computation
   */
  def blocking[R](code: => R): Future[R] = {
    Future(code)(blockingExecutorContext)
  }
}

object Flow {
  class Work[R](val task: () => R) {
    protected[Flow] val promise = Promise[R]()
  }

  import scala.reflect.macros.blackbox.Context
  def findNearestMessageMacro[F <: Flow](c: Context {type PrefixType <: Flow }): c.Expr[RootMessage[F]] = {
    import c.universe._
    val rootMessageTpe = c.typeOf[RootMessage[_]]
    val collected = c.enclosingClass.collect {
      case a @ Apply(Ident(i), List(Function(List(param @ ValDef(modifiers, paramName, _, _)), _))) if (i.encodedName.toString == "logic" || i.encodedName.toString == "logicImpl") &&
        modifiers.hasFlag(Flag.PARAM) && param.symbol.typeSignature <:< rootMessageTpe &&
        !c.typecheck(c.parse(paramName.encodedName.toString), c.TERMmode, param.symbol.typeSignature, silent = true).isEmpty =>
        paramName
    }.head
    val selectMessage = c.Expr(c.parse(collected.encodedName.toString))
    reify(selectMessage.splice)
  }


  def logicMacroImpl[R, F <: Flow](c: Context {type PrefixType = F})(l: c.Expr[RootMessage[F] => R])
  (fEv: c.WeakTypeTag[F]): c.Expr[Unit] = {
    import c.universe._
    def subType(t: Type, tName: Name, inClass: Type) = {
      val ts = t.member(tName)
      ts.typeSignature.asSeenFrom(t, inClass.typeSymbol.asClass)
    }

    def results(tree: Tree): List[Tree] = tree match {
      case Block(_, expr)      => results(expr)
      case Match(_, cases)     => cases flatMap (cd => results(cd.body))
      case If(_, thenp, elsep) => results(thenp) ::: results(elsep)
      case Typed(expr, _)      => results(expr)
      case Return(ret)         => results(ret)
      case t                   => List(t)
    }

    val exitBranches = l.tree match {
      case Function(vparams, body) => results(body)
    }

    //if the flow is oneway we always succeed:
    val logicResultType = subType(c.prefix.actualType, TypeName("LogicResult"), c.typeOf[Flows#Flow[_, _]])
    if (logicResultType =:= c.TypeTag.Unit.tpe) {
      reify {
        val flow = c.prefix.splice
        flow.logicImpl(l.splice.asInstanceOf[flow.Logic])
      }
    } else { //its a request response flow, so we must analyse the result
      //calculate the valid responses typelist
      val messageResultType = logicResultType.asInstanceOf[TypeRef].args(0)
      val rootEndpointType = subType(c.prefix.actualType, TermName("rootEndpoint"), c.typeOf[Flows#Flow[_, _]])
      val supportedResponsesTypeListType =
        subType(rootEndpointType, TypeName("SupportedResponseTypes"),
                rootEndpointType) match {
          case TypeBounds(lo, hi) => hi //in some case with generics, I might get the typelist like this
          case other => other
        }
      val containedT = typeOf[Contained[String :: TypeNil, String]].asInstanceOf[TypeRef] //obtain a sample typeRef to use its parts
      implicit val possibleTypesTag = c.TypeTag(supportedResponsesTypeListType)
      val typeListDescriptor = new TypeList.TypeListDescriptor(c.universe)
      val possibleTypesList = typeListDescriptor.describe(possibleTypesTag.asInstanceOf[typeListDescriptor.universe.TypeTag[_ <: TypeList]])

      //iterate every exit branch trying to find a valid mapping
      val futureMessageTypeTag = c.typeOf[Future[Message[_]]]
      val messageTypeTag = c.typeOf[Message[_]]
      val mappedBranches: List[(Tree, Tree)] = exitBranches map { branch =>
        if (branch.tpe =:= c.TypeTag.Nothing.tpe) {
          branch -> branch
        } else if (branch.tpe <:< futureMessageTypeTag) {
          val branchExpr = c.Expr[Future[Message[_]]](branch)
//          println(s"result($branch) is a type of future message")
          //to obtain the subtype directly from the message, I have to first get the view from the Message[_] POV, otherwise,
          //since this is a root message, the first argument to it, is the type of the flow, as in RootMessage[this.type] in flow's Logic.'
          if (branch.tpe <:< logicResultType) {
            branch -> branch
          } else {
            val messageSubType = branch.tpe.asInstanceOf[TypeRef].args(0).baseType(messageTypeTag.typeSymbol).asInstanceOf[TypeRef].args(0)
            val containedType = c.internal.typeRef(
              containedT.pre, containedT.sym,
              List(supportedResponsesTypeListType, messageSubType))
            val containedTree = c.inferImplicitValue(containedType, true, true, l.tree.pos)
            if (containedTree != EmptyTree) {
              val containedExr = c.Expr[Contained[_, _]](containedTree)
              val implicitRootMessage = c.Expr[RootMessage[_]](c.parse("messageInLogicImplicit"))
              val r = reify {
                val flow = c.prefix.splice
                def casted[R](a: Any) = a.asInstanceOf[R]
                import scala.concurrent.Future, uy.com.netlabs.luthier.typelist.OneOf
                branchExpr.splice.map(m => m.map(p => new OneOf(p)(casted(containedExr.splice))))(flow.workerActorsExecutionContext(casted(implicitRootMessage.splice)))
              }
              branch -> r.tree
            } else {
              c.abort(branch.pos, "Found flow's resulting future of message of type: " + messageSubType + "\n" +
                      "Expected a Message[T] or Future[Message[T]] where T can be any of [\n    " +
                      possibleTypesList.mkString("\n    ") + "\n]")
            }
          }

        } else if (branch.tpe <:< messageTypeTag) {
          if (branch.tpe <:< messageResultType) {
            branch -> branch
          } else {
            val branchExpr = c.Expr[Message[_]](branch)
            //to obtain the subtype directly from the message, I have to first get the view from the Message[_] POV, otherwise,
            //since this is a root message, the first argument to it, is the type of the flow, as in RootMessage[this.type] in flow's Logic.'
            val messageSubType = branch.tpe.baseType(messageTypeTag.typeSymbol).asInstanceOf[TypeRef].args(0)
            val containedType = c.internal.typeRef(
              containedT.pre, containedT.sym,
              List(supportedResponsesTypeListType, messageSubType))
            val containedTree = c.inferImplicitValue(containedType, true, true, l.tree.pos)
            if (containedTree != EmptyTree) {
              //reifiy a call to logic mapping the result
              val containedExr = c.Expr[Contained[_, _]](containedTree)
              val r = reify {
                val flow = c.prefix.splice
                def casted[R](a: Any) = a.asInstanceOf[R]
                import scala.concurrent.Future, uy.com.netlabs.luthier.typelist.OneOf
                Future.successful(branchExpr.splice.map(p => new OneOf(p)(casted(containedExr.splice))))
              }
              branch -> r.tree
            } else {
              c.abort(branch.pos, "Found flow's resulting message of type: " + messageSubType + "\n" +
                      "Expected a Message[T] or Future[Message[T]] where T can be any of [\n    " +
                      possibleTypesList.mkString("\n    ") + "\n]")
            }
          }

        } else {
          c.abort(branch.pos, "Found flow's result of type: " + branch.tpe + "\n" +
                  "Expected a Message[T] or Future[Message[T]] where T can be any of [\n    " +
                  possibleTypesList.mkString("\n    ") + "\n]")
        }
      }
      val resultTree = new Transformer {
        override def transform(tree: Tree) = {
//          super.transform(tree)
          mappedBranches.find(e => e._1 == tree) match {
            case Some((_, mapped)) => mapped
            case None => super.transform(tree)
          }
        }
      }.transform(l.tree)
      val mappedResult = c.Expr[Any](c untypecheck resultTree)
      reify {
        val flow = c.prefix.splice
        flow.logicImpl(mappedResult.splice.asInstanceOf[flow.Logic])
      }
    }
  }

}
