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
package uy.com.netlabs.luthier.veditor

import scala.util._
import scala.reflect.api.{ Trees, Types, Symbols }

import javafx._, application.Application, stage.Stage

object TreeDescriptor {

  class Initializer extends Application {

    def start(primaryStage) {
      primaryStage.centerOnScreen()
      rootStage put primaryStage
    }
  }
  private[this] lazy val initJfx = {
    new Thread {
      override def run {
        try Application.launch(classOf[Initializer])
        catch { case e => e.printStackTrace }
      }
    }.start()
  }

  class RootNode(root: scene.control.TreeItem[Any]) extends scene.layout.BorderPane {
    val treeView = new scene.control.TreeView[Any](root)
    setCenter(treeView)
  }

  private[this] val rootStage = new scala.concurrent.SyncVar[Stage]()

  def LazyTreeItem(value: Any, children: => Iterable[scene.control.TreeItem[_]]) = new scene.control.TreeItem[Any](value) {
    def childrenEval = Try { //since this happens in the jfx thread, we must never throw exception
      val res = children.toSeq
      super.getChildren().setAll(res.asInstanceOf[Seq[scene.control.TreeItem[Any]]]: _*);
      res
    }.recover {case ex => println("Exception occurred while expanding node:" + ex); Seq.empty}.get

    override def getChildren(): javafx.collections.ObservableList[scene.control.TreeItem[Any]] = {
      childrenEval
      return super.getChildren();
    }

    override def isLeaf(): Boolean = {
      return childrenEval.isEmpty;
    }

  }

