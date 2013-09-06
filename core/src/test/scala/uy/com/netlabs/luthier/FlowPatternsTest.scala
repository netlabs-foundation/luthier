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

import typelist._
import scala.concurrent._, duration._

class FlowPatternsTest extends endpoint.BaseFlowsTest {
  val totalCount = 15
  describe(s"Retrying with $totalCount attempts") {
    it(s"should perform $totalCount retries before giving up") {
      new Flows {
        val appContext = testApp
        @volatile var count = 0
        val run = inFlow { (flow, msg) =>
          import flow._
          retryAttempts(totalCount, "fail op")(doWork { println("Counting!"); count += 1 })(_ => count != totalCount)(msg.flowRun)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        Await.result(run, 1.second)
        assert(count === totalCount)
      }
    }
  }
  val maxBackoff = 600.millis
  describe(s"Retrying with $maxBackoff attempts") {
    it(s"should continue backing off retries, until failure.") {
      new Flows {
        val appContext = testApp
        @volatile var count = 0
        val run = inFlow { (flow, msg) =>
          import flow._
          retryBackoff(100, 2, maxBackoff.toMillis, "fail op")(blocking { println("Counting!"); count += 1 })(_ => true)(msg.flowRun)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        Await.result(run, 1.2.seconds)
        assert(count === 4)
      }
    }
  }


  /*
   * Some setup to test the paging endpoints.
   * We will declare an endpoint that returns pages of an entity person.
   */
  case class Person(name: String, lastName: String, age: Int)
  def pagingFlow(flows: Flows, throwExceptionOnIndexOutOfBound: Boolean): Flow = {
    import flows._
    val Vm = endpoint.base.VM.forAppContext(appContext)
    new Flow("paging endpoint")(Vm.responsible[java.lang.Integer, Seq[Person] :: TypeNil]("pagingEndpoint")) {
      logic {in =>
        if (in.payload >= 10) {
          if (throwExceptionOnIndexOutOfBound) throw new Exception("Invalid index exception")
          else in map (_ => Seq.empty)
        } else in map (_ => Seq.tabulate(10)(i => Person(s"RandomName${in.payload}$i", s"LastName${in.payload}$i", scala.util.Random.nextInt(50) + 5)))
      }
    }
  }

  describe("Paging") {
    it ("should iterated all pages in sequence using fold") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, false)
        pager.start()
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = paging(0){i => ep.ask(msg.map(_ => i)) map {
              case Message(ex: Exception) => None
              case Message(persons) if persons.nonEmpty => Some(persons->(i+1))
              case _ => None
            }}
          pages.fold(Seq.empty[Person])((msg, acc) => acc ++ msg)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        val seq = Await.result(res, 2.seconds)
        assert(seq.size === 100)
      }
    }
    it ("should fail folding if one of the page requests fail") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, true)
        pager.start()
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = paging(0){i => ep.ask(msg.map(_ => i)) map {
              case Message(persons) if persons.nonEmpty => Some(persons->(i+1))
              case _ => None
            }}
          pages.fold(Seq.empty[Person])((msg, acc) => acc ++ msg)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        Await.ready(res, 2.seconds)
        assert(res.value.get.isFailure)
      }
    }
    it ("should not request pages on mapping") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, false)
        pager.start()
        @volatile var lastRequestedIndex = 0
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = paging(0){i => lastRequestedIndex = i; ep.ask(msg.map(_ => i)) map {
              case Message(persons) if persons.nonEmpty => Some(persons->(i+1))
              case _ => None
            }}
          pages.map(_ map (_.name)).next
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        Await.result(res, 1.seconds)
        Thread.sleep(100) ///give enough time for a second page request
        assert(lastRequestedIndex === 0)
      }
    }
    it ("should filter on demand without requesting all the pages") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, false)
        pager.start()
        @volatile var lastRequestedIndex = 0
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = paging(0){i => lastRequestedIndex = i; ep.ask(msg.map(_ => i)) map {
              case Message(persons) if persons.nonEmpty => Some(persons->(i+1))
              case _ => None
            }}
          pages.filter(_.find(_.name endsWith "55").isDefined).next
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        Await.result(res, 2.seconds)
        Thread.sleep(100) ///give enough time for a second page request
        assert(lastRequestedIndex === 5)
      }
    }
    it ("should be able to filter") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, false)
        pager.start()
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = paging(0){i => ep.ask(msg.map(_ => i)) map {
              case Message(persons) if persons.nonEmpty => Some(persons->(i+1))
              case _ => None
            }}
          pages.filter(_.find(_.name.init.head == '2').isDefined).fold(Seq.empty[Person])((msg, acc) => acc ++ msg)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        val persons = Await.result(res, 2.seconds)
        assert(persons.forall(_.name.init.head == '2'), "Found persons:\n  " + persons.mkString("\n  "))
      }
    }
    it ("should be able to map") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, false)
        pager.start()
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = paging(0){i => ep.ask(msg.map(_ => i)) map {
              case Message(persons) if persons.nonEmpty => Some(persons->(i+1))
              case _ => None
            }}
          pages.map(_.filter(_.name endsWith "5")).fold(Seq.empty[Person])((msg, acc) => acc ++ msg)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        val persons = Await.result(res, 2.seconds)
        assert(persons.forall(_.name endsWith "5"), "Found persons:\n  " + persons.mkString("\n  "))
      }
    }
  }
  describe("Indexed Paging") {
    it ("should iterated all pages in sequence using fold") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, false)
        pager.start()
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = indexedPaging(0){_ => (i => ep.ask(msg.map(_ => i)) map {
                case Message(ex: Exception) => None
                case Message(persons) if persons.nonEmpty => Some(persons)
                case _ => None
              })}
          pages.fold(Seq.empty[Person])((msg, acc) => acc ++ msg)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        val seq = Await.result(res, 2.seconds)
        assert(seq.size === 100)
      }
    }
    it ("should fail folding if one of the page requests fail") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, true)
        pager.start()
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = indexedPaging(0){_ => (i => ep.ask(msg.map(_ => i)) map {
                case Message(ex: Exception) => None
                case Message(persons) if persons.nonEmpty => Some(persons)
                case _ => None
              })}
          pages.fold(Seq.empty[Person])((msg, acc) => acc ++ msg)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        Await.ready(res, 2.seconds)
        assert(res.value.get.isFailure)
      }
    }
    it ("should not request pages on mapping") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, false)
        pager.start()
        @volatile var lastRequestedIndex = 0
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = indexedPaging(0){_ => {i => lastRequestedIndex = i; ep.ask(msg.map(_ => i)) map {
                case Message(ex: Exception) => None
                case Message(persons) if persons.nonEmpty => Some(persons)
                case _ => None
              }}}
          pages.map(_ map (_.name)).get(0)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        Await.result(res, 1.seconds)
        Thread.sleep(100) ///give enough time for a second page request
        assert(lastRequestedIndex === 0)
      }
    }
    it ("should filter on demand without requesting all the pages") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, false)
        pager.start()
        @volatile var lastRequestedIndex = 0
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = indexedPaging(0){_ => {i => lastRequestedIndex = i; ep.ask(msg.map(_ => i)) map {
                case Message(ex: Exception) => None
                case Message(persons) if persons.nonEmpty => Some(persons)
                case _ => None
              }}}
          pages.filter(_.find(_.name endsWith "55").isDefined).next
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        Await.result(res, 2.seconds)
        Thread.sleep(100) ///give enough time for a second page request
        assert(lastRequestedIndex === 5)
      }
    }
    it ("should be able to filter") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, false)
        pager.start()
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = indexedPaging(0){_ => (i => ep.ask(msg.map(_ => i)) map {
                case Message(ex: Exception) => None
                case Message(persons) if persons.nonEmpty => Some(persons)
                case _ => None
              })}
          pages.filter(_.find(_.name.init.head == '2').isDefined).fold(Seq.empty[Person])((msg, acc) => acc ++ msg)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        val persons = Await.result(res, 2.seconds)
        assert(persons.forall(_.name.init.head == '2'), "Found persons:\n  " + persons.mkString("\n  "))
      }
    }
    it ("should be able to map") {
      new Flows {
        val appContext = testApp
        val Vm = endpoint.base.VM.forAppContext(appContext)
        val pager = pagingFlow(this, false)
        pager.start()
        val res = inFlow { (flow, msg) =>
          import flow._
          implicit val fr = msg.flowRun
          val ep = Vm.ref[Int, Seq[Person]]("user/VM/pagingEndpoint")
          val pages = indexedPaging(0){_ => (i => ep.ask(msg.map(_ => i)) map {
                case Message(ex: Exception) => None
                case Message(persons) if persons.nonEmpty => Some(persons)
                case _ => None
              })}
          pages.map(_.filter(_.name endsWith "5")).fold(Seq.empty[Person])((msg, acc) => acc ++ msg)
        }.flatMap(identity)(appContext.actorSystem.dispatcher)
        val persons = Await.result(res, 2.seconds)
        assert(persons.forall(_.name endsWith "5"), "Found persons:\n  " + persons.mkString("\n  "))
      }
    }
  }
}