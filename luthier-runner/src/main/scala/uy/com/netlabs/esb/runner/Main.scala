package uy.com.netlabs.esb.runner

import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.IMain

object Main {
  def main(args: Array[String]) {
    val settings = new Settings
    settings.YmethodInfer.value = true
    val compiler = new IMain
  }
}