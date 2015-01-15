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
package endpoint.http

import language.{ higherKinds, implicitConversions }
import scala.util._
import scala.concurrent._, duration._
import scala.collection.mutable.Map
import scala.collection.JavaConversions._

import shapeless._, typelist._

import com.ning.http.client.{
  AsyncHttpClient,
  AsyncHttpClientConfig,
  AsyncCompletionHandler,
  Cookie,
  RequestBuilder,
  Request,
  Response
}
import dispatch.{ Future => _, _ }
import unfiltered.filter.async.Plan
import unfiltered.request._
import unfiltered.response._

import javax.net.ssl._
import javax.servlet._, http._

object Http {

  class HttpDispatchEndpoint[R] private[Http] (f: Flow,
                                               req: Option[(RequestBuilder, FunctionHandler[R])],
                                               ioThreads: Int,
                                               httpClientConfig: AsyncHttpClientConfig) extends Pullable with Askable {
    val flow = f
    type Payload = R
    type Response = R
    type SupportedType = <::<[(RequestBuilder, FunctionHandler[R])] :: <::<[(Request, FunctionHandler[R])] :: HNil

    val threadPoolSize = ioThreads

    val ioProfile = endpoint.base.IoProfile.threadPool(ioThreads, flow.name + "-http-ep")
    val dispatcher = new dispatch.HttpExecutor {
      lazy val client = new AsyncHttpClient(httpClientConfig)
    }

    def start() {}
    def dispose() {
      try dispatcher.shutdown catch { case ex: Exception => flow.log.error(ex, "Could not shutdown dispatcher") }
      ioProfile.executor.shutdown()
    }

    private type HeaderBaggage = Seq[Cookie]

    private def wrapRequest(r: (RequestBuilder, FunctionHandler[R])) = {
      r._1.build() -> new AsyncCompletionHandler[(R, HeaderBaggage)] {

        def onCompleted(response) = r._2.onCompleted(response) -> response.getCookies
        //proxy all methods -- must call all supers because this AsyncCompletionHandler is horribly stateful
        override def onBodyPartReceived(content) = {
          super.onBodyPartReceived(content)
          r._2.onBodyPartReceived(content)
        }
        override def onContentWriteCompleted() = {
          super.onContentWriteCompleted()
          r._2.onContentWriteCompleted()
        }
        override def onContentWriteProgress(amount, current, total) = {
          super.onContentWriteProgress(amount, current, total)
          r._2.onContentWriteProgress(amount, current, total)
        }
        override def onHeadersReceived(h) = {
          super.onHeadersReceived(h)
          r._2.onHeadersReceived(h)
        }
        override def onHeaderWriteCompleted() = {
          super.onHeaderWriteCompleted()
          r._2.onHeaderWriteCompleted()
        }
        override def onStatusReceived(s) = {
          super.onStatusReceived(s)
          r._2.onStatusReceived(s)
        }
        override def onThrowable(t) = {
          super.onThrowable(t)
          r._2.onThrowable(t)
        }
      }
    }
    private def toMessage(resp: (R, HeaderBaggage), mf: MessageFactory) = {
      import collection.JavaConversions._
      val res = mf(resp._1)
      res.header.outbound.clear
      res.header.inbound += ("Cookies" -> resp._2)
      res
    }

    def pull()(implicit mf: MessageFactory): Future[Message[Payload]] = {
      val promise = Promise[Message[Payload]]()
      dispatcher(wrapRequest(req.get))(ioProfile.executionContext).onComplete {
        case Success(res) => promise.success(toMessage(res, mf))
        case Failure(err) => promise.failure(err)
      }(ioProfile.executionContext)
      promise.future
    }
    def ask[Payload: TypeIsSupported](msg, timeOut): Future[Message[Response]] = {
      val promise = Promise[Message[Response]]()
      val req = msg.payload match {
        case (r: RequestBuilder, f: FunctionHandler[Response]@unchecked) => (r, f)
        case (r: Request, f: FunctionHandler[Response]@unchecked) => (new RequestBuilder(r), f)
      }
      val cookies = msg.header.outbound.getOrElse("Cookies", Seq.empty).asInstanceOf[Seq[Cookie]]
      cookies foreach req._1.addOrReplaceCookie
      //      println("Sending cookies: " + cookies)

      dispatcher(wrapRequest(req))(ioProfile.executionContext).onComplete {
        case Success(res) => promise.success(toMessage(res, msg))
        case Failure(err) => promise.failure(err)
      }(ioProfile.executionContext)
      promise.future
    }
  }

  private case class EF[R](req: Option[(RequestBuilder, FunctionHandler[R])], ioThreads: Int,
                           httpClientConfig: AsyncHttpClientConfig) extends EndpointFactory[HttpDispatchEndpoint[R]] {
    def apply(f: Flow) = new HttpDispatchEndpoint(f, req, ioThreads, httpClientConfig)
  }

