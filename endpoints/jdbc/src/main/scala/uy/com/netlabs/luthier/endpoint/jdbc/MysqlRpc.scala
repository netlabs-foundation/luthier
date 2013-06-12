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

import typelist._
import javax.sql._, java.sql._
import scala.util._

object MysqlRpc {

  private[MysqlRpc] trait Mutex {
    def lock()
    def unlock()
  }
  private[MysqlRpc] def createMutex(conn: Connection, methodName: String): Mutex = {
    val lockStatement = conn.prepareStatement("SELECT get_lock(?, 60);")
    val unlockStatement = conn.prepareStatement("SELECT release_lock(?);")
    lockStatement.setString(1, "mysqlrpc_v1_" + methodName + "_mutex")
    unlockStatement.setString(1, "mysqlrpc_v1_" + methodName + "_mutex")
    new Mutex {
      def lock = lockStatement.executeQuery.close
      def unlock = unlockStatement.executeQuery.close
    }
  }

  class Server private[MysqlRpc](val flow: Flow,
                                 val dataSource: DataSource,
                                 val methodName: String,
                                 val pollTime: Int) extends endpoint.base.BaseSource {
    type Payload = String

    lazy val conn = dataSource.getConnection();
    @volatile private[this] var stopServer = false;
    @volatile private[this] var tid: Int = -1

    def start(): Unit = {
      conn

      flow.blocking {
        while (!stopServer) {
          lazy val statement: Statement = conn.createStatement
          val processed = new collection.mutable.ArrayBuffer[Long]
          val mutex = createMutex(conn, methodName)
          def deleteProcessed() = {
            if (processed.length > 0) {
              val upd = statement.executeUpdate(
                "DELETE FROM mysqlrpc_v1 " +
                " WHERE id IN (" + (processed map (_.toString) mkString ",") + ");")
              processed.clear()
              upd
            } else 0
          }

          try {
            // check the rpc table exists, create it otherwise:
            statement //initialize statement
            var rs = statement.executeQuery(
              "SELECT count(*) > 0 FROM information_schema.tables " +
              " WHERE table_schema = database() " +
              "   AND table_type='BASE TABLE' " +
              "   AND table_name='mysqlrpc_v1';")
            rs.next
            val rpcTableExists = rs.getBoolean(1)
            rs.close
            if (!rpcTableExists) {
              println("creating mysqlrpc_v1 table")
              statement.executeUpdate(
                "CREATE TABLE mysqlrpc_v1 (" +
                "  id serial PRIMARY KEY," +
                "  data text, " +
                "  method text" +
                ") ENGINE InnoDB;")
            }

            // This prepared statement will be used to obtain the queued messages to
            // the queue for methodName. "FOR UPDATE" is used to lock the resulting
            // rows.
            val listStatement = conn.prepareStatement(
              "SELECT id, data " +
              "  FROM mysqlrpc_v1 " +
              " WHERE method = ? " +
              " LIMIT 1000 " +
              "   FOR UPDATE;")
            listStatement.setString(1, methodName)

            // Does this server support multiple locking?
            rs = statement.executeQuery("SELECT connection_id();");
            rs.next
            tid = rs.getInt(1)
            rs.close
            statement.executeQuery("SELECT get_lock('mysqlrpc_v1_"+tid+"_lock1', 1);").close;
            statement.executeQuery("SELECT get_lock('mysqlrpc_v1_"+tid+"_lock2', 1);").close;
            rs = statement.executeQuery("SELECT is_used_lock('mysqlrpc_v1_"+tid+"_lock1');");
            rs.next
            rs.getInt(1)
            val supportsMultipleLocks = !rs.wasNull
            rs.close
            if (supportsMultipleLocks) {
              statement.executeQuery("SELECT release_lock('mysqlrpc_v1_"+tid+"_lock1');").close;
            }
            statement.executeQuery("SELECT release_lock('mysqlrpc_v1_"+tid+"_lock2');").close;

            // If we would unlock the mutex on servers without support to multiple
            // locks, there would be a race condition that occurs when the client
            // wants to know the tid of the worker id, it would not be able to
            // discover it, and no wakeup would be sent. To fix it, we detect the
            // versions that do NOT support multiple locking, and for those we don't
            // unlock the mutex.
            val sleepStatement = if (supportsMultipleLocks) {
              val st = conn.prepareStatement(s"SELECT (release_lock(?) = 42) OR sleep($pollTime);")
              st.setString(1, "mysqlrpc_v1_${methodName}_mutex")
              st
            } else {
              val st = conn.prepareStatement(s"SELECT get_lock(?, 60) = 42 OR sleep($pollTime);")
              st.setString(1, s"mysqlrpc_v1_${methodName}_worker")
              st
            }

            // Acquire an identifier lock. This means that if we are waiting for
            // messages on the "foo" queue, then we'll lock on mysqlrpc_v1_foo_worker
            //   This will be used by the client to obtain this server thread id.
            val workerLockStatement = conn.prepareStatement(
              "SELECT coalesce(get_lock(?, 60), 0) = 1;")
            workerLockStatement.setString(1, "mysqlrpc_v1_" + methodName + "_worker")
            rs = workerLockStatement.executeQuery()
            rs.next
            if (!rs.getBoolean(1))
              throw new Exception("there's another worker for method " + methodName)
            rs.close
            //workerLockStatement.close

            mutex.lock
            while (true) {
              conn.setAutoCommit(false)
              rs = listStatement.executeQuery()
              val msgs = new Iterator[(Long, String)] {
                def hasNext = rs.next
                def next = (rs.getLong(1), rs.getString(2))
              }.toArray
              rs.close
              for (msg <- msgs) {
                messageArrived(newReceviedMessage(msg._2))
                processed += msg._1
              }
              mutex.lock()
              val deleted = deleteProcessed()
              conn.commit()

              if (deleted < 1000) sleepStatement.executeQuery().close()
              mutex.lock()
            }
          } catch {
            case ex: Exception => if (!stopServer) log.error(ex, "Closing server")
          } finally {
            try {
              if (processed.length > 0) deleteProcessed
            }
            if (statement != null) statement.close
            if (!conn.isClosed) conn.close
          }
        }
      }
    }
    def dispose(): Unit = {
      stopServer = true
      Try {
        val conn2 = dataSource.getConnection()
        Try {conn2.createStatement().execute(s"KILL CONNECTION $tid")}.recover {case ex => log.error(ex, "Kaboom")}
        Try {conn2.close()}.recover {case ex => log.error(ex, "Kaboom")}
      }.recover {case ex => ex.printStackTrace()}
    }
  }

