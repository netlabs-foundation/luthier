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

import scala.concurrent._

import typelist._

object Function {
  class FunctionPull[R] private[Function] (f: Flow, function: () => R, ioThreads: Int) extends endpoint.base.BasePullEndpoint with Askable {
    val flow = f
    type Payload = R
    type Response = R
    type SupportedTypes = Function0[R] :: TypeNil

    val ioProfile = base.IoProfile.threadPool(ioThreads, flow.name + "-function-ep")
    protected def retrieveMessage(mf): Message[Payload] = mf(function())

    def ask[Payload: SupportedType](msg, timeOut): Future[Message[Response]] = {
      Future(msg.as[Function0[R]] map (_()))
    }

    def start() {
    }
    def dispose() {
      ioProfile.dispose()
    }
  }
  private case class EF[R](function: () => R, ioThreads: Int = 1) extends EndpointFactory[FunctionPull[R]] {
    def apply(f: Flow) = new FunctionPull(f, function, ioThreads)
  }
  def apply[R](function: => R, ioThreads: Int = 1): EndpointFactory[PullEndpoint {type Payload = R}] = EF[R](() => function, ioThreads)
  def apply[R](ioThreads: Int = 1): EndpointFactory[Askable {type Response = R; type SupportedTypes = FunctionPull[R]#SupportedTypes}] = EF[R](null, ioThreads)
}