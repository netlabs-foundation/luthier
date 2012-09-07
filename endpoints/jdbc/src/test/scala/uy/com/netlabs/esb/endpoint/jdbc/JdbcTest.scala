package uy.com.netlabs.esb
package endpoint.jdbc

import java.nio.file.{ Paths, Files }

import scala.concurrent._
import scala.concurrent.util.{ Duration, duration }, duration._
import scala.util._
import language._

import endpoint.PollingFeatures._

import org.h2.jdbcx.JdbcConnectionPool

import org.scalatest._

class JdbcTest extends FunSpec with BeforeAndAfter {

  var myApp: AppContext = _
  var dataSource: JdbcConnectionPool = _
  before {
    myApp = new AppContext {
      val name = "Test Jdbc App"
      val rootLocation = Paths.get(".")
    }
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

        val result = Promise[Option[String]]()
        val flow = new Flow("Poll DB")(Poll(Jdbc.const("SELECT * FROM Coffees", rowMapper, dataSource), 1.seconds)) { //initial delay is 0
          logic { m =>
            result.success(m.payload.length === 3)
          }
        }
        flow.start
        val res = Try(Await.result(result.future, 0.25.seconds))
        flow.stop
        assert(res.get)
      }
    }
  }

  //  new Flows {
  //    val appContext = myApp
  //
  //    val dataSource: javax.sql.DataSource = null
  //
  //    def rowMapper(row: Row) = row.get[String](1) -> row.get[String](2)
  //
  //    val query = Jdbc.parameterized("SELECT * From Users where name LIKE ? AND and origin = ?", rowMapper, dataSource)
  //
  //    new Flow("Poll DB")(Poll(Jdbc.const("SELECT UserName, Group from Users", rowMapper, dataSource, 4), 10.seconds)) {
  //      logic { m =>
  //        val users: Seq[(String, String)] = m.payload
  //        query.ask(Message(("Marcos", "Uruguay"))) map { u =>
  //          val users2: Seq[(String, String)] = u.payload
  //        }
  //      }
  //    }
  //    registeredFlows foreach (_.start)
  //  }
}