  def describe(tree: Trees#TreeApi, universe: Trees) {
    import universe._
    initJfx
    def treeItem(a: Any) = new scene.control.TreeItem(a)
    def listTreeItem(name: String, a: => Seq[scene.control.TreeItem[_]]) = LazyTreeItem(name, a)
    def toJfxTreeItem(tree: Trees#TreeApi): scene.control.TreeItem[Any] = {
      val res = treeItem(tree.getClass.getName)
      tree match {
        case Alternative(trees) =>
          res.getChildren.add(listTreeItem("trees", trees map toJfxTreeItem))
        case Annotated(annot, arg) =>
          res.getChildren.add(toJfxTreeItem(annot))
          res.getChildren.add(toJfxTreeItem(arg))
        case AppliedTypeTree(tpt, args) =>
          res.getChildren.add(toJfxTreeItem(tpt))
          res.getChildren.add(listTreeItem("args", args map toJfxTreeItem))
        case Apply(sym, args) =>
          res.getChildren.add(toJfxTreeItem(sym))
          res.getChildren.add(listTreeItem("args", args map toJfxTreeItem))
        case Assign(lhs, rhs) =>
          res.getChildren.add(toJfxTreeItem(lhs))
          res.getChildren.add(toJfxTreeItem(rhs))
        case AssignOrNamedArg(lhs, rhs) =>
          res.getChildren.add(toJfxTreeItem(lhs))
          res.getChildren.add(toJfxTreeItem(rhs))
        case Bind(sym, body) =>
          res.getChildren.add(treeItem(sym))
          res.getChildren.add(toJfxTreeItem(body))
        case Block(stats, expr) =>
          res.getChildren.add(listTreeItem("stats", stats map toJfxTreeItem))
          res.getChildren.add(toJfxTreeItem(expr))
        case CaseDef(pat, guard, body) =>
          res.getChildren.add(toJfxTreeItem(pat))
          res.getChildren.add(toJfxTreeItem(guard))
          res.getChildren.add(toJfxTreeItem(body))
        case ClassDef(mods, name, tparams, impl) =>
          res.getChildren.add(treeItem(mods))
          res.getChildren.add(treeItem(name))
          res.getChildren.add(listTreeItem("tparams", tparams map treeItem))
          res.getChildren.add(toJfxTreeItem(impl))
        case CompoundTypeTree(templ) =>
          res.getChildren.add(toJfxTreeItem(templ))
        case DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
          res.getChildren.add(treeItem(mods))
          res.getChildren.add(treeItem(name))
          res.getChildren.add(listTreeItem("tparams", tparams map treeItem))
          res.getChildren.add(listTreeItem("vparamss", vparamss map (e => listTreeItem("vaparams", e map toJfxTreeItem))))
          res.getChildren.add(toJfxTreeItem(tpt))
          res.getChildren.add(toJfxTreeItem(rhs))
        case ExistentialTypeTree(tpt, whereClauses) =>
          res.getChildren.add(toJfxTreeItem(tpt))
          res.getChildren.add(listTreeItem("whereClauses", whereClauses map toJfxTreeItem))
        case Function(vparams, body) =>
          res.getChildren.add(listTreeItem("vparams", vparams map toJfxTreeItem))
          res.getChildren.add(toJfxTreeItem(body))
        case Ident(name) =>
          res.getChildren.add(treeItem(name))
        case If(cond, thenp, elsep) =>
          res.getChildren.add(toJfxTreeItem(cond))
          res.getChildren.add(toJfxTreeItem(thenp))
          res.getChildren.add(toJfxTreeItem(elsep))
        case Import(expr, selector) =>
          res.getChildren.add(toJfxTreeItem(expr))
          res.getChildren.add(listTreeItem("selector", selector map treeItem))
        case Literal(value) =>
          res.getChildren.add(treeItem(value))
        case LabelDef(sym, params, rhs) =>
          res.getChildren.add(treeItem(sym))
          res.getChildren.add(listTreeItem("params", params map treeItem))
          res.getChildren.add(toJfxTreeItem(rhs))
        case Match(selector, cases) =>
          res.getChildren.add(toJfxTreeItem(selector))
          res.getChildren.add(listTreeItem("cases", cases map toJfxTreeItem))
        case ModuleDef(mods, name, impl) =>
          res.getChildren.add(treeItem(mods))
          res.getChildren.add(treeItem(name))
          res.getChildren.add(toJfxTreeItem(impl))
        case New(tpt) =>
          res.getChildren.add(toJfxTreeItem(tpt))
        case PackageDef(pid, stats) =>
          res.getChildren.add(toJfxTreeItem(pid))
          res.getChildren.add(listTreeItem("stats", stats map toJfxTreeItem))
        case Select(qual, name) =>
          res.getChildren.add(toJfxTreeItem(qual))
          res.getChildren.add(treeItem(name))
        case SelectFromTypeTree(qual, name) =>
          res.getChildren.add(toJfxTreeItem(qual))
          res.getChildren.add(treeItem(name))
        case SingletonTypeTree(ref) =>
          res.getChildren.add(toJfxTreeItem(ref))
        case Star(s) =>
          res.getChildren.add(toJfxTreeItem(s))
        case Super(qual, mix) =>
          res.getChildren.add(toJfxTreeItem(qual))
          res.getChildren.add(treeItem(mix))
        case Template(parents, self, body) =>
          res.getChildren.add(listTreeItem("parents", parents map toJfxTreeItem))
          res.getChildren.add(toJfxTreeItem(self))
          res.getChildren.add(listTreeItem("body", body map toJfxTreeItem))
        case This(qual) =>
          res.getChildren.add(treeItem(qual))
        case Throw(expr) =>
          res.getChildren.add(toJfxTreeItem(expr))
        case Try(block, catches, finalizer) =>
          res.getChildren.add(toJfxTreeItem(block))
          res.getChildren.add(listTreeItem("catches", catches map toJfxTreeItem))
          res.getChildren.add(toJfxTreeItem(finalizer))
        case TypeApply(fun, args) =>
          res.getChildren.add(toJfxTreeItem(fun))
          res.getChildren.add(listTreeItem("args", args map toJfxTreeItem))
        case TypeBoundsTree(lo, hi) =>
          res.getChildren.add(toJfxTreeItem(lo))
          res.getChildren.add(toJfxTreeItem(hi))
        case TypeDef(mods, name, tparams, rhs) =>
          res.getChildren.add(treeItem(mods))
          res.getChildren.add(treeItem(name))
          res.getChildren.add(listTreeItem("tparams", tparams map treeItem))
          res.getChildren.add(toJfxTreeItem(rhs))
        case Typed(expr, tpt) =>
          res.getChildren.add(toJfxTreeItem(expr))
          res.getChildren.add(toJfxTreeItem(tpt))
        case UnApply(fun, args) =>
          res.getChildren.add(toJfxTreeItem(fun))
          res.getChildren.add(listTreeItem("args", args map toJfxTreeItem))
        case ValDef(mods, name, tpt, rhs) =>
          res.getChildren.add(treeItem(mods))
          res.getChildren.add(treeItem(name))
          res.getChildren.add(toJfxTreeItem(tpt))
          res.getChildren.add(toJfxTreeItem(rhs))
        case other =>
      }
      val descr = treeItem("toString")
      descr.getChildren.add(treeItem(tree.toString))
      res.getChildren.add(descr)
      res
    }
    val root = toJfxTreeItem(tree)
    rootStage.get
    application.Platform.runLater(new Runnable {
      def run {
        val s = new stage.Stage()
        s.setScene(new scene.Scene(new RootNode(root)))
        s.show()
      }
    })
  }

