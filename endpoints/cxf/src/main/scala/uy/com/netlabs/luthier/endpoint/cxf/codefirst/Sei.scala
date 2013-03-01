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

