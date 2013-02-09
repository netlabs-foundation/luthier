package uy.com.netlabs.luthier.endpoint.cxf.codefirst

import scala.reflect._
import javax.servlet.ServletContext
import javax.xml.ws.Endpoint
import org.apache.cxf.jaxws.JaxWsServerFactoryBean
import org.apache.cxf.transport.servlet.CXFNonSpringServlet
import org.apache.cxf.BusFactory
import org.apache.cxf.endpoint.Server
import javax.servlet.ServletRegistration

trait Sei[SEI] {
  val seiClass: ClassTag[SEI]
  val path: String

  def start()
  def stop()

  import java.lang.reflect.{ InvocationHandler, Method }
  object InterfaceImplementor extends InvocationHandler {
    var methodImplementors = Map[Method, Array[AnyRef] => Any]()
    def invoke(proxy, method, args): AnyRef = {
      methodImplementors.get(method).map(_(args)).getOrElse(throw new UnsupportedOperationException(s"Method ${method.getName()} is not implemented")).asInstanceOf[AnyRef]
    }
  }

  lazy val serviceImplementor = java.lang.reflect.Proxy.newProxyInstance(seiClass.runtimeClass.getClassLoader(), Array(seiClass.runtimeClass), InterfaceImplementor)
}

object Sei {
  def apply[SEI: ClassTag](url: String, path: String, serverConfigurator: JaxWsServerFactoryBean => Unit = null) =
    new JettySei[SEI](url, path, implicitly, Option(serverConfigurator))
  def apply[SEI: ClassTag](path: String, servletContext: ServletContext) = new ServletSei[SEI](path, servletContext, implicitly)
}

class JettySei[SEI](val url: String, val path: String, val seiClass: ClassTag[SEI], val srvFactoryConfigurator: Option[JaxWsServerFactoryBean => Unit]) extends Sei[SEI] {
  val srvFactory = new JaxWsServerFactoryBean();
  srvFactory.setServiceClass(seiClass.runtimeClass);
  srvFactory.setAddress(url + path);
  srvFactoryConfigurator map (_(srvFactory))

  srvFactory.setServiceBean(serviceImplementor)

  private[this] var server: Server = _
  def start() {
    server = srvFactory.create
  }
  def stop() {
    server.stop()
    server.destroy()
  }

}

/**
 * ServletSei registers the webservice to a servlet context. To do so, it registers the first time a dynamic servlet
 * which is then reused by all ServletSei's.
 *
 * **TODO:** Experimentation has to be done to verify that this does not lead to a memory leak in presence of hot deploy
 * in application servers.
 */
class ServletSei[SEI](val path: String, servletContext: ServletContext, val seiClass: ClassTag[SEI]) extends Sei[SEI] {
  private[this] var registree: Endpoint = _
  def start() {
    ServletSei.configureServlet(servletContext)
    registree = Endpoint.publish(path, serviceImplementor)
  }
  def stop() {
    registree.stop()
  }
}
object ServletSei {
  private[this] var servlet: CXFNonSpringServlet = _
  def configureServlet(servletContext: ServletContext) = synchronized {
    if (servlet == null) {
      servlet = new CXFNonSpringServlet {
        override def loadBus(servletConfig) {
          val bus = getBus
          BusFactory.setDefaultBus(bus)
        }
      }
    }
    val filter = servletContext.addFilter("cxf-luthier-filter", servlet)
    filter.setAsyncSupported(true)
    servlet
  }

}

