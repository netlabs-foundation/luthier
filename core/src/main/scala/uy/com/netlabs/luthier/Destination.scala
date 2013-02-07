package uy.com.netlabs.luthier

trait Destination

object Destination {
  implicit class UnresolvedDestination(val path: String) extends Destination
}