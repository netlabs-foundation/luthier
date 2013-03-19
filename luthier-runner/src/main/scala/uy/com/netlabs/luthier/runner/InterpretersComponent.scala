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

    def bindObject(name: String, classDecl: String, obj: Any) {
      boundObjects :+= (name, classDecl, obj)
      interpreters foreach (i => require(i.bind(name, classDecl, obj) == IR.Success))
    }
    def addImports(imports: String*) {
      this.imports ++= imports
      interpreters foreach (i => require(i.addImports(imports:_*) == IR.Success))
    }

    def newInterpreter(name: String, exponseInterpreter: Boolean = false): IMain = {
      logger.info(s"Initializing compiler for $name")
      val res = new IMain(compilerSettings)
      interpreters :+= res
      val done = Promise[Unit]()
      res.initialize {
        done.success(())
      }
      done.future.onSuccess {case _ =>
          Try {
            res.beQuietDuring {
              for (i <- imports) res.addImports(i)
              for ((name, classDecl, obj) <- boundObjects) {
                res.bind(name, classDecl, obj)
              }
              if (exponseInterpreter) res.bind("interpreter", res)
            }
            logger.info(s"Done initializing compiler for $name")
          }.recover {case ex: Throwable => logger.error(s"Failed to initialize interpreter for $name", ex)}
      }
      res
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
