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
package uy.com.netlabs.luthier.typelist

import language.{ higherKinds, implicitConversions }
import language.experimental.macros
import scala.reflect.api.{ Universe, TypeTags, Types }
import shapeless._

object TypeList {
  class TypeListDescriptor(val universe: Universe) {
    import universe._
    val ConsType = typeOf[::[_, _]]
    val NilType = typeOf[HNil]

    def describe[TL <: HList](implicit tl: TypeTag[TL]): List[Type] = {
      def describe(t: Type): List[Type] = {
        val t2 = t.dealias
        if (t2 == NilType) Nil
        else {
          t2.baseType(ConsType.typeSymbol) match {
            case NoType => List(t2)
            case TypeRef(_, _, args) => args flatMap describe
          }
        }
      }
      describe(tl.tpe)
    }
  }
}

sealed trait ErrorSelectorImplicits[Selector[TL <: HList, A]] {
  self: TypeSelectorImplicits[Selector] =>
  implicit def noContains[TL <: HList, A]: Selector[TL, A] = macro TypeSelectorImplicits.noSelectorErrorImpl[Selector, TL, A]
}
trait TypeSelectorImplicits[Selector[TL <: HList, A]] extends ErrorSelectorImplicits[Selector] {
  implicit def select[TL <: HList, A](implicit s: shapeless.ops.hlist.Selector[TL, A]): Selector[TL, A] = null.asInstanceOf[Selector[TL, A]]
}
object TypeSelectorImplicits {
  import scala.reflect.macros.blackbox.Context
  def noSelectorErrorImpl[Selector[_ <: HList, _], T <: HList, A](c: Context)(
    implicit selectorEv: c.WeakTypeTag[Selector[T, A]], tEv: c.WeakTypeTag[T], aEv: c.WeakTypeTag[A]): c.Expr[Selector[T, A]] = {
    import c.universe._
    // detect the typelist that we are searching a selector for
    val TypeRef(_, _, List(typelist, element)) = c.macroApplication.tpe.baseType(selectorEv.tpe.typeSymbol)
    val typelistDescriptor = new TypeList.TypeListDescriptor(c.universe)
    val types = typelistDescriptor.describe(c.TypeTag(typelist).asInstanceOf[typelistDescriptor.universe.TypeTag[T]])
    val typesDescription = types.mkString("[\n  ", ",\n  ", "\n]")
    val failError = selectorEv.tpe.typeSymbol.annotations.find(a => a.tree.tpe =:= typeOf[scala.annotation.implicitNotFound]) map { a =>
      val List(Literal(Constant(msg: String))) = a.tree.children.tail
      val (List(tl), List(e)) = selectorEv.tpe.typeParams partition { tp => tp.asType.toType <:< typeOf[HList] }
      msg.replace("${" + e.name.toString + '}', element.dealias.toString).
        replace("${" + tl.name.toString + '}', typesDescription)
    } getOrElse s"Type ${aEv.tpe} was not found in typelist $typesDescription"

    c.abort(c.enclosingPosition, failError)
  }
}

@annotation.implicitNotFound("${E} is not contained in ${TL}")
trait Contained[-TL <: HList, E] //declared TL as contravariant, to deal with java generics.
object Contained extends TypeSelectorImplicits[Contained]

class OneOf[+E, TL <: HList](_value: E)(implicit contained: Contained[TL, E]) {
  val unsafeValue = _value
  def value(implicit ev: OneOf.TypeListHasSizeOne[TL]): ev.TheType = unsafeValue.asInstanceOf[ev.TheType]
  
  override def toString = "OneOf(" + unsafeValue + ")"
  def valueAs[T](implicit contained: Contained[TL, T]) = unsafeValue.asInstanceOf[T]
  def match_(f: PartialFunction[E, Any]): Any = macro OneOf.dispatchMacro[E, TL]

}
object OneOf {
  
  @annotation.implicitNotFound("This OneOf has more than one possible type ${T}, you should be using method match_ to properly evaluate the value")
  trait TypeListHasSizeOne[T <: HList] { type TheType }
  implicit def typeListHasSizeOne[H]: TypeListHasSizeOne[H :: HNil] {type TheType = H} = null
  
  implicit def oneOfSingleType2Type[H](o: OneOf[_, H :: HNil]): H = o.value
  
  import scala.reflect.macros.whitebox.Context
  def dispatchMacro[E, TL <: HList](c: Context { type PrefixType = OneOf[E, TL] })(
		  f: c.Tree)(implicit eEv: c.WeakTypeTag[E], tlEv: c.WeakTypeTag[TL]): c.Tree = {
    import c.universe._
//    println(f)
    val casesAst = f.collect {
      case q"{case ..$cases}" => cases
    }.flatten

    val typeListDescriptor = new TypeList.TypeListDescriptor(c.universe)
    val typesInListAsStr = typeListDescriptor.describe(c.TypeTag(tlEv.tpe).asInstanceOf[typeListDescriptor.universe.TypeTag[TL]])
    
    //validate that every pattern is contained in the type list and accumulate the case result type
    val casesMappedTypes = casesAst map { caseAst =>
      //      println(s"Testing $caseAst, pat type: ${caseAst.pat.tpe}, body type: ${caseAst.body.tpe}")
      val typeToInfer = c.typecheck(tq"_root_.uy.com.netlabs.luthier.typelist.Contained[${tlEv.tpe}, ${caseAst.pat.tpe}]", c.TYPEmode).tpe
      lazy val typeIsContained = c.inferImplicitValue(typeToInfer, withMacrosDisabled = true)
      if (!(caseAst.pat.tpe =:= eEv.tpe) && typeIsContained.isEmpty) {
        c.abort(caseAst.pos, s"The pattern of a type ${caseAst.pat.tpe} is not defined in the type list with types ${typesInListAsStr.mkString(", ")}.")
      }
//      println("case " + caseAst.pat + " is valid in " + typeList + " proved by " + typeIsContained)
      caseAst.body.tpe map {
        case t if t <:< caseAst.pat.tpe => eEv.tpe
        case other => other
      }
    }
    val patternMatchRes = lub(casesMappedTypes)
    //    println(s"Lubbing $casesMappedTypes == $patternMatchRes")
    q"(${c.prefix.tree}.unsafeValue match { case ..$casesAst }).asInstanceOf[$patternMatchRes]"
  }
  //  implicit def anyToOneOf[E, TL <: TypeList](e: E)(implicit contained: Contained[TL, E]) = new OneOf[E, TL](e)
}
