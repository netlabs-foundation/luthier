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

class JdbcPull[R](val flow: Flow,
                  val query: String,
                  val rowMapper: Row => R,
                  val dataSource: DataSource,
                  ioThreads: Int) extends endpoint.base.BasePullEndpoint {
  type Payload = IndexedSeq[R]

  @volatile private[this] var connection: Connection = _
  @volatile private[this] var preparedStatement: PreparedStatement = _

  def dispose(): Unit = {
    Try(preparedStatement.close())
    Try(connection.close())
    ioProfile.dispose()
  }
  def start(): Unit = {
    try {
      connection = dataSource.getConnection()
      preparedStatement = connection.prepareStatement(query)
    }
  }

  lazy val ioProfile = endpoint.base.IoProfile.threadPool(ioThreads)

  protected def retrieveMessage(mf): uy.com.netlabs.luthier.Message[Payload] = {
    val res = Try {
      val rs = preparedStatement.executeQuery()
      val res = collection.mutable.ArrayBuffer[R]()
      while (rs.next) res += rowMapper(Row(rs))
      res: Payload
    }
    mf(res.get)
  }
}

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

  private[this] val ioProfile = endpoint.base.IoProfile.threadPool(ioThreads)

  //TODO: Honor the timeout
  def ask[Payload: SupportedType](msg: Message[Payload], timeOut: FiniteDuration): Future[Message[Response]] = {
    Future {
      val args = {
        msg.payload match {
          case is: IndexedSeq[_] => is
          case prod: Product     => prod.productIterator.toIndexedSeq
        }
      }
      val res = Try {
        var ps = preparedStatement.get()
        if (ps == null) {
          ps = connection.prepareStatement(query)
          preparedStatement.set(ps)
        }
        ps.clearParameters()
        var i = 0
        while ({ i += 1; i <= args.length }) ps.setObject(i, args(i - 1))
        val rs = ps.executeQuery()
        val res = collection.mutable.ArrayBuffer[R]()
        while (rs.next) res += rowMapper(Row(rs))
        res: Response
      }
      msg map (_ => res.get)
    }(ioProfile.executionContext)
  }
}

trait Row {
  def get[Type: ClassTag](col: String): Type
  def get[Type: ClassTag](index: Int): Type
}
private[jdbc] object Row {
  import java.lang.{ Byte => JByte, Short => JShort, Integer => JInt, Long => JLong, Float => JFloat, Double => JDouble, Character => JChar, Boolean => JBoolean }
  class ClassExtractor(valid: Class[_]*) {
    def unapply(c: Class[_]) = valid.find(_ == c)
  }
  def apply(resultSet: ResultSet) = new Row {
    def get[Type: ClassTag](col: String): Type = {
      classTag[Type].runtimeClass match {
        case c if c == classOf[JByte] || c == JByte.TYPE => resultSet.getByte(col).asInstanceOf[Type]
        case c if c == classOf[JShort] || c == JShort.TYPE => resultSet.getShort(col).asInstanceOf[Type]
        case c if c == classOf[JInt] || c == JInt.TYPE => resultSet.getInt(col).asInstanceOf[Type]
        case c if c == classOf[JLong] || c == JLong.TYPE => resultSet.getLong(col).asInstanceOf[Type]
        case c if c == classOf[JFloat] || c == JFloat.TYPE => resultSet.getFloat(col).asInstanceOf[Type]
        case c if c == classOf[JDouble] || c == JDouble.TYPE => resultSet.getDouble(col).asInstanceOf[Type]
        case c if c == classOf[JBoolean] || c == JBoolean.TYPE => resultSet.getBoolean(col).asInstanceOf[Type]
        case c if c == classOf[String] => resultSet.getString(col).asInstanceOf[Type]
        case c if c == classOf[java.math.BigDecimal] => resultSet.getBigDecimal(col).asInstanceOf[Type]
        case c if c == classOf[Array[Byte]] => resultSet.getBytes(col).asInstanceOf[Type]
        case c if c == classOf[java.sql.Blob] => resultSet.getBlob(col).asInstanceOf[Type]
        case c if c == classOf[java.sql.Clob] => resultSet.getClob(col).asInstanceOf[Type]
        case c if c == classOf[java.util.Date] => resultSet.getDate(col).asInstanceOf[Type]
        case other => throw new IllegalArgumentException(s"No direct sql mapping for $other")
      }
    }
    def get[Type: ClassTag](col: Int): Type = {
      classTag[Type].runtimeClass match {
        case c if c == classOf[JByte] || c == JByte.TYPE => resultSet.getByte(col).asInstanceOf[Type]
        case c if c == classOf[JShort] || c == JShort.TYPE => resultSet.getShort(col).asInstanceOf[Type]
        case c if c == classOf[JInt] || c == JInt.TYPE => resultSet.getInt(col).asInstanceOf[Type]
        case c if c == classOf[JLong] || c == JLong.TYPE => resultSet.getLong(col).asInstanceOf[Type]
        case c if c == classOf[JFloat] || c == JFloat.TYPE => resultSet.getFloat(col).asInstanceOf[Type]
        case c if c == classOf[JDouble] || c == JDouble.TYPE => resultSet.getDouble(col).asInstanceOf[Type]
        case c if c == classOf[JBoolean] || c == JBoolean.TYPE => resultSet.getBoolean(col).asInstanceOf[Type]
        case c if c == classOf[String] => resultSet.getString(col).asInstanceOf[Type]
        case c if c == classOf[java.math.BigDecimal] => resultSet.getBigDecimal(col).asInstanceOf[Type]
        case c if c == classOf[Array[Byte]] => resultSet.getBytes(col).asInstanceOf[Type]
        case c if c == classOf[java.sql.Blob] => resultSet.getBlob(col).asInstanceOf[Type]
        case c if c == classOf[java.sql.Clob] => resultSet.getClob(col).asInstanceOf[Type]
        case c if c == classOf[java.util.Date] => resultSet.getDate(col).asInstanceOf[Type]
        case other => throw new IllegalArgumentException(s"No direct sql mapping for $other")
      }
    }
  }
}
