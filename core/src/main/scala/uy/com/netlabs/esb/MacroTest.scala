package uy.com.netlabs.esb

import language.experimental._
import scala.reflect.macros._

object MacroTest {

  def showMeTheTypesImpl[T: c.WeakTypeTag](c: scala.reflect.macros.Context): c.Expr[Unit] = {
    val tt = implicitly[c.WeakTypeTag[T]]
    println("Type is = " + tt)
    c.universe.reify(())
  }
  
  def showMeTheTypes[T]: Unit = macro showMeTheTypesImpl[T]
  
  def showMeTheCodeImpl(ctx: scala.reflect.macros.Context)(c: ctx.Expr[Any]): ctx.Expr[Unit] = {
    println(ctx.universe.showRaw(c))
    ctx.universe.reify(())
  }
  
  def showMeTheCode(c: Any): Unit = macro showMeTheCodeImpl
  
  def normalMethod = "normalMethod"
  
  def replaceMeWithMethodImpl(c: scala.reflect.macros.Context)() = {
    import c.universe._
    val normalDefTree = reify(normalMethod).tree
    println(showRaw(normalDefTree) + " - " + normalDefTree.pos)
//    val tree = DefDef(Modifiers(), newTermName("myMethod"), List(), List(), TypeTree(), Literal(Constant("hi there")))
//    val e = c.Expr(tree)
//    e
    val t = c.parse("def myMethod = \"Hi there\"")
    t.symbol = c.macroApplication.symbol
//    for (n <- t) n.symbol = c.macroApplication.symbol
    c.Expr(t)
  }
  
  def replaceMeWithMethod() = macro replaceMeWithMethodImpl
  
  
//  class TransformerList[SupportedTypes] {
//    def transform[T](t: T)(implicit acceptance: T <:< SupportedTypes) {
//      
//    }
//  }
//  
//  val t = new TransformerList[String with Serializable with Int]
//  t.transform("lalal")
}