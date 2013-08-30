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
package amqp

import scala.util._
import scala.concurrent._, duration._
import com.rabbitmq.client.ConnectionFactory
import java.util.concurrent.CountDownLatch

class AmqpTest extends BaseFlowsTest {
  var Amqp: Amqp = null
  override def beforeEach() {
    super.beforeEach()
    Amqp = new Amqp(new ConnectionFactory)(testApp)
  }
  override def afterEach() {
    super.beforeEach()
    Amqp.dispose(); Amqp = null
  }
  describe("A AMQP endpoint") {
    it("should be able to push and pull messages") {
      new Flows {
        val appContext = testApp
        val flowRun = inFlow { (flow, rm) =>
          import flow._
          implicit val fr = rm.flowRun
          for {
            pushedOp <- Amqp(Seq("panchi")).push(rm map (_ => "msg".getBytes))
            retrievedMsg <- Amqp(Seq("panchi")).pull()
          } yield retrievedMsg

        }.flatMap(identity)(testApp.actorSystem.dispatcher)
        val msg = Await.result(flowRun, 0.25.seconds)
        assert(msg.payload.flatMap(new String(_) === "msg"))
      }
    }
    val msgCount = 1000
    it(s"should be able to send and receive $msgCount msgs") {
      new Flows {
        val appContext = testApp
        val countDown = new CountDownLatch(msgCount)
        //clear the queue just in case
        Amqp.channel.queuePurge("panchi")
        val flowRun = inFlow { (flow, rm) =>
          import flow._
          implicit val fr = rm.flowRun
          val pushes = for (i <- 0 until msgCount) yield Amqp(Seq("panchi")).push(rm map (_ => s"msg$i".getBytes))
          Future sequence pushes
        }.flatMap(identity)(testApp.actorSystem.dispatcher)
        Await.ready(flowRun, 5.seconds)
        new Flow("receiveAllMessages")(Amqp.consume(Seq("panchi")))(ExchangePattern.OneWay) {
          logic { _ => countDown.countDown() }
        }.start()
        try countDown.await(15, java.util.concurrent.TimeUnit.SECONDS)
        catch { case ex: Exception => throw new Exception(s"Messages weren't completely received after 15 seconds, remaining: ${countDown.getCount()}") }
      }
    }
    it(s"should be able to implement a responsible") {
      new Flows {
        val appContext = testApp
        new Flow("echo")(Amqp.consume(Seq("panchi")))(ExchangePattern.RequestResponse) {
          logic { m =>
            m map (s => ("Hello " + new String(s)).getBytes)
          }
        }.start
        val flowRun = inFlow { (flow, rm) =>
          import flow._
          implicit val fr = rm.flowRun
          val req = rm map (_ => "world".getBytes)
          req.replyTo = AmqpDestination("answerMeBastard")
          val queue = Queue("answerMeBastard", autoDelete = true)
          for {
            _ <- Amqp(Seq("panchi")).push(req)
            resp <- untilSuccessful("fetch reply")(Amqp(Seq("answerMeBastard"), queue = queue).pull())(isSuccess = {
              case Success(Message(Some(content))) => true
              case _                               => false
            })
          } yield resp
        }.flatMap(identity)(testApp.actorSystem.dispatcher)
        val msg = Await.result(flowRun, 20.millis)
        assert(msg.payload.flatMap(new String(_) === "Hello world"))
      }
    }
  }
}