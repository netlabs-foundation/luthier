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

import language.experimental.macros
import typelist._
import shapeless._
import scala.concurrent._
import scala.reflect.macros.blackbox.Context

private[luthier] class FlowMacros(val c: Context { type PrefixType <: Flow }) {
  import c.universe._
  val descriptor = new TypeList.TypeListDescriptor(c.universe)

  def findNearestMessageMacro[F <: Flow]: c.Expr[RootMessage[F]] = {
    val rootMessageTpe = c.typeOf[RootMessage[_]]
    val collected = c.enclosingClass.collect {
      case a @ Apply(Ident(i), List(Function(List(param @ ValDef(modifiers, paramName, _, _)), _))) if (i.encodedName.toString == "logic" || i.encodedName.toString == "logicImpl") &&
        modifiers.hasFlag(Flag.PARAM) && param.symbol.typeSignature <:< rootMessageTpe &&
        !c.typecheck(c.parse(paramName.encodedName.toString), c.TERMmode, param.symbol.typeSignature, silent = true).isEmpty =>
        paramName
    }.head
    val selectMessage = c.Expr(c.parse(collected.encodedName.toString))
    reify(selectMessage.splice)
  }

  def errorForLogicResultImpl[TL <: HList](a: c.Expr[Any]): c.Expr[Future[Message[OneOf[_, TL]]]] = {
    val expectedType = c.macroApplication.tpe
    val typeList = expectedType.baseType(symbolOf[Future[_]]).typeArgs.head.baseType(symbolOf[Message[_]]).typeArgs.head.baseType(symbolOf[OneOf[_, _]]).typeArgs.tail.head

    val types = descriptor.describeAsString(c.TypeTag(typeList).asInstanceOf[descriptor.universe.TypeTag[TL]])
    c.abort(a.tree.pos, s"Invalid response type for the flow.\n  Found: ${a.tree.tpe}\n  " +
      s"Expected a Message[T] or a Future[Message[T]] where T can be any of ${types.mkString("[\n    ", "\n    ", "\n  ]")}")
  }

  def errorForMessage[TL <: HList](a: c.Expr[Any]): c.Expr[Message[OneOf[_, TL]]] = {
    val typeList = c.macroApplication.tpe.baseType(symbolOf[Message[_]]).typeArgs.head.baseType(symbolOf[OneOf[_, _]]).typeArgs.tail.head

    val types = descriptor.describeAsString(c.TypeTag(typeList).asInstanceOf[descriptor.universe.TypeTag[TL]])
    c.abort(a.tree.pos, s"Invalid message type for the flow.\n Found: ${a.tree.tpe}\n " +
      s"Expected a Message[T] where T can be any of ${types.mkString("[\n    ", "\n    ", "\n  ]")}")
  }

  def errorForOneOf[TL <: HList](a: Tree): Tree = {
    val typeList = c.macroApplication.tpe.baseType(symbolOf[OneOf[_, _]]).typeArgs.tail.head

    val types = descriptor.describeAsString(c.TypeTag(typeList).asInstanceOf[descriptor.universe.TypeTag[TL]])
    c.abort(a.pos, s"Invalid type for the flow.\n Found: ${a.tpe}\n " +
      s"Expected one of ${types.mkString("[\n    ", "\n    ", "\n  ]")}")
  }
}

// vim: set ts=4 sw=4 et:
