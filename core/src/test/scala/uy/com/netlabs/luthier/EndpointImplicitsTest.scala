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

import org.scalatest.FunSuite
import typelist._
import testing.TestUtils._
import scala.reflect.runtime.universe

class EndpointImplicitsTest extends FunSuite {

  val askable: Askable { type SupportedTypes = String :: Long :: TypeNil } = null
  if (false) { //use false so that the code is not actually executed, just typechecked
    askable.askImpl(null: Message[Long])
    askable.askImpl(null: Message[String])
  }
  val suffix = "\nError occurred in an application involving default arguments."
  val expectedError = """This transport does not support messages with payload Int. Supported types are [
  String,
  Long
]"""
  test("Non supported type should be rejected by askable") {
    val err = materializeTypeError("askable.askImpl(null: Message[Int])") stripSuffix suffix
    assert(err == expectedError)
  }

  val pushable: Sink { type SupportedTypes = String :: Long :: TypeNil } = null
  if (false) { //use false so that the code is not actually executed, just typechecked
    pushable.pushImpl(null: Message[Long])
    pushable.pushImpl(null: Message[String])
  }
  test("Non supported type should be rejected by sink") {
    val err = materializeTypeError("pushable.pushImpl(null: Message[Int])") stripSuffix suffix
    assert(err == expectedError)
  }
}