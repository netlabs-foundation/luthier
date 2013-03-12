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
package endpoint.http

import com.ning.http.client.{AsyncHttpClientConfig}
import javax.net.ssl.{SSLContext => JSslContext, _}

import java.nio.file._

import scala.collection.JavaConverters._

case class SslKeys(paths: String*)
case class SslCerts(paths: String*)

object SSLContext {
  private implicit class ByteArrayOps(val bytes: Array[Byte]) extends AnyVal {
    def asStream = new java.io.ByteArrayInputStream(bytes)
  }
  /**
   * Provisionary documentation: keys MUST be in pkcs8 DER format in order to be read by standard java security.
   * You can convert them from other formats like this
   * {{openssl pkcs8 -topk8 -inform PEM -outform DER -in ~/myKey.in.key -nocrypt > ~/myKey.out.der}}
   */
  def apply(authKeys: SslKeys, authCerts: SslCerts, peerCerts: SslCerts,
            sslProtocol: String = null, sslProvider: String = null)(implicit appContext: AppContext): JSslContext = {
    val defaultSslContext = JSslContext.getDefault
    val myContext = JSslContext.getInstance(Option(sslProtocol).getOrElse(defaultSslContext.getProtocol),
                                            Option(sslProvider).getOrElse(defaultSslContext.getProvider.getName))
    val kmf = KeyManagerFactory.getInstance("PKIX")
    val keyStore = java.security.KeyStore.getInstance("JKS")
    keyStore.load(null) //just initialize it. Thank you sun for your really intuitive interfaces...

    val keyFactory = java.security.KeyFactory.getInstance("RSA")
    for (loc <- authKeys.paths) {
      val keyLocation = appContext.rootLocation.resolve(loc)
      val keyData = Files.readAllBytes(keyLocation)
      val privateKey = keyFactory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyData))
    }

    val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
    for (loc <- authCerts.paths) {
      val certLocation = appContext.rootLocation.resolve(loc)
      val in = Files.newInputStream(certLocation)
      val certData = Files.readAllBytes(certLocation)
      val cert = certFactory.generateCertificate(certData.asStream)
      keyStore.setCertificateEntry(certLocation.getFileName.toString, cert)
    }
    kmf.init(keyStore, null)

    val tmf = TrustManagerFactory.getInstance("PKIX")
    val trustStore = java.security.KeyStore.getInstance("JKS")
    trustStore.load(null)

    for (loc <- peerCerts.paths) {
      val certLocation = appContext.rootLocation.resolve(loc)
      val certData = Files.readAllBytes(certLocation)
      val cert = certFactory.generateCertificate(certData.asStream)
      trustStore.setCertificateEntry(certLocation.getFileName.toString, cert)
    }


//    trustStore.aliases.asScala.map(a => a->scala.util.Try(trustStore.getCertificate(a))) foreach println

    tmf.init(new javax.net.ssl.CertPathTrustManagerParameters(new java.security.cert.PKIXBuilderParameters(trustStore, null)))

    myContext.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
    myContext
  }
}

/**
 * Scala builder for AsyncHttpClientConfig, from java, just use AsyncHttpClientConfig.Builder
 */
object ClientConfig {
  type Bool = java.lang.Boolean
  def apply(
    allowPoolingCoonnection: Bool = null,
    asyncHttpClientProviderConfig: com.ning.http.client.AsyncHttpProviderConfig[_, _] = null,
    compressionEnabled: Bool = null,
    connectionsPool: com.ning.http.client.ConnectionsPool[_, _] = null,
    connectionTimeoutInMs: java.lang.Integer = null,
    executorService: java.util.concurrent.ExecutorService = null,
    followsRedirects: Bool = null,
    idleConnectionInPoolTimeoutInMs: java.lang.Integer = null,
    maximumConnectionsPerHost: java.lang.Integer = null,
    maximumConnectionsTotal: java.lang.Integer = null,
    maximumNumberOfRedirects: java.lang.Integer = null,
    maxRequestRetry: java.lang.Integer = null,
    proxyServer: com.ning.http.client.ProxyServer = null,
    realm: com.ning.http.client.Realm = null,
    requestCompressionLevel: java.lang.Integer = null,
    requestTimeoutInMs: java.lang.Integer = null,
    scheduledExecutorService: java.util.concurrent.ScheduledExecutorService = null,
    sslContext: JSslContext = null,
    sslEngineFactory: com.ning.http.client.SSLEngineFactory = null,
    userAgent: String = null
  ): AsyncHttpClientConfig = {
    val configBuilder = new AsyncHttpClientConfig.Builder()
    if (allowPoolingCoonnection != null) configBuilder.setAllowPoolingConnection(allowPoolingCoonnection)
    if (asyncHttpClientProviderConfig != null) configBuilder.setAsyncHttpClientProviderConfig(asyncHttpClientProviderConfig)
    if (compressionEnabled != null) configBuilder.setCompressionEnabled(compressionEnabled)
    if (connectionsPool != null) configBuilder.setConnectionsPool(connectionsPool)
    if(connectionTimeoutInMs != null) configBuilder.setConnectionTimeoutInMs(connectionTimeoutInMs)
    if (executorService != null) configBuilder.setExecutorService(executorService)
    if (followsRedirects != null) configBuilder.setFollowRedirects(followsRedirects)
    if (idleConnectionInPoolTimeoutInMs != null) configBuilder.setIdleConnectionInPoolTimeoutInMs(idleConnectionInPoolTimeoutInMs)
    if (maximumConnectionsPerHost != null) configBuilder.setMaximumConnectionsPerHost(maximumConnectionsPerHost)
    if (maximumConnectionsTotal != null) configBuilder.setMaximumConnectionsTotal(maximumConnectionsTotal)
    if (maximumNumberOfRedirects != null) configBuilder.setMaximumNumberOfRedirects(maximumNumberOfRedirects)
    if (maxRequestRetry != null) configBuilder.setMaxRequestRetry(maxRequestRetry)
    if (proxyServer != null) configBuilder.setProxyServer(proxyServer)
    if (realm != null) configBuilder.setRealm(realm)
    if (requestCompressionLevel != null) configBuilder.setRequestCompressionLevel(requestCompressionLevel)
    if (requestTimeoutInMs != null) configBuilder.setRequestTimeoutInMs(requestTimeoutInMs)
    if (scheduledExecutorService != null) configBuilder.setScheduledExecutorService(scheduledExecutorService)
    if (sslContext != null) configBuilder.setSSLContext(sslContext)
    if (sslEngineFactory != null) configBuilder.setSSLEngineFactory(sslEngineFactory)
    if (userAgent != null) configBuilder.setUserAgent(userAgent)
    configBuilder.build()
  }
}
