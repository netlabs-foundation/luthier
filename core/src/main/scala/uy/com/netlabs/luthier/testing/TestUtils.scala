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
package uy.com.netlabs.luthier.testing

import language.experimental.macros
import scala.reflect.macros.blackbox.Context
import scala.util.Try
import scala.reflect.macros.TypecheckException

private[testing] class TestUtils(val c: Context) {
  import c.universe._

  def materializeTypeErrorImpl(s: c.Expr[String]): c.Expr[String] = {
    import c.universe.Quasiquote
    val code = c.eval(c.Expr[String](c.untypecheck(s.tree)))
    val codeAst = c.parse(code)
    try {
      c.typecheck(codeAst)
      c.abort(s.tree.pos, "Passed code typechecks")
    } catch { case e: TypecheckException => 
      c.Expr[String](q"${e.getMessage}") 
    }
  }

  def assertTypeErrorEqualsImpl(code: Tree)(message: Tree): Tree = {
    val codeToParse = c.eval(c.Expr[String](c.untypecheck(code)))
    val expectedError = c.eval(c.Expr[String](c.untypecheck(message)))
    try {
      c.typecheck(c parse codeToParse)
      c.abort(code.pos, "Passed code typechecks")
    } catch {
      case e: TypecheckException =>
        if (e.getMessage != expectedError) {
          println(e.getMessage)
	  c.abort(code.pos, "Type error did not match expected message:\nType error:\n" + e.getMessage)
	} else q"()"
    }
  }
}
object TestUtils {

  def materializeTypeError(s: String): String = macro TestUtils.materializeTypeErrorImpl

  def assertTypeErrorEquals(code: String)(message: String): Unit = macro TestUtils.assertTypeErrorEqualsImpl
}
