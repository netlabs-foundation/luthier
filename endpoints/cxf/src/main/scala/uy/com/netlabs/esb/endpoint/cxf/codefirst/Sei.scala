package uy.com.netlabs.esb.endpoint.cxf.codefirst

import scala.reflect._
import org.apache.cxf.jaxws.JaxWsServerFactoryBean

class Sei[SEI](val url: String, val seiClass: ClassTag[SEI], val srvFactoryConfigurator: Option[JaxWsServerFactoryBean => Unit]) {
  val srvFactory = new JaxWsServerFactoryBean();
  srvFactory.setServiceClass(seiClass.runtimeClass);
  srvFactory.setAddress(url);
  srvFactoryConfigurator map (_(srvFactory))
  
  import java.lang.reflect.{InvocationHandler, Method}
  object InterfaceImplementor extends InvocationHandler {
    var methodImplementors = Map[Method, Array[AnyRef] => Any]()
    def invoke(proxy, method, args): AnyRef = {
      methodImplementors.get(method).map(_(args)).getOrElse(throw new UnsupportedOperationException(s"Method ${method.getName()} is not implemented")).asInstanceOf[AnyRef]
    }
  }
  
  srvFactory.setServiceBean(java.lang.reflect.Proxy.newProxyInstance(seiClass.runtimeClass.getClassLoader(), Array(seiClass.runtimeClass), InterfaceImplementor))
}

object Sei {
  def apply[SEI: ClassTag](url: String, serverConfigurator: JaxWsServerFactoryBean => Unit = null) = 
    new Sei[SEI](url, classTag[SEI], Option(serverConfigurator))
}