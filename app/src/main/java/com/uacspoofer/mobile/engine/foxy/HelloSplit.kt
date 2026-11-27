/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/HelloSplitHelper.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 */
package com.uacspoofer.mobile.engine.foxy

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class HelloSplitMode { OFF, SNI, FIRST_BYTE, RECORD }

object HelloSplitHelper {
    private const val TLS_RECORD_HEADER_LENGTH = 5

    fun isClientHello(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean =
        length > TLS_RECORD_HEADER_LENGTH &&
            data[offset] == 0x16.toByte() &&
            data[offset + 1] == 0x03.toByte() &&
            data[offset + 5] == 0x01.toByte()

    fun findSniServerNameOffset(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        if (!isClientHello(data, offset, length)) return -1
        var at = offset + 5 + 4 + 2 + 32
        val end = offset + length
        fun skipLengthPrefixed(lengthBytes: Int): Boolean {
            if (at + lengthBytes > end) return false
            var n = 0
            for (i in 0 until lengthBytes) n = (n shl 8) or (data[at + i].toInt() and 0xff)
            at += lengthBytes + n
            return at <= end
        }
        if (!skipLengthPrefixed(1) || !skipLengthPrefixed(2) || !skipLengthPrefixed(1)) return -1
        if (at + 2 > end) return -1
        val extensionsLength = ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)
        at += 2
        val extEnd = minOf(at + extensionsLength, end)
        while (at + 4 <= extEnd) {
            val kind = ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)
            val size = ((data[at + 2].toInt() and 0xff) shl 8) or (data[at + 3].toInt() and 0xff)
            val body = at + 4
            if (body + size > extEnd) return -1
            if (kind == 0) {
                if (size < 6 || data[body + 2] != 0.toByte()) return -1
                val nameLen = ((data[body + 3].toInt() and 0xff) shl 8) or (data[body + 4].toInt() and 0xff)
                if (nameLen == 0 || body + 5 + nameLen > extEnd) return -1
                return body + 5
            }
            at = body + size
        }
        return -1
    }

    fun buildSegments(data: ByteArray, mode: HelloSplitMode, offset: Int = 0, length: Int = data.size): List<ByteArray> {
        if (mode == HelloSplitMode.OFF || !isClientHello(data, offset, length) || length <= 1) {
            return listOf(data.copyOfRange(offset, offset + length))
        }
        if (mode == HelloSplitMode.FIRST_BYTE) {
            return listOf(
                data.copyOfRange(offset, offset + 1),
                data.copyOfRange(offset + 1, offset + length),
            )
        }
        val sniStart = findSniServerNameOffset(data, offset, length)
        if (sniStart <= 0 || sniStart + 1 >= offset + length) {
            return listOf(
                data.copyOfRange(offset, offset + 1),
                data.copyOfRange(offset + 1, offset + length),
            )
        }
        if (mode == HelloSplitMode.RECORD) {
            val recordPayloadLen = ((data[offset + 3].toInt() and 0xff) shl 8) or (data[offset + 4].toInt() and 0xff)
            if (recordPayloadLen == length - TLS_RECORD_HEADER_LENGTH) {
                val cut = sniStart + 1
                val rec1 = ByteArray(cut - offset)
                System.arraycopy(data, offset, rec1, 0, 3)
                rec1[3] = ((cut - offset - TLS_RECORD_HEADER_LENGTH) ushr 8).toByte()
                rec1[4] = ((cut - offset - TLS_RECORD_HEADER_LENGTH) and 0xff).toByte()
                System.arraycopy(data, offset + TLS_RECORD_HEADER_LENGTH, rec1, TLS_RECORD_HEADER_LENGTH, cut - offset - TLS_RECORD_HEADER_LENGTH)
                val secondPayloadLen = offset + length - cut
                val rec2 = ByteArray(TLS_RECORD_HEADER_LENGTH + secondPayloadLen)
                System.arraycopy(data, offset, rec2, 0, 3)
                rec2[3] = ((secondPayloadLen) ushr 8).toByte()
                rec2[4] = ((secondPayloadLen) and 0xff).toByte()
                System.arraycopy(data, cut, rec2, TLS_RECORD_HEADER_LENGTH, secondPayloadLen)
                return listOf(rec1, rec2)
            }
        }
        return listOf(
            data.copyOfRange(offset, sniStart),
            data.copyOfRange(sniStart, sniStart + 1),
            data.copyOfRange(sniStart + 1, offset + length),
        )
    }
}

/** Splits only the first write when it is a TLS ClientHello (SNI mode by default). */
class HelloSplitOutputStream(
    out: OutputStream,
    private val mode: HelloSplitMode = HelloSplitMode.SNI,
) : FilterOutputStream(out) {
    private var passthrough = mode == HelloSplitMode.OFF

    override fun write(b: Int) {
        if (!passthrough) {
            passthrough = true
            val single = byteArrayOf(b.toByte())
            if (HelloSplitHelper.isClientHello(single)) {
                for (seg in HelloSplitHelper.buildSegments(single, mode)) {
                    out.write(seg)
                    out.flush()
                }
                return
            }
        }
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!passthrough) {
            passthrough = true
            if (HelloSplitHelper.isClientHello(b, off, len)) {
                for (seg in HelloSplitHelper.buildSegments(b, mode, off, len)) {
                    out.write(seg)
                    out.flush()
                }
                return
            }
        }
        out.write(b, off, len)
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }
}

internal fun ByteBuffer.u16be(offset: Int): Int =
    (get(offset).toInt() and 0xff) shl 8 or (get(offset + 1).toInt() and 0xff)
