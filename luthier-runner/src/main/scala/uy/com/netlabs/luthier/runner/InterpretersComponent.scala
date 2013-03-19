package uy.com.netlabs.luthier.runner

import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.{ IMain, IR }
import scala.util._
import scala.concurrent._, duration._
import org.slf4j._

trait InterpretersComponent {

  protected val logger: Logger
  protected val compilerSettings: Settings

  protected object Interpreters {
    @volatile private[this] var boundObjects = Vector.empty[(String, String, Any)]
    @volatile private[this] var imports = Vector.empty[String]
    @volatile private[this] var interpreters = Vector.empty[IMain]
    private[this] implicit val postInitializerExecutor = ExecutionContext.fromExecutor(java.util.concurrent.Executors.newFixedThreadPool(1))

    /**
     * Performs a bind ignoring the actual class, and then performs a cast using interpret to avoid
     * problems with the clean classloader
     */
    private def bindValue(compiler: IMain, name: String, classDecl: String, obj: Any) {
      val fn = compiler.naming.freshInternalVarName
      require(compiler.bind(fn, "Any", obj) == IR.Success, s"Could not bind $name")
      require(compiler.interpret(s"val $name = $fn.asInstanceOf[$classDecl]") == IR.Success, s"Could not bind $name")
    }

    def bindObject(name: String, classDecl: String, obj: Any) {
      boundObjects :+= (name, classDecl, obj)
      interpreters foreach (i => bindValue(i, name, classDecl, obj))
    }
    def addImports(imports: String*) {
      this.imports ++= imports
      interpreters foreach (i => require(i.addImports(imports:_*) == IR.Success))
    }

    def newInterpreter(name: String, exposeInterpreter: Boolean = false): Future[IMain] = {
      logger.info(s"Initializing compiler for $name")
      val res = new IMain(compilerSettings) {
        override val parentClassLoader = new ClassLoader(null) {}
      }
      interpreters :+= res
      val done = Promise[IMain]()
      res.initialize {
        done.success(res)
      }
      done.future.map {res =>
        res.beQuietDuring {
          for (i <- imports) res.addImports(i)
          for ((name, classDecl, obj) <- boundObjects) {
            bindValue(res, name, classDecl, obj)
          }
          if (exposeInterpreter) {
            bindValue(res, "interpreter", "scala.tools.nsc.interpreter.IMain", res)
          }
          res
        }
      }
    }

    def disposeAll() {
      interpreters foreach (i => Try(i.close()))
    }
  }

  /**
   * Binds the given object into the compiler instance, so its accessible as a global variable from the
   * flows
   */
  def bindObject(name: String, classDecl: String, obj: Any) {
    Interpreters.bindObject(name, classDecl, obj)
  }
}
