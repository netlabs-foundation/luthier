package uy.com.netlabs.esb

import java.nio.file.Path
import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext

trait AppContext {
  def name: String
  def rootLocation: Path
  
  lazy val actorSystem: ActorSystem = ActorSystem(name.replace(' ', '-'))
}