  case class EFServer private[MysqlRpc](dataSource: DataSource, methodName: String, pollTime: Int) extends EndpointFactory[Server] {
    def apply(flow: Flow) = new Server(flow, dataSource, methodName, pollTime)
  }
  def Server(dataSource: DataSource, methodName: String, pollTime: Int = 60) = EFServer(dataSource, methodName, pollTime)


  //Client part
  class Client(val flow: Flow,
               val dataSource: DataSource,
               ioThreads: Int) extends endpoint.base.BaseSink {
    type SupportedTypes = (String, String) :: BatchMsg :: Wakeup :: TypeNil

    lazy val ioProfile = endpoint.base.IoProfile.threadPool(ioThreads, flow.name + "-mysqlrpc-client")

    lazy val conn = dataSource.getConnection();
    def start() {
      conn.setAutoCommit(false)
    }
    def dispose() {
      Try {conn.close()}.recover {case ex => log.error(ex, "Kaboom")}
    }
    def pushMessage[Payload: SupportedType](msg) {
      msg.payload match {
        case (m: String, methodName: String) =>
          send(Seq(m), methodName)
          wakeup(methodName)
        case BatchMsg(msgs, methodName, w) =>
          send(msgs, methodName)
          if (w) wakeup(methodName)
        case Wakeup(methodName) => wakeup(methodName)
      }
    }

    private def send(msgs: Seq[String], methodName: String) {
      val statement: Statement = conn.createStatement
      val insertStatement = conn.prepareStatement(
        "INSERT INTO mysqlrpc_v1(data, method) VALUES (?, ?)")
      try {

        insertStatement.setString(2, methodName)
        for (m <- msgs) {
          insertStatement.setString(1, m)
          insertStatement.executeUpdate()
        }
        conn.commit()
      } finally {
        Try (insertStatement.close())
      }
    }

    private def wakeup(methodName: String) {
      // Now it's time to wake up the consumer:
      val queryTidStatement = conn.prepareStatement("SELECT is_used_lock(?);")
      queryTidStatement.setString(1, "mysqlrpc_v1_" + methodName + "_worker")
      val rs = queryTidStatement.executeQuery()
      rs.next()
      val tid = rs.getInt(1)
      if (rs.wasNull) return // if there's no consumer, return...
      queryTidStatement.close()

      //println("waking up consumer " + tid)
      val mutex = createMutex(conn, methodName)
      val killStatement = conn.prepareStatement("KILL QUERY ?")
      killStatement.setInt(1, tid)
      mutex.lock()
      killStatement.executeUpdate()
      mutex.unlock()
    }

  }
  sealed trait ClientMsg
  case class BatchMsg(msgs: Seq[String], methodName: String, wakeup: Boolean = true) extends ClientMsg
  case class Wakeup(methodName: String) extends ClientMsg


  case class EFClient private[MysqlRpc](dataSource: DataSource, ioThreads: Int) extends EndpointFactory[Client] {
    def apply(flow: Flow) = new Client(flow, dataSource, ioThreads)
  }
  def Client(dataSource: DataSource) = EFClient(dataSource, 1)
}
