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
package uy.com.netlabs.luthier.reflect.util

import java.lang.reflect.Method
import scala.language._
import scala.language.experimental.macros
import scala.reflect._
import scala.reflect.macros.Context

trait MethodRef[ClassRef, ArgumentList, ReturnType] {
  val method: Method
}
object MethodRef {

  private class MethodRefImpl[ClassRef, ArgumentList, ReturnType](val method: Method) extends MethodRef[ClassRef, ArgumentList, ReturnType]

  implicit def methodRef[ClassRef, R](f: () => R) = macro MethodRefMacros.methodRefImpl0[ClassRef, R]
  implicit def methodRef[ClassRef, P1, R](f: P1 => R) = macro MethodRefMacros.methodRefImpl1[ClassRef, P1, R]
  implicit def methodRef[ClassRef, P1, P2, R](f: (P1, P2) => R) = macro MethodRefMacros.methodRefImpl2[ClassRef, P1, P2, R]
  implicit def methodRef[ClassRef, P1, P2, P3, R](f: (P1, P2, P3) => R) = macro MethodRefMacros.methodRefImpl3[ClassRef, P1, P2, P3, R]
  implicit def methodRef[ClassRef, P1, P2, P3, P4, R](f: (P1, P2, P3, P4) => R) = macro MethodRefMacros.methodRefImpl4[ClassRef, P1, P2, P3, P4, R]
  implicit def methodRef[ClassRef, P1, P2, P3, P4, P5, R](f: (P1, P2, P3, P4, P5) => R) = macro MethodRefMacros.methodRefImpl5[ClassRef, P1, P2, P3, P4, P5, R]
  implicit def methodRef[ClassRef, P1, P2, P3, P4, P5, P6, R](f: (P1, P2, P3, P4, P5, P6) => R) = macro MethodRefMacros.methodRefImpl6[ClassRef, P1, P2, P3, P4, P5, P6, R]

  object MethodRefMacros {
    def methodRefImpl0[ClassRef: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(f: c.Expr[() => R]) = impl[ClassRef, Unit, R](c)(f)
    def methodRefImpl1[ClassRef: c.WeakTypeTag, P1: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(f: c.Expr[P1 => R]) = impl[ClassRef, P1, R](c)(f)
    def methodRefImpl2[ClassRef: c.WeakTypeTag, P1: c.WeakTypeTag, P2: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(f: c.Expr[(P1, P2) => R]) = impl[ClassRef, (P1, P2), R](c)(f)
    def methodRefImpl3[ClassRef: c.WeakTypeTag, P1: c.WeakTypeTag, P2: c.WeakTypeTag, P3: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(f: c.Expr[(P1, P2, P3) => R]) = impl[ClassRef, (P1, P2, P3), R](c)(f)
    def methodRefImpl4[ClassRef: c.WeakTypeTag, P1: c.WeakTypeTag, P2: c.WeakTypeTag, P3: c.WeakTypeTag, P4: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(f: c.Expr[(P1, P2, P3, P4) => R]) = impl[ClassRef, (P1, P2, P3, P4), R](c)(f)
    def methodRefImpl5[ClassRef: c.WeakTypeTag, P1: c.WeakTypeTag, P2: c.WeakTypeTag, P3: c.WeakTypeTag, P4: c.WeakTypeTag, P5: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(f: c.Expr[(P1, P2, P3, P4, P5) => R]) = impl[ClassRef, (P1, P2, P3, P4, P5), R](c)(f)
    def methodRefImpl6[ClassRef: c.WeakTypeTag, P1: c.WeakTypeTag, P2: c.WeakTypeTag, P3: c.WeakTypeTag, P4: c.WeakTypeTag, P5: c.WeakTypeTag, P6: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(f: c.Expr[(P1, P2, P3, P4, P5, P6) => R]) = impl[ClassRef, (P1, P2, P3, P4, P5, P6), R](c)(f)

    private def impl[ClassRef: c.WeakTypeTag, ArgumentList: c.WeakTypeTag, ReturnType: c.WeakTypeTag](c: Context)(f: c.Expr[_]): c.Expr[MethodRef[ClassRef, ArgumentList, ReturnType]] = {
      import c.universe._
      val classRefType = implicitly[c.WeakTypeTag[ClassRef]]
      val methodName = f.tree.collect { case f@Function(valdef, Apply(Select(prefix, methodName), args)) => methodName }.head

      classRefType.tpe.member(methodName) match {
        case NoSymbol => c.abort(c.enclosingPosition, s"No method '$methodName' found in class ${classRefType.tpe}")
        case _ => //pass
      }

      val classExpr = c.Expr[Class[ClassRef]](c.reifyRuntimeClass(classRefType.tpe, true))
      val methodNameExpr = c.literal(methodName.encoded)
      reify {
        val c = classExpr.splice
        val validMethods = c.getMethods().filter(_.getName == methodNameExpr.splice)
        require(validMethods.length == 1, "Needed only one method named " + methodNameExpr.splice + " obtained: [" + validMethods.length + "] = " + validMethods.mkString("[", ", ", "]"))
        (new MethodRefImpl[ClassRef, ArgumentList, ReturnType](validMethods(0)))
      }
    }
  }
}