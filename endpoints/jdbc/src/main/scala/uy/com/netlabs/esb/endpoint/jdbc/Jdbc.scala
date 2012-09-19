package uy.com.netlabs.esb
package endpoint.jdbc

import javax.sql.DataSource

object Jdbc {
  private case class EFP[R](query: String, rowMapper: Row => R,dataSource: DataSource, ioThreads: Int) extends EndpointFactory[JdbcPull[R]] {
    def apply(flow: Flow) = new JdbcPull(flow, query, rowMapper, dataSource, ioThreads)
  }
  def const[R](query: String,
               rowMapper: Row => R,
               dataSource: DataSource,
               ioThreads: Int = 1): EndpointFactory[JdbcPull[R]] = EFP(query, rowMapper, dataSource, ioThreads)


  private case class EFA[R](query: String, rowMapper: Row => R,dataSource: DataSource, ioThreads: Int) extends EndpointFactory[JdbcAskable[R]] {
    def apply(flow: Flow) = new JdbcAskable(flow, query, rowMapper, dataSource, ioThreads)
  }
  def parameterized[R](query: String,
                       rowMapper: Row => R,
                       dataSource: DataSource,
                       ioThreads: Int = 1): EndpointFactory[JdbcAskable[R]] = EFA(query, rowMapper, dataSource, ioThreads)
}