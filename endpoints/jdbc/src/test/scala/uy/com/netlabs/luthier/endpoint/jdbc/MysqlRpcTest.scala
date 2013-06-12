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
package endpoint.jdbc

import scala.concurrent._, duration._
import scala.util._
import language._


import org.scalatest._

class MysqlRpcTest extends endpoint.BaseFlowsTest {
  describe("A MysqlRpcTest") {
    it("should be able to work when the server starts first") {
      new Flows {
        val appContext = testApp
        val ds = new com.mysql.jdbc.jdbc2.optional.MysqlDataSource()
        ds setUrl "jdbc:mysql://localhost/test"
        val msgArrived = Promise[Unit]()
        val server = new Flow("server")(MysqlRpc.Server(ds, "myTest")) {
          logic {m =>
            println("Message received " + m.payload)
            msgArrived.success(())
          }
        }
        server.start()
        Thread.sleep(1)
        val flowRes = inFlow {(flow, rm) =>
          try {
            import flow._
            implicit val flowRun = rm.flowRun
            val msg = rm.map(_ => "Hi server"->"myTest")
            MysqlRpc.Client(ds).push(msg)
          } catch {case ex: Throwable => ex.printStackTrace()}
        }
        Await.result(msgArrived.future, 10 seconds)
        server.dispose()
      }
    }

    it("should be able to work when the client starts first") {
      new Flows {
        val appContext = testApp
        val ds = new com.mysql.jdbc.jdbc2.optional.MysqlDataSource()
        ds setUrl "jdbc:mysql://localhost/test"
        val msgArrived = Promise[Unit]()

        val flowRes = inFlow {(flow, rm) =>
          try {
            import flow._
            implicit val flowRun = rm.flowRun
            val msg = rm.map(_ => "Hi server"->"myTest")
            MysqlRpc.Client(ds).push(msg)
          } catch {case ex: Throwable => ex.printStackTrace()}
        }
        Thread.sleep(1)
        val server = new Flow("server")(MysqlRpc.Server(ds, "myTest")) {
          logic {m =>
            println("Message received " + m.payload)
            msgArrived.success(())
          }
        }
        server.start()
        Await.result(msgArrived.future, 10 seconds)
        server.dispose()
      }
    }
  }
  describe("Fire test") {
    it("should pass. Thanks") {
      new Flows {
        val appContext = testApp
        val ds = new com.mysql.jdbc.jdbc2.optional.MysqlDataSource()
        ds setUrl "jdbc:mysql://localhost/test"
        val amount = 100000
        val latch = new java.util.concurrent.CountDownLatch(amount)
        val server = new Flow("server")(MysqlRpc.Server(ds, "myTest")) {
          logic {m =>
            println("Message received " + m.payload)
            latch.countDown()
          }
        }
        server.start()

        val start = System.nanoTime
        val flowRes = inFlow {(flow, rm) =>
          try {
            import flow._
            implicit val flowRun = rm.flowRun
            val endpoint = endpointFactory2Endpoint(MysqlRpc.Client(ds))

            val msg = rm.map(_ => MysqlRpc.BatchMsg((0 until amount) map (i =>  "!Hi server: " + i), "myTest", false))
            endpoint.push(msg) flatMap (_ => endpoint.push(rm.map(_ => MysqlRpc.Wakeup("myTest"))))

//            val fs = for (i <- 0 until amount) yield {
//              val msg = rm.map(_ => MysqlRpc.BatchMsg(Seq( "!Hi server: " + i), "myTest", false))
//              endpoint.push(msg)
//            }
//            Future sequence fs
          } catch {case ex: Throwable => ex.printStackTrace()}
        }
        latch.await()
        val totalTime = System.nanoTime - start
        println(f"Total time: ${totalTime.nanos.toMillis}ms; T/s = ${amount/totalTime.toFloat*1e9}%,.2f")
      }
    }
  }
}
