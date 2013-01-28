package uy.com.netlabs.esb
package endpoint
package irc

import language._
import jerklib._

object IrcTest extends App with Flows {
  val appContext = AppContext.quick("IrcTest")
  
  val name = "aadvark123"
  val ircSession = new ConnectionManager(new Profile(name)).requestConnection("chat.freenode.net", 6667)
  
  new Flow("Logging")(Irc("#someChannel2", ircSession))(ExchangePattern.RequestResponse) {
    logic {in => 
      val msg = in.payload
      val users = Irc.listUsersInChannel()
      Irc("#someChannel2", ircSession).ask(in.map(p => RequestMessage("#someChannel3", msg.from + " asked: " + p.message, _.to == name))) map {resp =>
        resp map (p => OutMessage("#someChannel2", p.message))
      }
    }
  }.start
}