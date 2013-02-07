package uy.com.netlabs.luthier

import scala.language._
import scala.collection.mutable._

/**
 * Value implicitly available during a run.
 * Useful to store run temporal information as well as a message factory.
 */
trait FlowRun[+FlowType <: Flow] extends MessageFactory {
  
  /**
   * Message that started the run, i.e.: the message declared in logic
   */
  val rootMessage: RootMessage[FlowType]
  /**
   * The flow to which this run belongs
   */
  val flow: FlowType
  /**
   * A run context to put anything you like.
   */
  val context: Map[Any, Any] = scala.collection.concurrent.TrieMap.empty
  
  private[this] var doneReactions = Set.empty[() => Unit]
  def afterFlowRun(code: => Unit) {
    doneReactions += (() => code)
  }
  private[luthier] def flowRunCompleted() {
    for (r <- doneReactions) r()
  }
  
  private[this] var lastProducedMessage0: Message[_] = _
  private[this] var lastReceivedMessage0: Message[_] = rootMessage
  def lastProducedMessage: Message[_] = lastProducedMessage0
  def lastReceivedMessage: Message[_] = lastReceivedMessage0
  def messageSent[P](m: Message[P]) = {
    lastProducedMessage0 = m
    m
  }
  def createReceivedMessage[P](payload: P) = {
    val res = lastProducedMessage.map(_ => payload)
    lastProducedMessage0 = res //FIXME: not sure this makes much sense...
    res
  }
  
  def apply[P](payload: P) = {
    val res = lastReceivedMessage0.map(_ => payload)
    lastProducedMessage0 = res
    res
  }
}