/*
 * TLS for the Foxy edge.
 *
 * Uses the device trust store plus the two public roots the Fastly MASQUE edges chain to.
 * Without them, older Android trust stores reject `*.m1.fastly-masque.net` with
 * "Trust anchor for certification path not found" and the engine cannot dial at all.
 */
package com.uacspoofer.mobile.engine.foxy

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal object FoxyTrust {
    private val composite: X509TrustManager by lazy { CompositeTrustManager(systemManagers() + bundledManagers()) }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(composite), null)
        }.socketFactory
    }

    private fun systemManagers(): List<X509TrustManager> = runCatching {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>()
    }.getOrDefault(emptyList())

    private fun bundledManagers(): List<X509TrustManager> = runCatching {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val factory = CertificateFactory.getInstance("X.509")
        FoxyRootCas.ALL.forEachIndexed { index, pem ->
            val cert = factory.generateCertificate(pem.byteInputStream()) as X509Certificate
            keyStore.setCertificateEntry("foxy-root-$index", cert)
        }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }.trustManagers.filterIsInstance<X509TrustManager>()
    }.getOrDefault(emptyList())

    private class CompositeTrustManager(
        private val delegates: List<X509TrustManager>,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var last: CertificateException? = null
            for (tm in delegates) {
                try {
                    tm.checkClientTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("No trust manager accepted the client chain")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var last: CertificateException? = null
            for (tm in delegates) {
                try {
                    tm.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("No trust manager accepted the server chain")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
    }
}
