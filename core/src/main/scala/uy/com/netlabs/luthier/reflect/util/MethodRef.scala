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