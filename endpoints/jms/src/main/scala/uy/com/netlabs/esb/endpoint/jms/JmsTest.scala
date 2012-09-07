package uy.com.netlabs.esb
package endpoint.jms

import java.nio.file.{ Paths, Files }
import scala.concurrent.Future
import scala.concurrent.util.{ Duration, duration }, duration._
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

    new Flow("say hello")(askMeQueue RequestResponse) {
      logic { req =>
        req.map(prev => "Hello " + prev)
      }
    }

    new Flow("logQuestion")(Jms.queue("logQuestion", jmsConnection) OneWay) {
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