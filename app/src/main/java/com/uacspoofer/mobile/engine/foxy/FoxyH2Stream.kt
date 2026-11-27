/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FoxyH2Stream.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 */
package com.uacspoofer.mobile.engine.foxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FoxyH2Stream(
    val streamId: Int,
    private val session: FoxyH2Session,
    initialSendWindow: Int = 65535,
) {
    private val statusDeferred = CompletableDeferred<Int>()
    private val inboundLock = Any()
    private val inboundQueue: ArrayDeque<ByteArray> = ArrayDeque()
    /** Coalescing signal: kotlinx Semaphore(0) is illegal (it requires >= 1 permit). */
    private val inboundSignal = Channel<Unit>(Channel.CONFLATED)
    private var currentChunk: ByteArray = ByteArray(0)
    private var currentOffset = 0
    private var completed = false
    private var error: Throwable? = null
    @Volatile private var disposed = false
    @Volatile private var writeClosed = false
    @Volatile private var endStreamSent = false
    private val sendWindow = AtomicLong(initialSendWindow.toLong())
    private val pendingWindowUpdate = AtomicInteger(0)

    fun sendWindowValue(): Long = sendWindow.get()
    fun addSendWindow(delta: Long) { sendWindow.addAndGet(delta) }
    fun ensurePositiveSendWindow(minWindow: Long) {
        sendWindow.updateAndGet { if (it < minWindow) minWindow else it }
    }

    suspend fun waitForStatus(): Int = statusDeferred.await()

    fun onHeadersReceived(status: Int) { statusDeferred.complete(status) }

    fun failUnprocessed(reason: String) {
        writeClosed = true
        val ex = IOException(reason)
        statusDeferred.completeExceptionally(ex)
        synchronized(inboundLock) {
            error = ex
            runCatching { inboundSignal.trySend(Unit) }
        }
    }

    fun onDataReceived(data: ByteArray, isEnd: Boolean) {
        if (data.isNotEmpty()) {
            synchronized(inboundLock) {
                inboundQueue.add(data)
                runCatching { inboundSignal.trySend(Unit) }
            }
        }
        if (isEnd) {
            synchronized(inboundLock) {
                completed = true
                runCatching { inboundSignal.trySend(Unit) }
            }
            session.removeStream(streamId)
        }
    }

    fun onResetReceived() {
        val ex = IOException("HTTP/2 stream was reset by the remote peer.")
        writeClosed = true
        statusDeferred.completeExceptionally(ex)
        synchronized(inboundLock) {
            error = ex
            runCatching { inboundSignal.trySend(Unit) }
        }
    }

    suspend fun sendEndStream() {
        if (disposed || writeClosed || endStreamSent) return
        endStreamSent = true
        try {
            session.sendDataFrame(streamId, ByteArray(0), endStream = true)
        } catch (_: Exception) {
        }
    }

    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (disposed) return 0
        while (true) {
            var take = 0
            var queueEmpty = false
            synchronized(inboundLock) {
                error?.let { throw it }
                val remaining = currentChunk.size - currentOffset
                if (remaining > 0) {
                    take = minOf(remaining, length)
                    System.arraycopy(currentChunk, currentOffset, buffer, offset, take)
                    currentOffset += take
                } else if (inboundQueue.isNotEmpty()) {
                    currentChunk = inboundQueue.removeFirst()
                    currentOffset = 0
                    take = minOf(currentChunk.size, length)
                    System.arraycopy(currentChunk, 0, buffer, offset, take)
                    currentOffset = take
                } else if (completed) {
                    return 0
                }
                queueEmpty = (currentChunk.size - currentOffset == 0) && inboundQueue.isEmpty()
            }
            if (take > 0) {
                val pending = pendingWindowUpdate.addAndGet(take)
                if (pending >= WINDOW_UPDATE_BATCH_BYTES || (queueEmpty && pending >= 4096)) {
                    val flush = pendingWindowUpdate.getAndSet(0)
                    if (flush > 0) session.sendWindowUpdatePair(streamId, flush)
                }
                return take
            }
            inboundSignal.receive()
        }
    }

    suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            if (disposed || writeClosed) return
            val chunkLen = minOf(end - pos, MAX_DATA_CHUNK)
            session.sendDataFrame(streamId, buffer.copyOfRange(pos, pos + chunkLen), endStream = false)
            pos += chunkLen
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        var unconsumed = 0
        var wasCompleted: Boolean
        synchronized(inboundLock) {
            wasCompleted = completed
            unconsumed = currentChunk.size - currentOffset
            currentChunk = ByteArray(0)
            currentOffset = 0
            while (inboundQueue.isNotEmpty()) unconsumed += inboundQueue.removeFirst().size
            completed = true
            runCatching { inboundSignal.trySend(Unit) }
        }
        val connCredit = unconsumed + maxOf(0, pendingWindowUpdate.getAndSet(0))
        if (connCredit > 0) session.sendWindowUpdate(0, connCredit)
        session.removeStream(streamId)
        if (!(wasCompleted && endStreamSent)) session.sendRstStream(streamId, 0)
    }

    companion object {
        private const val MAX_DATA_CHUNK = 16384
        private const val WINDOW_UPDATE_BATCH_BYTES = 16384
    }
}
