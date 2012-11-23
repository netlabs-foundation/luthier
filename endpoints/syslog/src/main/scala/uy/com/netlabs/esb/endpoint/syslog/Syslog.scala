package uy.com.netlabs.esb
package endpoint
package syslog

import typelist._
import org.productivity.java.syslog4j._

object Syslog {

  case class EF private[Syslog] (protocol: String, host: String, port: Int, ioWorkers: Int) extends EndpointFactory[SyslogEndpoint] {
    def apply(f) = new SyslogEndpoint(f, protocol, host, port, ioWorkers)
  }

  def apply(protocol: String = "udp", host: String = "127.0.0.1", port: Int = 514, ioWorkers: Int = 1) = EF(protocol, host, port, ioWorkers)
  
  class SyslogEndpoint(val flow: Flow,
                       val protocol: String,
                       val host: String,
                       val port: Int,
                       val ioWorkers: Int) extends base.BaseSink {
    type SupportedTypes = (Int, String) :: String :: SyslogMessageIF :: (Int, SyslogMessageIF) :: TypeNil

    var syslogInstance: SyslogIF = _
    lazy val ioProfile = base.IoProfile.threadPool(ioWorkers)

    def pushMessage[Payload: SupportedType](m) = {
      m.payload match {
        case msg: String                        => syslogInstance.info(msg)
        case (level: Int, msg: String)          => syslogInstance.log(level, msg)
        case msg: SyslogMessageIF               => syslogInstance.info(msg)
        case (level: Int, msg: SyslogMessageIF) => syslogInstance.log(level, msg)
      }
    }

    def start {
      syslogInstance = org.productivity.java.syslog4j.Syslog.getInstance(protocol)
      syslogInstance.getConfig().setHost(host)
      syslogInstance.getConfig().setPort(port)
    }
    def dispose {
      if (syslogInstance != null) syslogInstance.shutdown()
    }
  }
}