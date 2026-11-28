package com.uacspoofer.mobile.engine.foxy

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Keeps an unexpected failure inside a Foxy background job from taking the whole process down.
 *
 * Disconnecting closes the edge sockets while the H2 reader, the stream writers and the session
 * pool are still running, so those jobs can fail with a socket exception at the exact moment the
 * user taps disconnect. Without a handler that exception is uncaught and Android kills the app,
 * which is what "the app closes when I disconnect" looked like.
 */
internal fun foxyCoroutineGuard(tag: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
    if (error is CancellationException) return@CoroutineExceptionHandler
    runCatching {
        AppLogRepository.warning(LogSource.APP, "Foxy: background task ($tag) failed", error)
    }
}

/** Runs [block] and turns any failure into a log line, never an uncaught exception. */
internal suspend fun foxyRunQuietly(tag: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        runCatching {
            AppLogRepository.warning(LogSource.APP, "Foxy: background task ($tag) failed", error)
        }
    }
}
