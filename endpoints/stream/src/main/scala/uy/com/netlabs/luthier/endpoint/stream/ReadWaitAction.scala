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
package uy.com.netlabs.luthier.endpoint.stream

import scala.util._

/**
 * Action to be taken when a specified amount of time elapses after a byte is read.
 * See companion object for available implementations.
 */
trait ReadWaitAction[+State, +Prod] {
  def maxWaitTime: Long
  def apply[S >: State, P >: Prod](prevState: S, consumer: Consumer[S, P]): consumer.ConsumerResult
}

object ReadWaitAction {
  object DoNothing extends ReadWaitAction[Nothing, Nothing] {
    val maxWaitTime = 0l
    def apply[S >: Nothing, P >: Nothing](prevState: S, consumer: Consumer[S, P]): consumer.ConsumerResult = consumer.NeedMore(prevState)
  }
//  /**
//   * Sends a signal to the consumer requesting its partial data, if it has and is able to return it.
//   */
//  def PullData[State, Prod](timeout: Long) = new ReadWaitAction[State, Prod] {
//    def maxWaitTime = timeout
//    def apply[S >: State, P >: Prod](prevState: S, consumer: Consumer[S, P]): consumer.ConsumerResult = consumer.consume(RetrievePartialState(prevState))
//  }
  /**
   * Returns the passed value as consumer byproduct, without changing the state
   */
  def ReadValueData[State, Prod](timeout: Long, value: Prod) = new ReadWaitAction[State, Prod] {
    def maxWaitTime = timeout
    def apply[S >: State, P >: Prod](prevState: S, consumer: Consumer[S, P]): consumer.ConsumerResult = consumer.ByProduct(Seq(Success(value)), prevState)
  }
}