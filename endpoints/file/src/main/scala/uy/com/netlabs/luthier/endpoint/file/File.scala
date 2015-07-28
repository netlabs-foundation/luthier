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
package endpoint.file

import shapeless.{ ::, HNil }
import java.nio.file._

object File {
  private case class EF(path: Path, charset: String, ioThreads: Int) extends EndpointFactory[FileEndpoint] {
    def apply(f: Flow) = new FileEndpoint(f, path, charset, ioThreads)
  }
  def apply(path: String, charset: String = "UTF-8",
            ioThreads: Int = 1)(implicit appContext: AppContext): EndpointFactory[FileEndpoint] = EF(appContext.rootLocation.resolve(path), charset, ioThreads)
}
class FileEndpoint(f: Flow, path: Path, charset: String, ioThreads: Int) extends endpoint.base.BasePushable with endpoint.base.BasePullable {
  type Payload = Array[Byte]
  type SupportedType = Iterable[Byte] :: Array[Byte] :: String :: java.io.Serializable :: HNil

  implicit val flow = f

  val ioProfile = endpoint.base.IoProfile.threadPool(ioThreads, flow.name + "-file-ep")

  protected def pushMessage[Payload: TypeIsSupported](msg: Message[Payload]) {
    msg.payload match {
      case it: Iterable[Byte @unchecked] => Files.write(path, it.toArray, StandardOpenOption.CREATE)
      case arr: Array[Byte] => Files.write(path, arr, StandardOpenOption.CREATE)
      case str: String => Files.write(path, str.getBytes(charset), StandardOpenOption.CREATE)
      case obj: java.io.Serializable =>
        val out = new java.io.ObjectOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE))
        try out.writeObject(obj)
        finally {
          if (out != null) out.close
        }
      case other => throw new UnsupportedOperationException("Validated type was invalid?")
    }
  }

  protected def retrieveMessage(mf) = mf(Files.readAllBytes(path))

  def dispose() {
    ioProfile.dispose()
  }
  def start() {}
}