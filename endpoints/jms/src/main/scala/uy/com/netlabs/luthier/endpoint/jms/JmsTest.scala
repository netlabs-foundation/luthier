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

    val jmsConnectionFactory = {
      val res = new org.apache.activemq.pool.PooledConnectionFactory("tcp://localhost:61616")
//      res.setDispatchAsync(true)
//      res.setUseRetroactiveConsumer(true)
//      res.setRedeliveryPolicy({
//        val res = new org.apache.activemq.RedeliveryPolicy
//        res setMaximumRedeliveries 1
//        res
//      })
//      res.createQueueConnection
      res.start
      res
    }
    val appContext = myApp

    val askMeQueue = Jms.queue("askMe", jmsConnectionFactory)

    class Lala()

    new Flow("say hello")(askMeQueue)(ExchangePattern.RequestResponse) {
      logic { req =>
        req.map("Hello " + _)
      }
    }

    new Flow("logQuestion")(Jms.queue("logQuestion", jmsConnectionFactory))(ExchangePattern.OneWay) {
      logic { req =>
        askMeQueue.ask(req.as[String]) onSuccess {case r => Jms.topic("result", jmsConnectionFactory).push(r.as[String])}
      }
    }
    new Flow("listenResult")(Jms.topic("result", jmsConnectionFactory)) {
      logic {req => println("Result to some request: " + req.payload)}
    }

    new Flow("ping")(endpoint.logical.Metronome("ping", 1 seconds)) {
      logic {m =>
        println("...pinging")
        Jms.queue("logQuestion", jmsConnectionFactory).push(m) onComplete (t => println("Ping result " + t))
      }
    }

    registeredFlows foreach (_.start)
  }
}