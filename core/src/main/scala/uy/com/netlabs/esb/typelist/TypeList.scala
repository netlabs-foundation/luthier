package uy.com.netlabs.esb.typelist

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