package uy.com.netlabs.luthier
package endpoint
package cxf.dynamic

import language.dynamics
import scala.concurrent.duration._
import typelist._
import org.apache.cxf.jaxws.endpoint.dynamic.JaxWsDynamicClientFactory

case class WsClient(val url: String) {
  val (dynamicClient, clientClassLoader) = {
    val prevCl = Thread.currentThread().getContextClassLoader()
    val res = JaxWsDynamicClientFactory.newInstance().createClient(url)
    val clCl = Thread.currentThread().getContextClassLoader() //client classloader
    Thread.currentThread().setContextClassLoader(prevCl)
    res -> clCl
  }

  class DynamicInstance private[WsClient] (className: String) extends Dynamic {
    val dynClass = wsClassRef(className)
    val dynFields = dynClass.getFields()
    val dynMeths = dynClass.getMethods()
    val instance = dynClass.newInstance()
    def selectDynamic(varia: String) = {
      //prefer methods, then methods of the from variable_=, then setters, and finally variables
      dynMeths find (_.getName == varia) orElse (dynMeths find (_.getName == s"get${varia.capitalize}")) orElse
        ((dynFields find (_.getName == varia)): @unchecked) match {
          case Some(setter: java.lang.reflect.Method) => setter.invoke(instance)
          case Some(setter: java.lang.reflect.Field)  => setter.get(instance)
          case None                                   => throw new NoSuchMethodException(s"Could not find method $varia")
        }
    }
    def applyDynamic(varia: String)(value: Any) = {
      dynMeths find (_.getName == varia) match {
        case Some(setter) => setter.invoke(instance, value.asInstanceOf[AnyRef])
        case None         => throw new NoSuchMethodException(s"Could not find method $varia")
      }
    }
    def updateDynamic(varia: String)(value: Any) {
      //prefer methods, then methods of the from variable_=, then setters, and finally variables
      dynMeths find (_.getName == varia) orElse (dynMeths find (_.getName == varia + "_$eq")) orElse (dynMeths find (_.getName == s"set${varia.capitalize}")) orElse
        ((dynFields find (_.getName == varia)): @unchecked) match {
          case Some(setter: java.lang.reflect.Method) => setter.invoke(instance, value.asInstanceOf[AnyRef])
          case Some(setter: java.lang.reflect.Field)  => setter.set(instance, value.asInstanceOf[AnyRef])
          case None                                   => throw new NoSuchMethodException(s"Could not find method $varia")
        }
    }
  }

  def instance(className: String) = new DynamicInstance(className)
  def wsClassRef(className: String) = Class.forName(className, false, clientClassLoader)
}

object WsInvoker {
  class DynamicWsClient[Result] private[WsInvoker] (val flow: Flow, client: WsClient, operation: String, shutDownClientOnEndpointDispose: Boolean) extends Askable {
    type SupportedTypes = Seq[_] :: Product :: TypeNil
    type Response = Result
    def ask[Payload: SupportedType](msg, timeout) = {
      flow.blocking {
        val res = msg.payload match {
          case traversable: Seq[Any] => client.dynamicClient.invoke(operation, traversable.asInstanceOf[Seq[AnyRef]].toArray: _*)
          case product: Product      => client.dynamicClient.invoke(operation, product.productIterator.asInstanceOf[Iterator[AnyRef]].toArray: _*)
        }
        msg map (_ =>
          if (res.length == 1) res(0).asInstanceOf[Response]
          else res.asInstanceOf[Response])
      }
    }
    def start() {}
    def dispose() {
      if (shutDownClientOnEndpointDispose) {
        client.dynamicClient.getBus().shutdown(true)
        client.dynamicClient.getConduit().close()
        client.dynamicClient.destroy()
      }
    }
  }

  case class EF[Result] private[WsInvoker] (client: WsClient, operation: String, shutDownClientOnEndpointDispose: Boolean) extends EndpointFactory[DynamicWsClient[Result]] {
    def apply(f: Flow) = new DynamicWsClient(f, client, operation, shutDownClientOnEndpointDispose)
  }
  def apply[Result](client: WsClient, operation: String, shutDownClientOnEndpointDispose: Boolean = false) = EF[Result](client, operation, shutDownClientOnEndpointDispose)
}