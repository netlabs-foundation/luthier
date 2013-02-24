package uy.com.netlabs.luthier.veditor

import scala.tools.nsc.ast.TreeBrowsers
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.{Reporter, StoreReporter}
import scala.reflect.io.AbstractFile
import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.interactive.Global

import uy.com.netlabs.luthier.Flow

import java.nio.file.{Path}

/**
 * A FlowAnalyzer uses the passed classpath to create a compiler instance, with
 * which it obtains the structure of the flow and lift it into the objects
 * used for the visual representation.
 */
class FlowAnalyzer(val classpath: Seq[String]) {

  val compilerSettings = new Settings()
  compilerSettings.YmethodInfer.value = true
  println("Using classpath:\n\t" + classpath.mkString("\n\t"))
  compilerSettings.classpath.value = classpath.mkString(java.io.File.pathSeparator)
  val reporter = new StoreReporter
  val compiler = new Global(compilerSettings, reporter, "FlowAnalyzer")
  import compiler._
  
  private[this] lazy val FlowType = compiler.typeOf[Flow]
  
  def analyze(file: Path, maxWaitTimeout: Long = 10000) {
    val response = new Response[Tree]()
    val sourceFile = new BatchSourceFile(AbstractFile.getFile(file.toFile))
    compiler.askLoadedTyped(sourceFile, response)
    val res = response.get(maxWaitTimeout) map (_.left.map {tree =>
        println(Console.CYAN + tree + Console.RESET)
        val flowsInstantiationTrees = tree collect {
          case t@Apply(Select(New(Ident(name)), nme.CONSTRUCTOR), args) if t.tpe <:< FlowType => t
        }
        //obtain the trees for the types of the instantiated flows
        val flowClasses = flowsInstantiationTrees map {f => 
          val typeAtLoc = new Response[Tree]
          compiler.askTypeAt(f.symbol.owner.pos, typeAtLoc)
          typeAtLoc.get.left.get
        }
        flowClasses foreach (t => println(showRaw(t) + "----------------\n\n\n"))
        
        //create FlowDescriptors from the classes
        val descriptors = flowClasses map {
          case t@Template(parents, self, body) =>
//            TreeDescriptor.describe(t, compiler)
            val Seq((flowName, endpoint, pattern)) = body.map (_ collect {
                case t@Apply( //flow constructor
                    Apply(//first parameter list
                      Apply(Select(Super(_, tpnme.EMPTY), nme.CONSTRUCTOR), List(flowName)), //flow name
                      List(Apply(sym, args)) //second parameter list, endpoint factory argument
                    ),
                    List(TypeApply(Select(_, pattern), _))) => //third parameter list, exchange pattern
                  
                  (flowName, sym.symbol.owner, pattern)
              }).flatten
            val logicResult = t.tpe.member(newTypeName("LogicResult"))
            val responseType = if (logicResult.typeSignature =:= typeOf[Unit]) None
            else Some(logicResult.typeSignature.toString)
            FlowDescriptor(flowName.toString, 
                           endpoint.typeConstructor.toString, 
                           pattern.decoded,
                           t.tpe.member(newTypeName("InboundEndpointTpe")).toString,
                           responseType,
                           t.pos)
        }
        
        descriptors foreach println
        descriptors
      })
    reporter.infos foreach (i => println(Console.YELLOW + i + Console.YELLOW))
  }
}
