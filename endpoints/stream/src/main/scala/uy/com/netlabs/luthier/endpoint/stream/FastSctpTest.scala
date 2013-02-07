package uy.com.netlabs.luthier
package endpoint
package stream

import language._
import Sctp._

object FastSctpTest extends App {
  val test = new Flows {
    val appContext = AppContext.quick("streams")
    new Flow("ss")(Server(1500, 1024)) {
      logic { client =>
        val clientChannel = client.payload.conn
        println(s"Client ${clientChannel}:${clientChannel.association} arrived.")
        new Flow("clientHandler-" + client.payload)(Handler(client, 0, consumers.lines(), serializers.string)) {
          logic { m: Message[String] =>
            println("Client message: " + m.payload)
            m map (8 -> _)
          }
        }
        new Flow("clientHandler2-" + client.payload)(Handler(client, 1, consumers.lines())) {
          logic { m: Message[String] =>
            m.payload match {
              case exit if exit.trim == "exit" =>
                println("Shutting down client")
                closeClient(client)
              case other =>
            }
          }
        }
      }
    }.start
  }
}