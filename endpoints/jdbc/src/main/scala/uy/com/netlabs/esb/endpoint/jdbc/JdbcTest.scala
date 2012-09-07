package uy.com.netlabs.esb
package endpoint.jdbc

import java.nio.file.{ Paths, Files }
import scala.concurrent.Future
import scala.concurrent.util.{ Duration, duration }, duration._
import language._
import uy.com.netlabs.esb.endpoint.PollingFeatures
import endpoint.PollingFeatures._

object JdbcTest extends App {
  val myApp = new AppContext {
    val name = "Test Jdbc App"
    val rootLocation = Paths.get(".")
  }
  new Flows {
    val appContext = myApp

    val dataSource: javax.sql.DataSource = null
    
    def rowMapper(row: Row) = row.get[String](1)->row.get[String](2)
    
    new Flow("Poll DB")(Poll(Jdbc.const("SELECT UserName, Group from Users", rowMapper, dataSource, 4), 10.seconds)) {
      logic {m => 
        m.payload.head._1
      }
    }
    
    registeredFlows foreach (_.start)
  }
}