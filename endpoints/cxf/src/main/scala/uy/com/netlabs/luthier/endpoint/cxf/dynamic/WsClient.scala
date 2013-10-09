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
package endpoint
package cxf.dynamic

import language.dynamics
import scala.concurrent.duration._
import scala.reflect.ClassTag
import typelist._
import org.apache.cxf.jaxws.endpoint.dynamic.JaxWsDynamicClientFactory

class DynamicInstance private[dynamic] (val peer: Any) extends Dynamic {
  private val dynClass = peer.getClass
  private val dynFields = dynClass.getFields()
  private val dynMeths = dynClass.getMethods()
//  val instance = dynClass.newInstance()
  def selectDynamic(varia: String) = {
    //prefer methods, then methods of the from variable_=, then setters, and finally variables
    dynMeths find (_.getName == varia) orElse (dynMeths find (_.getName == s"get${varia.capitalize}")) orElse
    ((dynFields find (_.getName == varia)): @unchecked) match {
      case Some(setter: java.lang.reflect.Method) => setter.invoke(peer)
      case Some(setter: java.lang.reflect.Field)  => setter.get(peer)
      case None                                   => throw new NoSuchMethodException(s"Could not find method $varia")
    }
  }
  def applyDynamic(varia: String)(value: Any) = {
    dynMeths find (_.getName == varia) match {
      case Some(setter) => setter.invoke(peer, value.asInstanceOf[AnyRef])
      case None         => throw new NoSuchMethodException(s"Could not find method $varia")
    }
  }
  def updateDynamic(varia: String)(value: Any) {
    //prefer methods, then methods of the from variable_=, then setters, and finally variables
    dynMeths find (_.getName == varia) orElse (dynMeths find (_.getName == varia + "_$eq")) orElse (dynMeths find (_.getName == s"set${varia.capitalize}")) orElse
    ((dynFields find (_.getName == varia)): @unchecked) match {
      case Some(setter: java.lang.reflect.Method) => setter.invoke(peer, value.asInstanceOf[AnyRef])
      case Some(setter: java.lang.reflect.Field)  => setter.set(peer, value.asInstanceOf[AnyRef])
      case None                                   => throw new NoSuchMethodException(s"Could not find method $varia")
    }
  }

  override def toString() = {
    s"""Dynamic($dynClass) {
       |  ${dynFields.map(_.toString).mkString("\n  ")}
       |  ${dynMeths.map(_.toString).mkString("\n  ")}
       |}""".stripMargin
  }
}

case class WsClient(val url: String) {
  val (dynamicClient, clientClassLoader) = {
    val prevCl = Thread.currentThread().getContextClassLoader()
    val res = JaxWsDynamicClientFactory.newInstance().createClient(url)
    val clCl = Thread.currentThread().getContextClassLoader() //client classloader
    Thread.currentThread().setContextClassLoader(prevCl)
    res -> clCl
  }

  def instance(className: String) = new DynamicInstance(wsClassRef(className).newInstance())
  def wsClassRef(className: String) = Class.forName(className, false, clientClassLoader)
}

object WsInvoker {
  class DynamicWsClient[Result] private[WsInvoker] (val flow: Flow, client: WsClient, operation: String,
                                                    shutDownClientOnEndpointDispose: Boolean, resultClassTag: ClassTag[Result]) extends Askable {
    type SupportedTypes = Seq[_] :: Product :: TypeNil
    type Response = Result
    val resultRuntimeClass = resultClassTag.runtimeClass.asInstanceOf[Class[Result]]
    def askImpl[Payload: SupportedType](msg, timeout) = {
      flow.blocking {
        val res = msg.payload match {
          case traversable: Seq[Any] => client.dynamicClient.invoke(operation, traversable.asInstanceOf[Seq[AnyRef]].toArray: _*)
          case product: Product      => client.dynamicClient.invoke(operation, product.productIterator.asInstanceOf[Iterator[AnyRef]].toArray: _*)
        }
        msg map {_ =>
          if (res.length == 1) {
            if (resultRuntimeClass == classOf[DynamicInstance])
              new DynamicInstance(res(0)).asInstanceOf[Result]
            else
              resultRuntimeClass.cast(res(0))
          }
          else {
            resultRuntimeClass.cast(res)
          }
        }
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

  case class EF[Result] private[WsInvoker] (client: WsClient, operation: String, shutDownClientOnEndpointDispose: Boolean,
                                            resultClassTag: ClassTag[Result]) extends EndpointFactory[DynamicWsClient[Result]] {
    def apply(f: Flow) = new DynamicWsClient(f, client, operation, shutDownClientOnEndpointDispose, resultClassTag)
  }
  def apply[Result: ClassTag](client: WsClient, operation: String, shutDownClientOnEndpointDispose: Boolean = false) = EF[Result](client, operation, shutDownClientOnEndpointDispose, implicitly)
}