package uy.com.netlabs.luthier
package endpoint.http

import scala.util._
import scala.concurrent._
import scala.collection.mutable.Map
import scala.collection.JavaConversions._

import typelist._

import com.ning.http.client.{ AsyncHttpClient, AsyncHttpClientConfig,
                             AsyncCompletionHandler, Cookie, RequestBuilder => Request }
import dispatch.{ Promise => _, _ }
import unfiltered.filter.async.Plan
import unfiltered.request._
import unfiltered.response._

import javax.net.ssl._
import javax.servlet._, http._

object Http {

  class HttpDispatchEndpoint[R] private[Http] (f: Flow,
                                               req: Option[(Request, FunctionHandler[R])],
                                               ioThreads: Int,
                                               httpClientConfig: AsyncHttpClientConfig) extends PullEndpoint with Askable {
    val flow = f
    type Payload = R
    type Response = R
    type SupportedTypes = (Request, FunctionHandler[R]) :: TypeNil

    val dispatcher = new dispatch.FixedThreadPoolExecutor {
      val threadPoolSize = ioThreads
      val timeout = dispatch.Duration.millis(Long.MaxValue) //no timeout
      lazy val client = new AsyncHttpClient(httpClientConfig)
    }

    def start() {}
    def dispose() {
      try dispatcher.shutdown catch { case ex: Exception => flow.log.error(ex, "Could not shutdown dispatcher") }
    }

    private type HeaderBaggage = Seq[Cookie]

    private def wrapRequest(r: (Request, FunctionHandler[R])) = {
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
      res.header.inbound += ("Cookies" -> resp._2)
      res
    }

    def pull()(implicit mf: MessageFactory): Future[Message[Payload]] = {
      val promise = Promise[Message[Payload]]()
      dispatcher(wrapRequest(req.get)).onComplete {
        case Right(res) => promise.success(toMessage(res, mf))
        case Left(err)  => promise.failure(err)
      }
      promise.future
    }
    def ask[Payload: SupportedType](msg, timeOut): Future[Message[Response]] = {
      val promise = Promise[Message[Response]]()
      val req = msg.as[(Request, FunctionHandler[R])].payload
      val cookies = msg.header.inbound.getOrElse("Cookies", Seq.empty).asInstanceOf[Seq[Cookie]]
      cookies foreach req._1.addCookie
      //      println("Sending cookies: " + cookies)

      dispatcher(wrapRequest(req)).onComplete {
        case Right(res) => promise.success(toMessage(res, msg))
        case Left(err)  => promise.failure(err)
      }
      promise.future
    }
  }

  private case class EF[R](req: Option[(Request, FunctionHandler[R])], ioThreads: Int,
                           httpClientConfig: AsyncHttpClientConfig) extends EndpointFactory[HttpDispatchEndpoint[R]] {
    def apply(f: Flow) = new HttpDispatchEndpoint(f, req, ioThreads, httpClientConfig)
  }
  //this two lines are ugly as hell :)
  def apply[R](req: Request, handler: FunctionHandler[R], ioThreads: Int = 1, httpClientConfig: AsyncHttpClientConfig = new AsyncHttpClientConfig.Builder().build()): EndpointFactory[PullEndpoint { type Payload = R }] = EF(Some(req->handler), ioThreads, httpClientConfig)
  def apply[R](ioThreads: Int = 1, httpClientConfig: AsyncHttpClientConfig = new AsyncHttpClientConfig.Builder().build()): EndpointFactory[Askable { type Response = R; type SupportedTypes = HttpDispatchEndpoint[R]#SupportedTypes }] = EF(None, ioThreads, httpClientConfig)

  //Unfiltered part

  private[Http] trait ServerRepr {
    def start(name: String, filter: Filter)
    def stop()
  }
  private[Http] case class ServletServerRepr(servletContext: ServletContext) extends ServerRepr {
    def start(name, filter) {
      val enka = servletContext.addFilter(name, filter)
      enka.setAsyncSupported(true)
      enka.addMappingForServletNames(java.util.EnumSet.allOf(classOf[DispatcherType]), false, "*")
      enka.addMappingForUrlPatterns(java.util.EnumSet.allOf(classOf[DispatcherType]), false, "*")
      println(Console.RED + "Enka data" + Console.RESET)
      enka.getServletNameMappings() foreach (s => println(s"Mapping for filter $name: $s"))
      enka.getUrlPatternMappings() foreach (s => println(s"Mapping for filter $name: $s"))
    }
    def stop() {} //cannot stop :(
  }
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
    type SupportedResponseTypes = String :: ResponseFunction[HttpServletResponse] :: TypeNil

    object Handler extends Plan {
      def intent = {
        case req =>
          onRequestHandler(newReceviedMessage(req)).onComplete {
            case Success(m) => m.payload.value match {
                case s: String => req.respond(ResponseString(s))
                case rf: ResponseFunction[HttpServletResponse @unchecked] => req.respond(rf)
              }
            case Failure(ex) => req.respond(InternalServerError ~> ResponseString(ex.toString))
          }(flow.workerActorsExecutionContext)
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
  def server(servletContext: ServletContext) = HttpUnfilteredEF(ServletServerRepr(servletContext))
}