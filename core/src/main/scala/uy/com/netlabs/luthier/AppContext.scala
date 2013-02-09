package uy.com.netlabs.luthier

import java.nio.file.{Path, Paths}
import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext

trait AppContext {
  def name: String
  def rootLocation: Path

  lazy val actorSystem: ActorSystem = ActorSystem(name.replace(' ', '-').replace('.', '-'))
}

object AppContext {
  def quick(name: String) = {
    val n = name
    new AppContext {
      val name = n
      val rootLocation = Paths.get(".")
    }
  }
}