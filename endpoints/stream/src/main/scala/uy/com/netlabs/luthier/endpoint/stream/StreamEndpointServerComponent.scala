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
package stream

import language.{ implicitConversions, higherKinds }

import java.nio._
import java.nio.channels._

import scala.util._
import scala.concurrent._, duration._

import typelist._

protected trait StreamEndpointServerComponent {

  protected type ClientType <: Disposable
  protected type ClientConn <: SelectableChannel
  protected type ServerType <: ServerComponent
  protected def newClient(server: ServerType, conn: ClientConn, readBuffer: ByteBuffer): ClientType

  protected trait ServerComponent extends base.BaseSource { self: ServerType =>
    type Payload = ClientType

    val flow: Flow
    val readBuffer: Int
    /**
     * The actual serverChannel. This variable MUST be lazy. otherwise,
     * initialization will happen before the start method is called.
     */
    val serverChannel: SelectableChannel with InterruptibleChannel
    def serverChannelAccept(): ClientConn

    def accept() = {
      Option(serverChannelAccept()) map { s =>
        s.configureBlocking(false)
        newClient(this, s, ByteBuffer.allocate(readBuffer))
      }
    }

    lazy val selector = Selector.open()

    @volatile private[StreamEndpointServerComponent] var currentClients = Set.empty[ClientType]
    /**
     * This method should be called by the server with the new Client.
     */
    private def clientArrived(client: ClientType) {
      currentClients += client
      messageArrived(newReceviedMessage(client))
    }
    private var selectingFuture: Future[Unit] = _
    private var stopServer = false

    def start() {
      val selKey = serverChannel.register(selector, SelectionKey.OP_ACCEPT, new SelectionKeyReactor)
      selKey.attachment().asInstanceOf[SelectionKeyReactor].acceptor = key => {
        var it = accept()
        while (it.isDefined) {
          clientArrived(it.get)
          it = accept()
        }
      }
      selectingFuture = flow blocking new SelectionKeyReactorSelector {
        val selector = ServerComponent.this.selector
        def mustStop = stopServer
      }.selectionLoop
    }
    def dispose() {
      stopServer = true
      Try(selector.wakeup())
      Try(Await.ready(selectingFuture, 5.seconds))
      Try(serverChannel.close())
      currentClients foreach (_.dispose())
    }
  }

  protected trait ClientComponent extends Disposable { self: ClientType =>
    val server: ServerType
    val conn: ClientConn
    val readBuffer: ByteBuffer
    val key: SelectionKey = conn.register(server.selector, SelectionKey.OP_READ)

    protected[StreamEndpointServerComponent] def disposeImpl() {
      server.currentClients -= this
      key.cancel()
      try conn.close() catch { case _: Exception => }
      debug(s"$conn disposed")
    }
  }

  protected trait HandlerComponent[S, P, R, SRT <: TypeList] extends Source with Responsible with EndpointFactory[HandlerComponent[S, P, R, SRT]] {
    //abstracts
    val client: ClientType
    val reader: Consumer[S, P]
    val onReadWaitAction: ReadWaitAction[S, P]
    def registerReader(reader: ByteBuffer => Unit): Unit
    def processResponseFromRequestedMessage(m: Message[OneOf[_, SupportedResponseTypes]])

    type Payload = P
    type SupportedResponseTypes = SRT
    implicit var flow: uy.com.netlabs.luthier.Flow = _
    private var state: S = null.asInstanceOf[S]
    @volatile private var lastScheduledOnWaitAction: akka.actor.Cancellable = _

    private def consumerHandler(updateWaitAction: Boolean): Try[P] => Unit = t => {
      if (updateWaitAction) updateOnWaitAction() //received input asynchronously, so update the on wait action
      if (t.isSuccess) {
        val p = t.get
        if (onEventHandler != null) onEventHandler(newReceviedMessage(p))
        else {
          val resp = onRequestHandler(newReceviedMessage(p))
          resp.onComplete {
            case Success(m) => processResponseFromRequestedMessage(m)
            case Failure(err) => log.error(err, "Failed to respond to client")
          }(flow.workerActorsExecutionContext)
        }
      } else log.error(t.failed.get, s"Failure reading from client $client")
    }
    { //do setup
      client.onDispose { _ => flow.dispose }
      registerReader(
        Consumer.Asynchronous(reader)(consumerHandler(true))
      )
    }

    private def updateOnWaitAction() {
      if (lastScheduledOnWaitAction != null) {
        lastScheduledOnWaitAction.cancel()
      }
      lastScheduledOnWaitAction = flow.scheduleOnce(onReadWaitAction.maxWaitTime.millis) {
        val res = onReadWaitAction(state, reader)
        state = res.state
        res match {
          case byprod: reader.ByProduct => byprod.content foreach consumerHandler(false)
          case _ =>
        }
        //There is only one onWaitAction executed per timeout, that's why we do not self schedule.
        lastScheduledOnWaitAction = null
      }
    }

    def start() {updateOnWaitAction()}
    def dispose() {if (lastScheduledOnWaitAction != null) lastScheduledOnWaitAction.cancel()}
    def apply(f) = { flow = f; this }

    def canEqual(that) = that == this

    def RR: EndpointFactory[Responsible {type SupportedResponseTypes = SRT; type Payload = P}] = this
    def OW: EndpointFactory[Source {type Payload = P}] = this
  }
}