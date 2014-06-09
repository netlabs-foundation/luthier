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
package jdbc

import java.nio.file.{ Paths, Files }

import scala.concurrent._, duration._
import scala.util._
import language._

import endpoint.logical.Poll
import endpoint.logical.Metronome

import org.h2.jdbcx.JdbcConnectionPool

import org.scalatest._

class JdbcTest extends FunSpec with BeforeAndAfter {

  var myApp: AppContext = _
  var dataSource: JdbcConnectionPool = _
  before {
    myApp = AppContext.build("Test Jdbc App")
    dataSource = JdbcConnectionPool.create("jdbc:h2:mem:test", "test", "test")
    val conn = dataSource.getConnection()
    try {
      val st = conn.createStatement()
      st.execute("CREATE TABLE Coffees (cofName text, supId text, price double)")
      st.execute("INSERT INTO Coffees VALUES('Colombian', '101', 7.99)")
      st.execute("INSERT INTO Coffees VALUES('Colombian_Decaf', '101', 8.99)")
      st.execute("INSERT INTO Coffees VALUES('French_Roast_Decaf', '49', 9.99)")
    } finally { conn.close() }
  }
  after {
    dataSource.dispose
    myApp.actorSystem.shutdown()
  }

  case class Coffee(name: String, supplier: String, price: Double)
  val rowMapper = (r: Row) => Coffee(r.get[String](1), r.get[String](2), r.get[Double](3))

  describe("A JdbcEndpoint") {
    it("Should be able to poll the Database") {
      new Flows {
        val appContext = myApp

        val result = Promise[Unit]()

        val flow = new Flow("Poll DB")(Poll.pulling(Jdbc.const("SELECT * FROM Coffees", rowMapper, dataSource), 1.seconds)) { //initial delay is 0
          logic { m =>
            result complete Try(assert(m.payload.length === 3))
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 0.25.seconds))
        flow.dispose()
        res.get
      }
    }

    it("Should be able to ask the Database") {
      new Flows {
        val appContext = myApp

        val result = Promise[Unit]()
        val flow = new Flow("Ask DB")(Metronome(1.seconds)) { //initial delay is 0
          logic { m =>
            result completeWith {
              Jdbc.parameterized("SELECT * FROM Coffees WHERE cofName = ?", rowMapper, dataSource).ask(Message(IndexedSeq("Colombian_Decaf"))) map { resp =>
                assert(resp.payload.head === Coffee("Colombian_Decaf", "101", 8.99))
              }
            }
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 0.25.seconds))
        flow.dispose()
        res.get
      }
    }
  }
}
