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
import scala.annotation.implicitNotFound

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

    def describeAsString[TL <: HList](implicit tl: TypeTag[TL]): List[String] = describe[TL].map(_.toString.replace("uy.com.netlabs.luthier.typelist.", ""))
  }
}

@implicitNotFound("Could not prove that type ${T} can be a ${T2}")
trait CanBe[T, T2]
object CanBe {
  implicit def equalsCanBe[T]: CanBe[T, T] = null
  implicit def bytesCanBeBoxed: CanBe[Byte, java.lang.Byte] = null
  implicit def shortsCanBeBoxed: CanBe[Short, java.lang.Short] = null
  implicit def intsCanBeBoxed: CanBe[Int, java.lang.Integer] = null
  implicit def longsCanBeBoxed: CanBe[Long, java.lang.Long] = null
  implicit def floatsCanBeBoxed: CanBe[Float, java.lang.Float] = null
  implicit def doublesCanBeBoxed: CanBe[Double, java.lang.Double] = null
  implicit def charsCanBeBoxed: CanBe[Char, java.lang.Character] = null
  implicit def BytesCanBeUnboxed: CanBe[java.lang.Byte, Byte] = null
  implicit def ShortsCanUnboxed: CanBe[java.lang.Short, Short] = null
  implicit def IntsCanBeUnboxed: CanBe[java.lang.Integer, Int] = null
  implicit def LongsCanUnbeBoxed: CanBe[java.lang.Long, Long] = null
  implicit def FloatsCanBeUnboxed: CanBe[java.lang.Float, Float] = null
  implicit def DoublesCanBeUnboxed: CanBe[java.lang.Double, Double] = null
  implicit def CharsCanBeUnboxed: CanBe[java.lang.Character, Char] = null
}

/**
 * Special type that accomplishes negation.
 * Usage as (for example is you want to exclude Nothing):
 * {{{ def myMethod[T](implicit ev: CanBe[T, T IsNot Nothing]) = ??? }}}
 */
trait Not[U]
object Not {
  implicit def tCanBeNotU[T, U]: CanBe[T, Not[U]] = null
  implicit def tCannotBeT[T]: CanBe[T, Not[T]] = null
  implicit def tCannotBeT2[T]: CanBe[T, Not[T]] = null
  implicit def tCannotBeU[T, U](implicit ev: CanBe[T, U]): CanBe[T, Not[U]] = null
  implicit def tCannotBeU2[T, U](implicit ev: CanBe[T, U]): CanBe[T, Not[U]] = null
}

/**
 * Special type that accomplishes covariance with regard to two types
 */
trait <::<[U]
object <::< {
  implicit def tIsCoWithU[T <: U, U]: CanBe[T, <::<[U]] = null
}

/**
 * Special type that accomplishes contravariance with regard to two types
 */
trait >::>[U]
object >::> {
  implicit def tIsContraWithU[T >: U, U]: CanBe[T, >::>[U]] = null
}

@implicitNotFound("${T} is not compatible with the specified typelist:\n${H}")
trait Supported[T, H <: HList]
object Supported extends LowPrioSupportedImplicits {
  implicit def headIsSupported[T, H, HL <: HList](implicit canBe: CanBe[T, H]): Supported[T, H :: HL] = null
  implicit def tailIsSupported[T, H, HL <: HList](implicit ev: Supported[T, HL]): Supported[T, H :: HL] = null

  class SupportedOps[T](val t: T) extends AnyVal {
    def math_[H <: HList](pf: PartialFunction[T, Any])(implicit ev: Supported[T, H]): Any = macro supportedDispatchBasedOnValue[T, H]
  }

  def supportedDispatchBasedOnValue[T, H <: HList](c: scala.reflect.macros.whitebox.Context { type PrefixType = SupportedOps[T] })(pf: c.Tree)(ev: c.Tree)(implicit tEv: c.WeakTypeTag[T], hEv: c.WeakTypeTag[H]): c.Tree = {
    import c.universe._
    new Macros[c.type](c).matchImpl(q"${c.prefix.tree}.t")(pf)(tEv.tpe, hEv.tpe)
  }
  def notSupportedImpl[T, HL <: HList](c: scala.reflect.macros.blackbox.Context)(implicit tEv: c.WeakTypeTag[T], hlEv: c.WeakTypeTag[HL]): c.Tree = {
    new Macros[c.type](c).noSelectorErrorImpl(None, tEv.tpe, hlEv.tpe)
  }
}
private[typelist] trait LowPrioSupportedImplicits {
  implicit def notSupported[T, HL <: HList]: Supported[T, HL] = macro Supported.notSupportedImpl[T, HL]

}

sealed trait ErrorSelectorImplicits[Selector[TL <: HList, A]] { self: TypeSelectorImplicits[Selector] =>

  // aux is a hack to force the type inferencer to bind the HList before the macro, otherwise given its covariant it will pass in the most general shapeless.HList
  implicit def noContains[T <: HList, A](implicit aux: TypeSelectorImplicits.Aux[T]): Selector[T, A] = macro TypeSelectorImplicits.noSelectorErrorImpl[Selector, T, A]
}
trait TypeSelectorImplicits[Selector[TL <: HList, A]] extends ErrorSelectorImplicits[Selector] {
  implicit def select[H <: HList, T](implicit supp: Supported[T, H]): Selector[H, T] = null.asInstanceOf[Selector[H, T]]
}
object TypeSelectorImplicits {
  import scala.reflect.macros.blackbox.Context
  sealed trait Aux[T]
  implicit def aux[T]: Aux[T] = null

  def noSelectorErrorImpl[Selector[_ <: HList, _], T <: HList, A](c: Context)(aux: c.Tree)(
    implicit selectorEv: c.WeakTypeTag[Selector[_, _]], aEv: c.WeakTypeTag[A], tEv: c.WeakTypeTag[T]): c.Tree = {
    import c.universe._
    val List(typelist, element) = c.macroApplication.tpe.typeArgs.map(_.dealias)
    new Macros[c.type](c).noSelectorErrorImpl(Some(selectorEv.tpe), element, typelist)
  }
}

/**
 * Simple generic contained typeselector, note that it doesn't add much with respect to simply using Supported.
 */
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
  implicit def typeListHasSizeOne[H]: TypeListHasSizeOne[H :: HNil] { type TheType = H } = null

  implicit def oneOfSingleType2Type[H](o: OneOf[_, H :: HNil]): H = o.value

  import scala.reflect.macros.whitebox.Context
  def dispatchMacro[E, TL <: HList](c: Context { type PrefixType = OneOf[E, TL] })(
    f: c.Tree)(implicit eEv: c.WeakTypeTag[E], tlEv: c.WeakTypeTag[TL]): c.Tree = {
    import c.universe._
    new Macros[c.type](c).matchImpl(q"${c.prefix.tree}.unsafeValue")(f)(eEv.tpe.dealias, tlEv.tpe.dealias)
  }
  //  implicit def anyToOneOf[E, TL <: TypeList](e: E)(implicit contained: Contained[TL, E]) = new OneOf[E, TL](e)
}
