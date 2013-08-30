package uy.com.netlabs.luthier
package endpoint
package amqp

import com.rabbitmq.client.ConnectionFactory

object SimpleAmqpTest extends App with Flows {
  val appContext = AppContext.build("simpleAmqpTest")
  val Amqp = new Amqp(new ConnectionFactory())
  new Flow("listenOnPanchi")(Amqp.consume(Seq("panchi")))(ExchangePattern.OneWay) {
    logic {in => println("Received " + new String(in.payload))}
  }.start
}
