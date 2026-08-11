package com.myvideolibrary.app.security

object PinRetryPolicy {
    private const val MAX_ATTEMPTS = 5
    private const val LOCK_DURATION_MS = 30_000L

    fun lockDurationMs(failedAttempts: Int): Long =
        if (failedAttempts >= MAX_ATTEMPTS) LOCK_DURATION_MS else 0L
}
