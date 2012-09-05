package uy.com.netlabs.esb

trait Destination

object Destination {
  implicit class UnresolvedDestination(val path: String) extends Destination
}