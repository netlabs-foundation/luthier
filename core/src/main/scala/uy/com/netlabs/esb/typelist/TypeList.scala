package uy.com.netlabs.esb.typelist

import language.higherKinds

trait TypeList {
  type Head
  type Tail <: TypeList
}

trait ::[A, T <: TypeList] extends TypeList {
  type Head = A
  type Tail = T
}

final class TypeNil extends TypeList {
  type Head = Nothing
  type Tail = TypeNil
}

trait LowPrioritySelectorImplicits[Selector[TL <: TypeList, A]] { self: TypeSelectorImplicits[Selector] =>
  implicit def tailContains[H, T <: TypeList, A](implicit c: Selector[T, A]): Selector[H :: T, A] = impl
}
trait TypeSelectorImplicits[Selector[TL <: TypeList, A]] extends LowPrioritySelectorImplicits[Selector] {
  implicit def containsExactly[H, T <: TypeList, A](implicit x: A <:< H): Selector[H :: T, A] = impl
  def impl[H <: TypeList, A]: Selector[H, A] = null.asInstanceOf[Selector[H, A]]
}

trait Contained[TL <: TypeList, E]
object Contained extends TypeSelectorImplicits[Contained]

class OneOf[E, TL <: TypeList](elem: E)(implicit contained: Contained[TL, E])
object OneOf {
  implicit def anyToOneOf[E, TL <: TypeList](e: E)(implicit contained: Contained[TL, E]) = new OneOf[E, TL](e)
}