package com.myvideolibrary.app.util

import kotlinx.coroutines.withTimeoutOrNull

internal object SlideshowAttempt {
    private data class Completed(val error: String?)

    suspend fun run(timeoutMs: Long, stage: () -> String, encode: suspend () -> String?): String? {
        // The encoder's null means success. Only a missing Completed result
        // means that withTimeoutOrNull expired; keep these outcomes distinct.
        val completed = withTimeoutOrNull(timeoutMs) { Completed(encode()) }
        return if (completed == null) "timed out @ ${stage()}" else completed.error
    }
}
