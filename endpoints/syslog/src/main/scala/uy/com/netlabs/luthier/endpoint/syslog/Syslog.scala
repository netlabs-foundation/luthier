/**
 * Copyright (c) 2013, Netlabs S.R.L. <contacto@netlabs.com.uy>
 * All rights reserved.
 *
 * This software is dual licensed as GPLv2: http://gnu.org/licenses/gpl-2.0.html,
 * and as the following 3-clause BSD license. In other words you must comply to
 * either of them to enjoy the permissions they grant over this software.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name "netlabs" nor the names of its contributors may be
 *       used to endorse or promote products derived from this software
 *       without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL NETLABS S.R.L.  BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uy.com.netlabs.luthier
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
    lazy val ioProfile = base.IoProfile.threadPool(ioWorkers, flow.name + "-syslog-ep")
    val appName = flow.appContext.name

    def pushMessage[Payload: SupportedType](m) = {
      m.payload match {
        case msg: String                        => syslogInstance.info(s"$appName: $msg")
        case (level: Int, msg: String)          => syslogInstance.log(level, s"$appName: $msg")
        case msg: SyslogMessageIF               => syslogInstance.info(s"$appName: ${msg.createMessage()}")
        case (level: Int, msg: SyslogMessageIF) => syslogInstance.log(level, s"$appName: ${msg.createMessage()}")
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