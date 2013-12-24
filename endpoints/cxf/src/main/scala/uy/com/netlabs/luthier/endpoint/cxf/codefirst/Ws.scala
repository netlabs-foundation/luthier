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
package cxf.codefirst

import language._

import uy.com.netlabs.luthier.reflect.util.MethodRef
import typelist._
import scala.concurrent._, duration._
import scala.util._
import java.util.concurrent.Executors
import org.apache.cxf.endpoint.Server

object Ws {

  class WsResponsible[I, PL, R] private[Ws] (f: Flow,
                                             val sei: Sei[I],
                                             val methodRef: MethodRef[I, PL, R],
                                             val maxResponseTimeout: FiniteDuration,
                                             val ioWorkers: Int) extends base.BaseResponsible {
    type SupportedResponseTypes = R :: TypeNil
    type Payload = PL

    implicit val flow = f

    def start() {
      sei.start()
    }
    def dispose() {
      sei.stop()
      ioProfile.dispose()
    }

    val ioProfile = base.IoProfile.threadPool(ioWorkers, flow.name + "-ws-ep")

    sei.InterfaceImplementor.methodImplementors += methodRef.method -> { args =>
      val payload = args match { //map from array to tuple
        case arr if arr == null => ()
        case arr if arr.length == 0 => ()
        case arr if arr.length == 1 => arr(0)
        case arr if arr.length == 2 => (arr(0), arr(1))
        case arr if arr.length == 3 => (arr(0), arr(1), arr(2))
        case arr if arr.length == 4 => (arr(0), arr(1), arr(2), arr(3))
        case arr if arr.length == 5 => (arr(0), arr(1), arr(2), arr(3), arr(4))
        case arr if arr.length == 6 => (arr(0), arr(1), arr(2), arr(3), arr(4), arr(5))
        case arr if arr.length == 7 => (arr(0), arr(1), arr(2), arr(3), arr(4), arr(5), arr(6))
        case arr if arr.length == 8 => (arr(0), arr(1), arr(2), arr(3), arr(4), arr(5), arr(6), arr(7))
        case arr if arr.length == 9 => (arr(0), arr(1), arr(2), arr(3), arr(4), arr(5), arr(6), arr(7), arr(8))
        case arr if arr.length == 10 => (arr(0), arr(1), arr(2), arr(3), arr(4), arr(5), arr(6), arr(7), arr(8), arr(9))
      }
      val m = newReceviedMessage(payload.asInstanceOf[PL])
      val resultPromise = Promise[Message[OneOf[_, SupportedResponseTypes]]]()
      requestArrived(m, resultPromise.complete)
      Await.result(resultPromise.future, maxResponseTimeout).payload.value //re-throw exception in the try on purpose
    }
  }

  private case class EF[I, PL, R](s: Sei[I], maxResponseTimeout: FiniteDuration, ioWorkers: Int)(f: I => MethodRef[I, PL, R]) extends EndpointFactory[WsResponsible[I, PL, R]] {
    def apply(flow: Flow) = new WsResponsible[I, PL, R](flow, s, f(null.asInstanceOf[I]), maxResponseTimeout, ioWorkers)
  }
  def apply[I, PL, R](s: Sei[I],
                      maxResponseTimeout: FiniteDuration = 30.seconds,
                      ioWorkers: Int = 4)(f: I => MethodRef[I, PL, R]): EndpointFactory[Responsible { //weird type declaration used instead of just WsResponsible[I, PL, R] because of bug in scalac
    type SupportedResponseTypes = WsResponsible[I, PL, R]#SupportedResponseTypes
    type Payload = WsResponsible[I, PL, R]#Payload
  }] = EF(s, maxResponseTimeout, ioWorkers)(f)
}
