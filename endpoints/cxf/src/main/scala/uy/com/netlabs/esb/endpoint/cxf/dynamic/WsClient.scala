package uy.com.netlabs.esb
package endpoint
package cxf.dynamic

import language.dynamics
import scala.concurrent.util.Duration
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
  def instance(className: String): Dynamic = new Dynamic {
    val dynClass = wsClassRef(className)
    val dynFields = dynClass.getFields()
    val dynMeths = dynClass.getMethods()
    val instance = dynClass.newInstance()
    def applyDynamic(varia: String)(value: Any) = {
      dynMeths find (_.getName == varia) match {
        case Some(setter) => setter.invoke(instance, value.asInstanceOf[AnyRef])
        case None => throw new NoSuchMethodException(s"Could not find method $varia")
      }
    }
    def updateDynamic(varia: String)(value: Any) {
      (dynFields find (_.getName == varia) orElse (dynMeths find (_.getName == varia)) orElse
        (dynMeths find (_.getName == s"set${varia.capitalize}")): @unchecked) match {
        case Some(setter: java.lang.reflect.Method) => setter.invoke(instance, value.asInstanceOf[AnyRef])
        case Some(setter: java.lang.reflect.Field) => setter.set(instance, value.asInstanceOf[AnyRef])
        case None => throw new NoSuchMethodException(s"Could not find method $varia")
      }
    }
  }
  def wsClassRef(className: String) = Class.forName(className, false, clientClassLoader)
}

object WsInvoker {
  class DynamicWsClient[Result] private[WsInvoker] (val flow: Flow, client: WsClient, operation: String) extends Askable {
    type SupportedTypes = Seq[_] :: Product :: TypeNil
    type Response = Result
    def ask[Payload: SupportedType](msg, timeout) = {
      flow.blocking {
        val res = msg.payload match {
          case traversable: Seq[Any] => client.dynamicClient.invoke(operation, traversable.toArray)
          case product: Product => client.dynamicClient.invoke(operation, product.productIterator.toArray)
        }
        msg map (_ =>
          if (res.length == 1) res(0).asInstanceOf[Response]
          else res.asInstanceOf[Response]
        )
      }
    }
    def start() {}
    def dispose() {}
  }

  case class EF[Result] private[WsInvoker](client: WsClient, operation: String) extends EndpointFactory[DynamicWsClient[Result]] {
    def apply(f: Flow) = new DynamicWsClient(f, client, operation)
  }
  def apply(client: WsClient, operation: String) = EF(client, operation)
}