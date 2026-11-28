/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FxaCrypto.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 * PBKDF2 is implemented via HMAC-SHA256 (RFC 2898) for API 24 compatibility
 * (javax.crypto PBKDF2WithHmacSHA256 requires API 26+).
 */
package com.uacspoofer.mobile.engine.foxy

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object FxaCrypto {
    private const val PROTOCOL_VERSION = "identity.mozilla.com/picl/v1/"
    private const val PBKDF2_ROUNDS = 1000
    private const val STRETCHED_PW_LEN = 32
    private const val HKDF_LEN = 32
    private val random = SecureRandom()

    fun deriveQuickStretch(email: String, password: String): ByteArray {
        val salt = (PROTOCOL_VERSION + "quickStretch:" + email).toByteArray(StandardCharsets.UTF_8)
        return pbkdf2HmacSha256(
            password.toByteArray(StandardCharsets.UTF_8), salt, PBKDF2_ROUNDS, STRETCHED_PW_LEN,
        )
    }

    fun deriveAuthPw(email: String, password: String): String =
        hkdfExpand(deriveQuickStretch(email, password), PROTOCOL_VERSION + "authPW", HKDF_LEN)
            .toHex()

    fun deriveHawkCredentials(sessionTokenHex: String): Pair<String, ByteArray> {
        val expanded = hkdfExpand(hexToBytes(sessionTokenHex), PROTOCOL_VERSION + "sessionToken", 64)
        val tokenId = expanded.copyOfRange(0, 32).toHex()
        val hmacKey = expanded.copyOfRange(32, 64)
        return tokenId to hmacKey
    }

    fun hkdfExpand(ikm: ByteArray, info: String, length: Int, salt: ByteArray? = null): ByteArray {
        val actualSalt = salt ?: ByteArray(32)
        val prk = hmacSha256(actualSalt, ikm)
        val infoBytes = info.toByteArray(StandardCharsets.UTF_8)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            val block = ByteArray(previous.size + infoBytes.size + 1)
            System.arraycopy(previous, 0, block, 0, previous.size)
            System.arraycopy(infoBytes, 0, block, previous.size, infoBytes.size)
            block[block.size - 1] = counter.toByte()
            previous = hmacSha256(prk, block)
            val take = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, output, written, take)
            written += take
            counter++
        }
        return output
    }

    fun buildHawkHeader(
        method: String,
        url: java.net.URL,
        tokenId: String,
        hmacKey: ByteArray,
        body: ByteArray? = null,
    ): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonceBytes = ByteArray(6)
        random.nextBytes(nonceBytes)
        val nonce = android.util.Base64.encodeToString(nonceBytes, android.util.Base64.NO_WRAP)
            .replace("+", "-").replace("/", "_").trimEnd('=')
        val pathAndQuery = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
        val host = url.host
        val port = if (url.port > 0) url.port else if (url.protocol == "https") 443 else 80
        var hash = ""
        if (body != null && body.isNotEmpty()) {
            val prefix = "hawk.1.payload\napplication/json\n".toByteArray(StandardCharsets.UTF_8)
            val suffix = "\n".toByteArray(StandardCharsets.UTF_8)
            val buffer = ByteArray(prefix.size + body.size + suffix.size)
            System.arraycopy(prefix, 0, buffer, 0, prefix.size)
            System.arraycopy(body, 0, buffer, prefix.size, body.size)
            System.arraycopy(suffix, 0, buffer, prefix.size + body.size, suffix.size)
            hash = android.util.Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(buffer), android.util.Base64.NO_WRAP,
            )
        }
        val macInput = "hawk.1.header\n$ts\n$nonce\n${method.uppercase()}\n$pathAndQuery\n$host\n$port\n$hash\n\n"
        val mac = android.util.Base64.encodeToString(
            hmacSha256(hmacKey, macInput.toByteArray(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP,
        )
        var header = "Hawk id=\"$tokenId\", ts=\"$ts\", nonce=\"$nonce\", mac=\"$mac\""
        if (hash.isNotEmpty()) header += ", hash=\"$hash\""
        return header
    }

    internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val hLen = 32
        val blockCount = (dkLen + hLen - 1) / hLen
        val dk = ByteArray(dkLen)
        var offset = 0
        for (block in 1..blockCount) {
            val blockIndex = byteArrayOf(
                (block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte(),
            )
            var u = hmacSha256(password, salt + blockIndex)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = hmacSha256(password, u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val take = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, dk, offset, take)
            offset += take
        }
        return dk
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Invalid hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
