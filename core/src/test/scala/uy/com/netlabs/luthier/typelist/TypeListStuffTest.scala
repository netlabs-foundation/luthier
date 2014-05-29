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
package typelist

import org.scalatest.FunSpec

class TypeListStuffTest extends FunSpec {

  val descriptor = new TypeList.TypeListDescriptor(scala.reflect.runtime.universe)
  type Aliased = List[String]

  trait TraitA {
    type B = String :: Long :: List[String] :: TypeNil
  }
  import descriptor.universe._
  describe("TypeListDescriptor") {
    it("should produce the list of types") {
      assert(descriptor.describe[Int :: Long :: String :: TypeNil].size === 3)
    }
    it("Should resolve aliases") {
      assert(descriptor.describe[Aliased :: TypeNil].head === typeOf[Aliased].dealias)
    }
    it("It should handle reified types") {
      val List(str, lng, listStr) = descriptor.describe[TraitA#B]
      assert(str =:= typeOf[String])
      assert(lng =:= typeOf[Long])
      assert(listStr =:= typeOf[List[String]])
    }
    it("should handle reified types 2") {
      val List(a, b) = descriptor.describe[({ type TL = Int :: Option[Long] :: TypeNil })#TL]
      assert(a =:= typeOf[Int])
      assert(b =:= typeOf[Option[Long]])
    }
  }
  def myMethod[A](implicit ev: Contained[String :: Int :: TypeNil, A]) = "good"
  
  describe("TypeSelectorImplicits") {
    it("should fail for direct implicit searchs of contained") {
      assertTypeError("implicitly[Contained[String :: Int :: TypeNil, Long]]")
    }
    it("should fail for direct implicit search of TypeSupportedByTransport") {
      assertTypeError("implicitly[TypeSupportedByTransport[String :: Int :: TypeNil, Long]]")
    }
    it("should fail for methods requiring a typeselector") {
      assertTypeError("myMethod[Long]")
    }
    it("should fail for classes constructors requiring a typeselector") {
      assertTypeError("""new OneOf[String, Int :: Long :: TypeNil]("a")""")
    }
  }

  type TL = Int :: Long :: String :: TypeNil
  def withOnOf[T](v: OneOf[T, TL]): Seq[T] = {
    v.dispatch {
      case i: Int => List(i + 3)
      case p: Long => Vector(p + 5)
      case foo => Seq(foo)
    }
  }
  describe("OneOf") {
    they("should dispatch values based on the type") {
      assert(withOnOf(new OneOf[Int, TL](3)) === List(6))
      assert(withOnOf(new OneOf[Long, TL](10l)) === List(15l))
      assert(withOnOf(new OneOf[String, TL]("foo")) === List("foo"))
    }
  }
}
