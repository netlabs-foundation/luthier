package uy.com.netlabs.luthier
package endpoint.jms

import java.nio.file.{ Paths, Files }
import scala.concurrent.Future
import scala.concurrent.duration._
import language._

object JmsTest extends App {

  val myApp = new AppContext {
    val name = "Test Jms App"
    val rootLocation = Paths.get(".")
  }
  new Flows {

    val jmsConnection = {
      val res = new org.apache.activemq.ActiveMQConnectionFactory("tcp://localhost:61616")
      res.setDispatchAsync(true)
      res.setUseRetroactiveConsumer(true)
      res.setRedeliveryPolicy({
        val res = new org.apache.activemq.RedeliveryPolicy
        res setMaximumRedeliveries 1
        res
      })
      res.createQueueConnection
    }
    val appContext = myApp
    
    val askMeQueue = Jms.queue("askMe", jmsConnection)

    class Lala()
    
    new Flow("say hello")(askMeQueue)(ExchangePattern.RequestResponse) {
      logic { req =>
        req.map("Hello " + _)
      }
    }

    new Flow("logQuestion")(Jms.queue("logQuestion", jmsConnection))(ExchangePattern.OneWay) {
      logic { req =>
        askMeQueue.ask(req.mapTo[String]) onSuccess {case r => Jms.topic("result", jmsConnection).push(r.mapTo[String])}
      }
    }
    new Flow("listenResult")(Jms.topic("result", jmsConnection)) {
      logic {req => println("Result to some request: " + req.payload)}
    }

    registeredFlows foreach (_.start)
  }
}