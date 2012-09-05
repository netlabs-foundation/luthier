package uy.com.netlabs.esb.endpoint.jms

import uy.com.netlabs.esb.Destination

case class JmsDestination(destination: javax.jms.Destination) extends Destination