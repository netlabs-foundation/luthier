package uy.com.netlabs.esb.typelist

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
    def disect(tpe: Type): List[String] = {
      tpe.asInstanceOf[Type] match {
        case TypeRef(prefix, sym, args) if sym.fullName == typeListFullName => args.flatMap(disect)
        case typeNil if typeNil.toString == typeNilFullName => Nil
        case TypeRef(RefinedType(parents, decls), sym, args) =>
          sym match {
            case alias: AliasTypeSymbol => disect(deAlias(alias.typeSignature))
            case other => /*no idea so...*/List(sym.toString)
          }
        case other => List(other.toString)
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

sealed trait ErrorSelectorImplicits[Selector[TL <: TypeList, A]] {
  self: TypeSelectorImplicits[Selector] =>
  implicit def noContains[H, T <: TypeList, A]: Selector[H :: T, A] = macro TypeSelectorImplicits.noSelectorErrorImpl[Selector, H :: T, A]
}
sealed trait LowPrioritySelectorImplicits[Selector[TL <: TypeList, A]] {
  self: TypeSelectorImplicits[Selector] =>
  implicit def tailContains[H, T <: TypeList, A](implicit c: Selector[T, A]): Selector[H :: T, A] = impl
}
trait TypeSelectorImplicits[Selector[TL <: TypeList, A]] extends LowPrioritySelectorImplicits[Selector] {
  implicit def containsExactly[H, T <: TypeList, A](implicit x: A <:< H): Selector[H :: T, A] = impl
  def impl[H <: TypeList, A]: Selector[H, A] = null.asInstanceOf[Selector[H, A]]
}
object TypeSelectorImplicits {
  import scala.reflect.macros.Context
  def noSelectorErrorImpl[Selector[_ <: TypeList, _], T <: TypeList: c.WeakTypeTag, A: c.WeakTypeTag](c: Context): c.Expr[Selector[T, A]] = {
    c.abort(c.enclosingPosition, "EEEXXXPLOOOOOOOOOTIOOON!")
  }
}

@annotation.implicitNotFound("${E} is not contained in ${TL}")
trait Contained[TL <: TypeList, E]
object Contained extends TypeSelectorImplicits[Contained]

class OneOf[+E, TL <: TypeList](val value: E)(implicit contained: Contained[TL, E]) {
  override def toString = "OneOf(" + value + ")"
}
object OneOf {
  implicit def anyToOneOf[E, TL <: TypeList](e: E)(implicit contained: Contained[TL, E]) = new OneOf[E, TL](e)
}