  type HttpPullableEF[R] = EndpointFactory[Pullable { type Payload = R }]
  type HttpAskableEF[R] = EndpointFactory[Askable {
      type Response = R
      type SupportedType = HttpDispatchEndpoint[R]#SupportedType
    }]

  //implicit class RequestBuilderAndHandler[R](val r: (RequestBuilder, FunctionHandler[R])) extends ReqType[R]

  def pulling[Req, HandlerType](req: Req, ioThreads: Int = 1,
                                httpClientConfig: AsyncHttpClientConfig = new AsyncHttpClientConfig.Builder().build())(
    implicit supported: ReqType[Req, HandlerType]): HttpPullableEF[HandlerType] =
      EF(Some(supported(req)), ioThreads, httpClientConfig)

  def apply[R](ioThreads: Int = 1, httpClientConfig: AsyncHttpClientConfig = new AsyncHttpClientConfig.Builder().build()): HttpAskableEF[R] =
    EF(None, ioThreads, httpClientConfig)

  sealed trait ReqType[R, HandlerType] {
    def apply(r: R): (RequestBuilder, FunctionHandler[HandlerType])
  }
  implicit def requestBuilderAndHandlerReqType[R] = allRequestBuilderAndHandlerReqType.asInstanceOf[ReqType[(RequestBuilder, FunctionHandler[R]), R]]
  private val allRequestBuilderAndHandlerReqType = new ReqType[(RequestBuilder, FunctionHandler[Any]), Any] {
    type HandlerType = Any
    def apply(r) = r
  }
  implicit def requestAndHandlerReqType[R, F[X] <: FunctionHandler[X]] = allRequestAndHandlerReqType.asInstanceOf[ReqType[(Request, F[R]), R]]
  private val allRequestAndHandlerReqType = new ReqType[(Request, FunctionHandler[Any]), Any] {
    type HandlerType = Any
    def apply(r) = (new RequestBuilder(r._1), r._2)
  }
  implicit val allRequestReqType = new ReqType[Req, Response] {
    type HandlerType = Response
    def apply(r) = (r.toRequestBuilder, new FunctionHandler[Response](identity))
  }

  //Unfiltered part

  private[Http] trait ServerRepr {
    def start(name: String, filter: Filter)
    def stop()
  }
  //  private[Http] case class ServletServerRepr(servletContext: ServletContext) extends ServerRepr {
  //    def start(name, filter) {
  //      val enka = servletContext.addFilter(name, filter)
  //      enka.setAsyncSupported(true)
  //      enka.addMappingForServletNames(java.util.EnumSet.allOf(classOf[DispatcherType]), false, "*")
  //      enka.addMappingForUrlPatterns(java.util.EnumSet.allOf(classOf[DispatcherType]), false, "*")
  //      println(Console.RED + "Enka data" + Console.RESET)
  //      enka.getServletNameMappings() foreach (s => println(s"Mapping for filter $name: $s"))
  //      enka.getUrlPatternMappings() foreach (s => println(s"Mapping for filter $name: $s"))
  //    }
  //    def stop() {} //cannot stop :(
  //  }
  private[Http] case class JettyServerRepr(port: Int) extends ServerRepr {
    var server: unfiltered.jetty.Http = _
    def start(name, filter) {
      server = unfiltered.jetty.Http.local(port).filter(filter)
      server.start()
    }
    def stop() {
      server.stop()
    }
  }

  class HttpUnfilteredEndpoint private[Http] (f: Flow, repr: ServerRepr) extends Responsible {
    type Payload = HttpRequest[HttpServletRequest]
    type SupportedResponseType = OneOf[_, String :: ResponseFunction[HttpServletResponse] :: HNil]

    object Handler extends Plan {
      def intent = {
        case req =>
          try {
            onRequestHandler(newReceviedMessage(req)).onComplete {
              case Success(m) => m.payload.match_ {
                  case s: String => req.respond(ResponseString(s))
                  case rf: ResponseFunction[HttpServletResponse @unchecked] => req.respond(rf)
                }
              case Failure(ex) =>
                log.error(ex, "Unexpected exception in code handling request")
                req.respond(InternalServerError ~> ResponseString(ex.toString))
            }(flow.rawWorkerActorsExecutionContext)
          } catch { case ex: Throwable => log.error(ex, "Could not process message"); throw ex }
      }
    }

    val flow = f
    def start() {
      repr.start(flow.name, Handler)
    }
    def dispose() {
      repr.stop()
    }
  }
  case class HttpUnfilteredEF private[Http] (repr: ServerRepr) extends EndpointFactory[HttpUnfilteredEndpoint] {
    def apply(f: Flow) = new HttpUnfilteredEndpoint(f, repr)
  }
  def server(port: Int) = HttpUnfilteredEF(JettyServerRepr(port))
  //  def server(servletContext: ServletContext) = HttpUnfilteredEF(ServletServerRepr(servletContext))
}
