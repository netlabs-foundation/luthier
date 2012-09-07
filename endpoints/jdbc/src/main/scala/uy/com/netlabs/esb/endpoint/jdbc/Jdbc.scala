package uy.com.netlabs.esb
package endpoint.jdbc

import javax.sql.DataSource

object Jdbc {
  def const[R](query: String,
               rowMapper: Row => R,
               dataSource: DataSource,
               ioThreads: Int = 1) = new EndpointFactory[JdbcPull[R]] {

    def apply(flow: Flow) = new JdbcPull(flow, query, rowMapper, dataSource, ioThreads)
  }

  def parameterized[R](query: String,
                       rowMapper: Row => R,
                       dataSource: DataSource,
                       ioThreads: Int = 1) = new EndpointFactory[JdbcAskable[R]] {

    def apply(flow: Flow) = new JdbcAskable(flow, query, rowMapper, dataSource, ioThreads)
  }
}