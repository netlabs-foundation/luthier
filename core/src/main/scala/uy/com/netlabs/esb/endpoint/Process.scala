package uy.com.netlabs.esb.endpoint

import scala.sys.process._

object Process {
  def apply(process: ProcessBuilder, ioThreads: Int = 1) = Function(process.!, ioThreads)
  def lines(process: ProcessBuilder, ioThreads: Int = 1) = Function(process.lines, ioThreads)
  def string(process: ProcessBuilder, ioThreads: Int = 1) = Function(process.lines.mkString("\n"), ioThreads)
}