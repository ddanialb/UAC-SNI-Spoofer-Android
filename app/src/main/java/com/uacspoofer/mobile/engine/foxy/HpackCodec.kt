/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/HpackCodec.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 */
package com.uacspoofer.mobile.engine.foxy

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object HpackCodec {
    private val staticTableNames = arrayOf(
        "", ":authority", ":method", ":method", ":path", ":path", ":scheme", ":scheme", ":status", ":status",
        ":status", ":status", ":status", ":status", ":status", "accept-charset", "accept-encoding", "accept-language",
        "accept-ranges", "accept", "access-control-allow-origin", "age", "allow", "authorization", "cache-control",
        "content-disposition", "content-encoding", "content-language", "content-length", "content-location",
        "content-range", "content-type", "cookie", "date", "etag", "expect", "expires", "from", "host", "if-match",
        "if-modified-since", "if-none-match", "if-range", "if-unmodified-since", "last-modified", "link", "location",
        "max-forwards", "proxy-authenticate", "proxy-authorization", "range", "referer", "refresh", "retry-after",
        "server", "set-cookie", "strict-transport-security", "transfer-encoding", "user-agent", "vary",
        "via", "www-authenticate",
    )

    // (code, bits) per RFC 7541 Appendix B — identical to Windows HuffmanCodeTable.
    private val huffmanCodeTable: Array<Pair<Long, Int>> = arrayOf(
        8184L to 13, 8388568L to 23, 268435426L to 28, 268435427L to 28, 268435428L to 28, 268435429L to 28,
        268435430L to 28, 268435431L to 28, 268435432L to 28, 16777194L to 24, 1073741820L to 30, 268435433L to 28,
        268435434L to 28, 1073741821L to 30, 268435435L to 28, 268435436L to 28, 268435437L to 28, 268435438L to 28,
        268435439L to 28, 268435440L to 28, 268435441L to 28, 268435442L to 28, 1073741822L to 30, 268435443L to 28,
        268435444L to 28, 268435445L to 28, 268435446L to 28, 268435447L to 28, 268435448L to 28, 268435449L to 28,
        268435450L to 28, 268435451L to 28, 20L to 6, 1016L to 10, 1017L to 10, 4090L to 12, 8185L to 13, 21L to 6,
        248L to 8, 2042L to 11, 1018L to 10, 1019L to 10, 249L to 8, 2043L to 11, 250L to 8, 22L to 6, 23L to 6,
        24L to 6, 0L to 5, 1L to 5, 2L to 5, 25L to 6, 26L to 6, 27L to 6, 28L to 6, 29L to 6, 30L to 6, 31L to 6,
        92L to 7, 251L to 8, 32764L to 15, 32L to 6, 4091L to 12, 1020L to 10, 8186L to 13, 33L to 6, 93L to 7,
        94L to 7, 95L to 7, 96L to 7, 97L to 7, 98L to 7, 99L to 7, 100L to 7, 101L to 7, 102L to 7, 103L to 7,
        104L to 7, 105L to 7, 106L to 7, 107L to 7, 108L to 7, 109L to 7, 110L to 7, 111L to 7, 112L to 7, 113L to 7,
        114L to 7, 252L to 8, 115L to 7, 253L to 8, 8187L to 13, 524272L to 19, 8188L to 13, 16380L to 14, 34L to 6,
        32765L to 15, 3L to 5, 35L to 6, 4L to 5, 36L to 6, 5L to 5, 37L to 6, 38L to 6, 39L to 6, 6L to 5, 116L to 7,
        117L to 7, 40L to 6, 41L to 6, 42L to 6, 7L to 5, 43L to 6, 118L to 7, 44L to 6, 8L to 5, 9L to 5, 45L to 6,
        119L to 7, 120L to 7, 121L to 7, 122L to 7, 123L to 7, 32766L to 15, 2044L to 11, 16381L to 14, 8189L to 13,
        268435452L to 28,
    )

    private class HuffmanNode(var symbol: Int = -1) {
        var left: HuffmanNode? = null
        var right: HuffmanNode? = null
    }

    private val huffmanTreeRoot: HuffmanNode = buildHuffmanTree()

    fun encodeConnectHeaders(authority: String, proxyPassToken: String): ByteArray {
        val ms = ByteArrayOutputStream()
        ms.write(0x03)
        writeString(ms, "CONNECT")
        ms.write(0x01)
        writeString(ms, authority)
        ms.write(0x0F)
        ms.write(0x22)
        writeString(ms, "Bearer $proxyPassToken")
        return ms.toByteArray()
    }

    fun decodeStatusCode(headerBlock: ByteArray, dynamicTable: MutableList<Pair<String, String>>? = null): Int =
        decodeStatusCodeImpl(headerBlock, dynamicTable)

    private fun decodeStatusCodeImpl(
        headerBlock: ByteArray,
        dynamicTable: MutableList<Pair<String, String>>?,
    ): Int {
        var pos = 0
        var resolvedStatus = 0
        while (pos < headerBlock.size) {
            val b = headerBlock[pos].toInt() and 0xff
            if ((b and 0x80) != 0) {
                val holder = intArrayOf(pos)
                val idx = readInteger(headerBlock, holder, 7)
                pos = holder[0]
                when (idx) {
                    8 -> resolvedStatus = 200
                    9 -> resolvedStatus = 204
                    10 -> resolvedStatus = 206
                    11 -> resolvedStatus = 304
                    12 -> resolvedStatus = 400
                    13 -> resolvedStatus = 404
                    14 -> resolvedStatus = 500
                    else -> {
                        if (idx >= staticTableNames.size && dynamicTable != null) {
                            val dynIdx = idx - staticTableNames.size
                            if (dynIdx < dynamicTable.size) {
                                val (dName, dVal) = dynamicTable[dynIdx]
                                if (dName.equals(":status", ignoreCase = true)) {
                                    dVal.toIntOrNull()?.let { resolvedStatus = it }
                                }
                            }
                        }
                    }
                }
            } else if ((b and 0x40) != 0) {
                val holder = intArrayOf(pos)
                val nameIndex = readInteger(headerBlock, holder, 6)
                pos = holder[0]
                val nameHolder = intArrayOf(pos)
                val name = resolveHeaderName(nameIndex, headerBlock, nameHolder, dynamicTable)
                pos = nameHolder[0]
                val valueHolder = intArrayOf(pos)
                val value = readString(headerBlock, valueHolder)
                pos = valueHolder[0]
                if (dynamicTable != null) {
                    dynamicTable.add(0, name to value)
                    if (dynamicTable.size > 128) dynamicTable.removeAt(dynamicTable.size - 1)
                }
                if (name.equals(":status", ignoreCase = true)) {
                    value.toIntOrNull()?.let { resolvedStatus = it }
                }
            } else if ((b and 0xF0) == 0x00 || (b and 0xF0) == 0x10) {
                val holder = intArrayOf(pos)
                val nameIndex = readInteger(headerBlock, holder, 4)
                pos = holder[0]
                val nameHolder = intArrayOf(pos)
                val name = resolveHeaderName(nameIndex, headerBlock, nameHolder, dynamicTable)
                pos = nameHolder[0]
                val valueHolder = intArrayOf(pos)
                val value = readString(headerBlock, valueHolder)
                pos = valueHolder[0]
                if (name.equals(":status", ignoreCase = true)) {
                    value.toIntOrNull()?.let { resolvedStatus = it }
                }
            } else if ((b and 0x20) != 0) {
                val holder = intArrayOf(pos)
                val maxSize = readInteger(headerBlock, holder, 5)
                pos = holder[0]
                if (maxSize == 0) dynamicTable?.clear()
            } else {
                pos++
            }
        }
        return resolvedStatus
    }

    private fun resolveHeaderName(
        nameIndex: Int,
        headerBlock: ByteArray,
        pos: IntArray,
        dynamicTable: MutableList<Pair<String, String>>?,
    ): String {
        if (nameIndex == 0) return readString(headerBlock, pos)
        if (nameIndex < staticTableNames.size) return staticTableNames[nameIndex]
        if (dynamicTable != null) {
            val dynIdx = nameIndex - staticTableNames.size
            if (dynIdx >= 0 && dynIdx < dynamicTable.size) return dynamicTable[dynIdx].first
        }
        return ""
    }

    private fun writeString(ms: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        writeInteger(ms, bytes.size, 7, 0)
        ms.write(bytes)
    }

    private fun writeInteger(ms: ByteArrayOutputStream, valueIn: Int, prefixBits: Int, prefixMask: Int) {
        var value = valueIn
        val maxPrefix = (1 shl prefixBits) - 1
        if (value < maxPrefix) {
            ms.write(prefixMask or value)
            return
        }
        ms.write(prefixMask or maxPrefix)
        value -= maxPrefix
        while (value >= 128) {
            ms.write((value and 0x7F) or 0x80)
            value = value ushr 7
        }
        ms.write(value)
    }

    private fun readInteger(span: ByteArray, pos: IntArray, prefixBits: Int): Int {
        if (pos[0] >= span.size) return 0
        val maxPrefix = (1 shl prefixBits) - 1
        var value = span[pos[0]++].toInt() and maxPrefix
        if (value < maxPrefix) return value
        var shift = 0
        while (pos[0] < span.size) {
            val b = span[pos[0]++].toInt() and 0xff
            value += (b and 0x7F) shl shift
            shift += 7
            if ((b and 0x80) == 0) break
        }
        return value
    }

    private fun readString(span: ByteArray, pos: IntArray): String {
        if (pos[0] >= span.size) return ""
        val huffman = (span[pos[0]].toInt() and 0x80) != 0
        val length = readInteger(span, pos, 7)
        if (length <= 0 || pos[0] + length > span.size) return ""
        val bytes = span.copyOfRange(pos[0], pos[0] + length)
        pos[0] += length
        return if (huffman) decodeHuffman(bytes) else String(bytes, StandardCharsets.UTF_8)
    }

    private fun decodeHuffman(bytes: ByteArray): String {
        val sb = StringBuilder()
        var node = huffmanTreeRoot
        for (b in bytes) {
            val byte = b.toInt() and 0xff
            for (bit in 7 downTo 0) {
                node = if (((byte ushr bit) and 1) == 0) node.left ?: return sb.toString() else node.right ?: return sb.toString()
                if (node.symbol < 0) continue
                if (node.symbol == 256) return sb.toString()
                sb.append(node.symbol.toChar())
                node = huffmanTreeRoot
            }
        }
        return sb.toString()
    }

    private fun buildHuffmanTree(): HuffmanNode {
        val root = HuffmanNode()
        for (symbol in huffmanCodeTable.indices) {
            val (code, bits) = huffmanCodeTable[symbol]
            var node = root
            for (i in bits - 1 downTo 0) {
                val bit = ((code ushr i) and 1L).toInt()
                if (bit == 0) {
                    if (node.left == null) node.left = HuffmanNode()
                    node = node.left!!
                } else {
                    if (node.right == null) node.right = HuffmanNode()
                    node = node.right!!
                }
            }
            node.symbol = symbol
        }
        return root
    }
}
