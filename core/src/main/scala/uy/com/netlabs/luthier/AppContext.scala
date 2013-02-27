package uy.com.netlabs.luthier

import java.nio.file.{ Path, Paths }
import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext
import com.typesafe.config.{ Config, ConfigFactory }

trait AppContext {
  def name: String
  def rootLocation: Path

  lazy val actorSystem: ActorSystem = createActorSystem()

  protected def createActorSystem(): ActorSystem
}

object AppContext {
  def build(name: String): AppContext = build(name, Paths.get(".")) 
  def build(name: String, rootLocation: Path): AppContext = build(name, rootLocation, ConfigFactory.load(), Thread.currentThread().getContextClassLoader())
  def build(name: String, rootLocation: Path, config: Config): AppContext = build(name, rootLocation, config, Thread.currentThread().getContextClassLoader())
  def build(name: String, rootLocation: Path, classLoader: ClassLoader): AppContext = build(name, rootLocation, ConfigFactory.load(), classLoader)
  def build(name: String, rootLocation: Path, config: Config, classLoader: ClassLoader): AppContext = {
    val n = name
    val rl = rootLocation
    new AppContext {
      val name = n
      val rootLocation = rl
      def createActorSystem = ActorSystem(name.replace(' ', '-').replace('.', '-'), config, classLoader)
    }
  }
}