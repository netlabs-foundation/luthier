package uy.com.netlabs.luthier.endpoint.jms

import uy.com.netlabs.luthier.Destination

case class JmsDestination(destination: javax.jms.Destination) extends Destination