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

import javax.sql.DataSource
import java.sql.{ Connection, PreparedStatement, ResultSet }
import scala.reflect._
import scala.concurrent.{ ExecutionContext, Future, duration }, duration._
import scala.util.Try
import typelist._

/**
 * A pull endpoint that calls a prepared statement, be warned that for better throughput
 * you need a pooled datasource.
 */
class JdbcPull[R](val flow: Flow,
                  val query: String,
                  val rowMapper: Row => R,
                  val dataSource: DataSource,
                  ioThreads: Int) extends endpoint.base.BasePullEndpoint {
  type Payload = IndexedSeq[R]

  def dispose(): Unit = {}
  def start(): Unit = {}

  lazy val ioProfile = endpoint.base.IoProfile.threadPool(ioThreads, flow.name + "-jdbc-ep")

  protected def retrieveMessage(mf): uy.com.netlabs.luthier.Message[Payload] = {
    val connection = dataSource.getConnection()
    try {
      val preparedStatement = connection.prepareStatement(query)
      val rs = preparedStatement.executeQuery()
      val res = collection.mutable.ArrayBuffer[R]()
      while (rs.next) res += rowMapper(Row(rs))
      mf(res)
    } finally {
      try connection.close() catch { case ex: Exception => }
    }
  }
}

/**
 * An askable endpoint that calls a prepared statement, be warned that for better throughput
 * you need a pooled datasource.
 */
class JdbcAskable[R](val flow: Flow,
                     val query: String,
                     val rowMapper: Row => R,
                     val dataSource: DataSource,
                     ioThreads: Int) extends Askable {
  type Response = IndexedSeq[R]
  type SupportedTypes = IndexedSeq[_ <: Any] :: Product :: TypeNil

  @volatile private[this] var connection: Connection = _
  private[this] val preparedStatement = new ThreadLocal[PreparedStatement]()

  def dispose(): Unit = {
    Try(connection.close())
    ioProfile.dispose()
  }
  def start(): Unit = {
    connection = dataSource.getConnection()
  }

  private[this] val ioProfile = endpoint.base.IoProfile.threadPool(ioThreads, flow.name)

  //TODO: Honor the timeout
  def askImpl[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration): Future[Message[Response]] = {
    Future {
      val args = {
        msg.payload match {
          case is: IndexedSeq[_] => is
          case prod: Product     => prod.productIterator.toIndexedSeq
        }
      }

      val connection = dataSource.getConnection()
      try {
        val ps = connection.prepareStatement(query)
        var i = 0
        while ({ i += 1; i <= args.length }) ps.setObject(i, args(i - 1))
        val rs = ps.executeQuery()
        val res = collection.mutable.ArrayBuffer[R]()
        while (rs.next) res += rowMapper(Row(rs))
        msg map (_ => res)
      } finally {
        try connection.close() catch { case ex: Exception => }
      }
    }(ioProfile.executionContext)
  }
}

trait Row {
  def get[Type: Row.SqlMappedType](col: String): Type
  def get[Type: Row.SqlMappedType](index: Int): Type
}
private[jdbc] object Row {
  import java.lang.{ Byte => JByte, Short => JShort, Integer => JInt, Long => JLong, Float => JFloat, Double => JDouble, Character => JChar, Boolean => JBoolean }
  @annotation.implicitNotFound("${T} is not a JDBC mapped type.")
  trait SqlMappedType[T] {
    def extract(col: Int, resultSet: ResultSet): T
    def extract(col: String, resultSet: ResultSet): T
  }
  implicit object ByteSqlMappedType extends SqlMappedType[Byte] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getByte(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getByte(col)
  }
  implicit object ShortSqlMappedType extends SqlMappedType[Short] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getShort(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getShort(col)
  }
  implicit object IntSqlMappedType extends SqlMappedType[Int] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getInt(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getInt(col)
  }
  implicit object LongSqlMappedType extends SqlMappedType[Long] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getLong(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getLong(col)
  }
  implicit object FloatSqlMappedType extends SqlMappedType[Float] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getFloat(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getFloat(col)
  }
  implicit object DoubleSqlMappedType extends SqlMappedType[Double] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getDouble(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getDouble(col)
  }
  implicit object BooleanSqlMappedType extends SqlMappedType[Boolean] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getBoolean(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getBoolean(col)
  }
  implicit object StringSqlMappedType extends SqlMappedType[String] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getString(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getString(col)
  }
  implicit object BigDecimalSqlMappedType extends SqlMappedType[java.math.BigDecimal] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getBigDecimal(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getBigDecimal(col)
  }
  implicit object ByteArraySqlMappedType extends SqlMappedType[Array[Byte]] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getBytes(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getBytes(col)
  }
  implicit object BlobSqlMappedType extends SqlMappedType[java.sql.Blob] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getBlob(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getBlob(col)
  }
  implicit object ClobSqlMappedType extends SqlMappedType[java.sql.Clob] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getClob(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getClob(col)
  }
  implicit object DateSqlMappedType extends SqlMappedType[java.util.Date] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getDate(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getDate(col)
  }
  implicit object SqlDateSqlMappedType extends SqlMappedType[java.sql.Date] {
    def extract(col: Int, resultSet: ResultSet) = resultSet.getDate(col)
    def extract(col: String, resultSet: ResultSet) = resultSet.getDate(col)
  }

  def apply(resultSet: ResultSet) = new Row {

    def get[Type: Row.SqlMappedType](col: String): Type = implicitly[Row.SqlMappedType[Type]].extract(col, resultSet)
    def get[Type: Row.SqlMappedType](col: Int): Type = implicitly[Row.SqlMappedType[Type]].extract(col, resultSet)
  }
}
