package uy.com.netlabs.luthier.veditor

import scala.reflect.api.Trees

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
        catch {case e => e.printStackTrace}
      }
    }.start()
  }
  
  class RootNode(root: scene.control.TreeItem[Any]) extends scene.layout.BorderPane {
    val treeView = new scene.control.TreeView[Any](root)
    setCenter(treeView)
  }
  
  private[this] val rootStage = new scala.concurrent.SyncVar[Stage]()
  
  
  def describe(tree: Trees#TreeApi, universe: Trees) {
    import universe._
    initJfx
    def treeItem(a: Any) = new scene.control.TreeItem(a)
    def listTreeItem(name: String, a: Seq[scene.control.TreeItem[_]]) = {
      val res = new scene.control.TreeItem[Any](name)
      a foreach (t => res.getChildren.add(t.asInstanceOf[scene.control.TreeItem[Any]]))
      res
    }
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
        case ReferenceToBoxed(ident) =>
          res.getChildren.add(toJfxTreeItem(ident))
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
}
