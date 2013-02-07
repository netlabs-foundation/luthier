package uy.com.netlabs.luthier
package endpoint
package stream

import language._
import Tcp._
import java.nio.channels._

object FastTcpTest extends App {
  val test = new Flows {
    val appContext = AppContext.quick("streams")
    new Flow("ss")(Server(1500, 1024)) {
      logic { client =>
        new Flow("clientHandler-" + client.payload)(Handler(client, consumers.lines(), serializers.string, ReadWaitAction.ReadValueData(2000, "lazy client"))) {
          logic { m: Message[String] =>
            println("Client message: " + m.payload)
            m
          }
        }
        new Flow("clientHandler2-" + client.payload)(Handler(client, consumers.lines())) {
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
    
    val chnl = SocketChannel.open(new java.net.InetSocketAddress("google.com", 80))
    chnl.finishConnect()
    chnl.write(java.nio.ByteBuffer.wrap("GET\n\r".getBytes))
    
    new Flow("SimpleConnectTo")(Client(chnl, consumers.lines()))(ExchangePattern.OneWay) {
      logic {m => 
      	println(s"Received: ${m.payload}")
      }
    }.start
  }
}