  def describe(tpe: Types#TypeApi, universe: Types with Symbols) {
    import universe._
    initJfx
    def treeItem(a: Any) = new scene.control.TreeItem(a)
    def listTreeItem(name: String, a: => Iterable[scene.control.TreeItem[_]]) = LazyTreeItem(name, a)
    def symbolToJfxTreeItem(s: Symbols#SymbolApi): scene.control.TreeItem[Any] = LazyTreeItem(s, Seq(toJfxTreeItem(s.typeSignature)))
    def toJfxTreeItem(tpe: Types#TypeApi): scene.control.TreeItem[Any] = {
      val res = treeItem(tpe.getClass.getName)
      tpe match {
        case AnnotatedType(annotations, underlying) =>
          res.getChildren.add(listTreeItem("annotations", annotations map treeItem))
          res.getChildren.add(toJfxTreeItem(underlying))
        case BoundedWildcardType(bounds) =>
          res.getChildren.add(toJfxTreeItem(bounds))
        case ClassInfoType(parents, decls, clazz) =>
          res.getChildren.add(listTreeItem("parents", parents map toJfxTreeItem))
          res.getChildren.add(listTreeItem("decls", decls map symbolToJfxTreeItem))
          res.getChildren.add(symbolToJfxTreeItem(clazz))
        case ConstantType(constant) =>
          res.getChildren.add(treeItem(constant))
        case ExistentialType(quantified, underlying) =>
          res.getChildren.add(listTreeItem("quantified", quantified map symbolToJfxTreeItem))
          res.getChildren.add(toJfxTreeItem(underlying))
        case MethodType(params, respte) =>
          res.getChildren.add(listTreeItem("params", params map symbolToJfxTreeItem))
          res.getChildren.add(toJfxTreeItem(respte))
        case NullaryMethodType(resultType) =>
          res.getChildren.add(toJfxTreeItem(resultType))
        case PolyType(typeParams, resultType) =>
          res.getChildren.add(listTreeItem("typeParams", typeParams map symbolToJfxTreeItem))
          res.getChildren.add(toJfxTreeItem(resultType))
        case RefinedType(parents, decls) =>
          res.getChildren.add(listTreeItem("parents", parents map toJfxTreeItem))
          res.getChildren.add(listTreeItem("decls", decls map symbolToJfxTreeItem))
        case SingleType(pre, sym) =>
          res.getChildren.add(toJfxTreeItem(pre))
          res.getChildren.add(symbolToJfxTreeItem(sym))
        case SingleType(thistpe, supertpe) =>
          res.getChildren.add(toJfxTreeItem(thistpe))
          res.getChildren.add(symbolToJfxTreeItem(supertpe))
        case ThisType(sym) =>
          res.getChildren.add(symbolToJfxTreeItem(sym))
        case TypeBounds(lower, upper) =>
          res.getChildren.add(toJfxTreeItem(lower))
          res.getChildren.add(toJfxTreeItem(upper))
        case TypeRef(pre, sym, args) =>
          res.getChildren.add(toJfxTreeItem(pre))
          res.getChildren.add(symbolToJfxTreeItem(sym))
          res.getChildren.add(listTreeItem("args", args map toJfxTreeItem))
        case other =>
      }
      val descr = treeItem("toString")
      descr.getChildren.add(treeItem(tpe.toString))
      res.getChildren.add(descr)
      res
    }
    val root = toJfxTreeItem(tpe)
    rootStage.get
    application.Platform.runLater(new Runnable {
      def run {
        val s = new stage.Stage()
        s.setScene(new scene.Scene(new RootNode(root)))
        application.Platform.setImplicitExit(true)
        s.show()
      }
    })
  }
}
