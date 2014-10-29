package uy.com.netlabs.luthier.typelist

import language.higherKinds
import scala.reflect.macros.blackbox.Context
import shapeless.HList

private[typelist] class Macros[C <: Context](val c: C) {
  import c.universe._

  def matchImpl[T, TL <: HList](value: c.Tree)(partialFunction: c.Tree)(valueType: c.Type, hlistType: c.Type): c.Tree = {
    val casesAst = partialFunction.collect {
      case q"{case ..$cases}" => cases
    }.flatten

    val typeListDescriptor = new TypeList.TypeListDescriptor(c.universe)
    val typesInListAsStr = typeListDescriptor.describeAsString(c.TypeTag(hlistType).asInstanceOf[typeListDescriptor.universe.TypeTag[TL]])

    val TypeRef(pre, sym, _) = typeOf[Supported[_, _]].typeConstructor
    //validate that every pattern is contained in the type list and accumulate the case result type
    val casesMappedTypes = casesAst map { caseAst =>
      //      println(s"Testing $caseAst, pat type: ${caseAst.pat.tpe}, body type: ${caseAst.body.tpe}")
      val typeToInfer = c.typecheck(tq"_root_.uy.com.netlabs.luthier.typelist.Supported[${caseAst.pat.tpe}, $hlistType]", c.TYPEmode).tpe
      lazy val typeIsContained = c.inferImplicitValue(typeToInfer, withMacrosDisabled = true)
      if (!(caseAst.pat.tpe =:= valueType) && typeIsContained.isEmpty) {
        c.abort(caseAst.pos, s"The pattern of a type ${caseAst.pat.tpe} is not defined in the type list with types ${typesInListAsStr.mkString(", ")}.")
      }
      //      println("case " + caseAst.pat + " is valid in " + typeList + " proved by " + typeIsContained)
      caseAst.body.tpe map {
        case t if t <:< caseAst.pat.tpe => valueType
        case other => other
      }
    }
    val patternMatchRes = lub(casesMappedTypes)
    //    println(s"Lubbing $casesMappedTypes == $patternMatchRes")
    q"($value match { case ..$casesAst }).asInstanceOf[$patternMatchRes]"
  }

  def noSelectorErrorImpl[Selector[_ <: HList, _], T <: HList, A](
    selector: Option[Type], element: Type, typelist: Type): Nothing = {
    import c.universe._
    // detect the typelist that we are searching a selector for
    //    val List(typelist, element) = c.macroApplication.tpe.typeArgs.map(_.dealias)
    val typelistDescriptor = new TypeList.TypeListDescriptor(c.universe)
    val types = typelistDescriptor.describeAsString(c.TypeTag(typelist).asInstanceOf[typelistDescriptor.universe.TypeTag[T]])
    val typesDescription = types.mkString("[\n  ", ",\n  ", "\n]")

    val failError = selector.flatMap(selector => selector.typeSymbol.annotations.find(a => a.tree.tpe =:= typeOf[scala.annotation.implicitNotFound]) map { a =>
      val List(Literal(Constant(msg: String))) = a.tree.children.tail
      //      println(s"Selector $selector's type param checking for HList: ${selector.typeParams}")
      val (List(tl), List(e)) = selector.typeParams partition { tp => tp.asType.toType <:< typeOf[HList] }
      msg.replace("${" + e.name.toString + '}', element.dealias.toString).
        replace("${" + tl.name.toString + '}', typesDescription)
    }) getOrElse s"Type ${element} was not found in typelist $typesDescription"

    c.abort(c.enclosingPosition, failError)

  }
}