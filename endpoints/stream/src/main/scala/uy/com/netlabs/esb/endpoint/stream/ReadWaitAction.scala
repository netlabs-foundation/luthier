package uy.com.netlabs.esb.endpoint.stream

import scala.util._

/**
 * Action to be taken when a specified amount of time elapses after a byte is read.
 * See companion object for available implementations.
 */
trait ReadWaitAction[+State, +Prod] {
  def maxWaitTime: Long
  def apply[S >: State, P >: Prod](prevState: S, consumer: Consumer[S, P]): consumer.ConsumerResult
}

object ReadWaitAction {
  object DoNothing extends ReadWaitAction[Nothing, Nothing] {
    val maxWaitTime = 0l
    def apply[S >: Nothing, P >: Nothing](prevState: S, consumer: Consumer[S, P]): consumer.ConsumerResult = consumer.NeedMore(prevState)
  }
//  /**
//   * Sends a signal to the consumer requesting its partial data, if it has and is able to return it.
//   */
//  def PullData[State, Prod](timeout: Long) = new ReadWaitAction[State, Prod] {
//    def maxWaitTime = timeout
//    def apply[S >: State, P >: Prod](prevState: S, consumer: Consumer[S, P]): consumer.ConsumerResult = consumer.consume(RetrievePartialState(prevState))
//  }
  /**
   * Returns the passed value as consumer byproduct, without changing the state
   */
  def ReadValueData[State, Prod](timeout: Long, value: Prod) = new ReadWaitAction[State, Prod] {
    def maxWaitTime = timeout
    def apply[S >: State, P >: Prod](prevState: S, consumer: Consumer[S, P]): consumer.ConsumerResult = consumer.ByProduct(Seq(Success(value)), prevState)
  }
}