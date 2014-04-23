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
import scala.reflect.api.{ TypeTags, Types}

trait TypeList {
  type Head
  type Tail <: TypeList
}

object TypeList {
  private[TypeList] val typeListFullName = scala.reflect.runtime.universe.typeTag[::[_, _]].tpe.typeSymbol.fullName
  private[TypeList] val typeNilFullName = scala.reflect.runtime.universe.typeTag[TypeNil].tpe.typeSymbol.fullName
  def describe[TL <: TypeList](tl: TypeTags#WeakTypeTag[TL]): List[String] = {
    val internalUniverse = tl.mirror.universe.asInstanceOf[scala.reflect.internal.SymbolTable]
    import internalUniverse._
    val consSymbol = weakTypeTag[::[_,_]].tpe.typeSymbol
    val typeListSymbol = typeOf[TypeList].typeSymbol
    def disect(tpe: Type): List[String] = {
      tpe match {
//        case TypeRef(prefix, sym, args) if sym.fullName == typeListFullName => println("going through a"); args.flatMap(disect)
        case TypeRef(prefix, sym, args) if sym.baseClasses.contains(consSymbol) => /*println("going through b"); */
          if (args.nonEmpty) args flatMap disect
          else disect(sym.typeSignature)
        case typeNil if typeNil.toString == typeNilFullName => Nil
        case TypeRef(RefinedType(parents, decls), sym, args) =>
          sym match {
            case alias: AliasTypeSymbol => disect(normalizeAliases(alias.typeSignature))
            case other => /*println(s"No idea so: $other - ${other.getClass}"); *//*no idea so...*/List(sym.toString)
          }
        case TypeRef(prefix, sym, args) if sym.baseClasses.contains(typeListSymbol) && prefix.memberInfo(sym) != NoType => // might be of the form SomeClass[reification]#SomeType
          prefix.memberInfo(sym) match {
            case t@TypeBounds(lo, hi) => disect(hi)
            case _ => List("Unknown: " + tpe.toString)
          }
        case other =>
          /*other match {
           case TypeRef(p, s, a) => println(s"TypeRef($p, $s, $a) -  symbolFullName: ${s.fullName} : ${s.typeSignature.getClass}")
           println(s"Member: " + p.memberInfo(s))
           case _ =>
           }
           println(s"Other? $other - ${other.getClass}"); */List(other.toString)
      }
    }
    disect(tl.tpe.asInstanceOf[Type])
  }
}
	
trait ::[A, T <: TypeList] extends TypeList {
  type Head = A
  type Tail = T
}

final class TypeNil extends TypeList {
  type Head = Nothing
  type Tail = TypeNil
}

//sealed trait ErrorSelectorImplicits[Selector[TL <: TypeList, A]] {
//  self: TypeSelectorImplicits[Selector] =>
//  implicit def noContains[H, T <: TypeList, A]: Selector[H :: T, A] = macro TypeSelectorImplicits.noSelectorErrorImpl[Selector, H :: T, A]
//}
sealed trait LowPrioritySelectorImplicits[Selector[TL <: TypeList, A]] {
  self: TypeSelectorImplicits[Selector] =>
  implicit def tailContains[H, T <: TypeList, A](implicit c: Selector[T, A]): Selector[H :: T, A] = impl
}
trait TypeSelectorImplicits[Selector[TL <: TypeList, A]] extends LowPrioritySelectorImplicits[Selector] {
  implicit def containsExactly[H, T <: TypeList, A](implicit x: A <:< H): Selector[H :: T, A] = impl
  def impl[H <: TypeList, A]: Selector[H, A] = null.asInstanceOf[Selector[H, A]]
}
object TypeSelectorImplicits {
//  import scala.reflect.macros.Context
//  def noSelectorErrorImpl[Selector[_ <: TypeList, _], T <: TypeList: c.WeakTypeTag, A: c.WeakTypeTag](c: Context): c.Expr[Selector[T, A]] = {
//    c.abort(c.enclosingPosition, "EEEXXXPLOOOOOOOOOTIOOON!")
//  }
}

@annotation.implicitNotFound("${E} is not contained in ${TL}")
trait Contained[-TL <: TypeList, -E] //declared TL as contravariant, to deal with java generics. E is contravariant because it makes sense.
object Contained extends TypeSelectorImplicits[Contained]

class OneOf[+E, TL <: TypeList](val value: E)(implicit contained: Contained[TL, E]) {
  override def toString = "OneOf(" + value + ")"
  def valueAs[T](implicit contained: Contained[TL, T]) = value.asInstanceOf[T]
  def dispatch(f: PartialFunction[E, Any]): Any = macro OneOf.dispatchMacro[E, TL]

}
object OneOf {
  import scala.reflect.macros.whitebox.Context
  def dispatchMacro[E, TL <: TypeList](c: Context { type PrefixType = OneOf[E, TL]})(
    f: c.Expr[PartialFunction[E, Any]])(implicit eEv: c.WeakTypeTag[E], tlEv: c.WeakTypeTag[TL]): c.Expr[Any] = {
    import c.universe._
    val typeList = tlEv.tpe.baseType(c.typeOf[_ :: _].typeSymbol)
    //get the match AST corresponding to the partial function
    val casesAst = f.tree.collect {
      case DefDef(mods, name, tparams, vparamss, tpt, Match(selector, cases)) if name.toString == "applyOrElse" => cases.init //discard last case because thats the partial function synthetic default case
    }.flatten

    val typesInListAsStr = TypeList.describe(tlEv)
    //validate that every pattern is contained in the type list and accumulate the case result type
    val TypeRef(pre, sym, _) = typeOf[Contained[TypeNil, Any]]
    val casesMappedTypes = casesAst map { caseAst =>
//      println(s"Testing $caseAst, pat type: ${caseAst.pat.tpe}, body type: ${caseAst.body.tpe}")
      tq"_root_.uy.com.netlabs.luthier.typelist.Contained[$typeList, ${caseAst.pat.tpe}]".tpe
      if (!(caseAst.pat.tpe =:= eEv.tpe) && c.inferImplicitValue(c.internal.typeRef(pre, sym, List(typeList, caseAst.pat.tpe))).isEmpty) {
        c.abort(caseAst.pos, s"The pattern of a type ${caseAst.pat.tpe} is not defined in the type list with types ${typesInListAsStr.mkString(", ")}.")
      }
      caseAst.body.tpe map {
        case t if t <:< caseAst.pat.tpe => eEv.tpe
        case other => other
      }
    }
    val patternMatchRes = lub(casesMappedTypes)
//    println(s"Lubbing $casesMappedTypes == $patternMatchRes")
    val selector = reify {c.prefix.splice.value}.tree
    val resMatch = c.Expr[Any](Match(selector, casesAst))
    def res[LUB](implicit ev: c.WeakTypeTag[LUB]) = reify {(resMatch.splice).asInstanceOf[LUB]}
    val r = res(c.WeakTypeTag(patternMatchRes))
//    println(r)
    r
  }
//  implicit def anyToOneOf[E, TL <: TypeList](e: E)(implicit contained: Contained[TL, E]) = new OneOf[E, TL](e)
}
