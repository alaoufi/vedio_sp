# TikTok Download Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make TikTok download retries and final failures visible, while refreshing expired media links before retrying.

**Architecture:** A small pure policy class will generate the user-facing retry state from a download source and failed attempt. `DownloadWorker` will apply that state before returning `Result.retry()`, and `DownloadsAdapter` will render the stored message for waiting jobs. Cancellation handling remains owned by `DownloadManager` so cancel and pause states are not overwritten by the worker.

**Tech Stack:** Kotlin, WorkManager, Room, JUnit 4, Android view binding.

---

### Task 1: Define retry feedback policy with a failing unit test

**Files:**
- Create: `app/src/test/java/com/myvideolibrary/app/download/DownloadRetryFeedbackTest.kt`
- Create: `app/src/main/java/com/myvideolibrary/app/download/DownloadRetryFeedback.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.myvideolibrary.app.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRetryFeedbackTest {
    @Test
    fun `TikTok retry explains that its temporary link is refreshed`() {
        assertEquals(
            "Refreshing the TikTok link and retrying…",
            DownloadRetryFeedback.messageFor("tiktok")
        )
    }

    @Test
    fun `other retry explains that download is retried`() {
        assertEquals("Retrying download…", DownloadRetryFeedback.messageFor("youtube"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests com.myvideolibrary.app.download.DownloadRetryFeedbackTest --no-daemon`

Expected: compilation failure because `DownloadRetryFeedback` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package com.myvideolibrary.app.download

internal object DownloadRetryFeedback {
    fun messageFor(source: String): String =
        if (source == "tiktok") "Refreshing the TikTok link and retrying…"
        else "Retrying download…"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat testDebugUnitTest --tests com.myvideolibrary.app.download.DownloadRetryFeedbackTest --no-daemon`

Expected: `BUILD SUCCESSFUL`.

### Task 2: Persist and render retry feedback

**Files:**
- Modify: `app/src/main/java/com/myvideolibrary/app/download/DownloadWorker.kt:128-136`
- Modify: `app/src/main/java/com/myvideolibrary/app/ui/downloads/DownloadsAdapter.kt:48-50`

- [ ] **Step 1: Write the failing rendering test**

Add this case to `DownloadRetryFeedbackTest.kt`:

```kotlin
@Test
fun `TikTok retry feedback remains non-empty`() {
    assertEquals(false, DownloadRetryFeedback.messageFor("tiktok").isBlank())
}
```

- [ ] **Step 2: Run the focused test**

Run: `./gradlew.bat testDebugUnitTest --tests com.myvideolibrary.app.download.DownloadRetryFeedbackTest --no-daemon`

Expected: `BUILD SUCCESSFUL`; this confirms the stored message contract before wiring it into Android code.

- [ ] **Step 3: Store retry feedback and render it**

Replace the retry status update in `DownloadWorker` with:

```kotlin
downloadRepository.setStatus(
    downloadId,
    DownloadStatus.WAITING,
    DownloadRetryFeedback.messageFor(download.source)
)
```

In `DownloadsAdapter`, render waiting state with:

```kotlin
DownloadStatus.WAITING -> item.errorMessage ?: ctx.getString(R.string.status_waiting)
```

Keep `refreshUrls(downloadId)` before the status update and keep `Result.retry()` unchanged.

- [ ] **Step 4: Run focused tests and full unit tests**

Run: `./gradlew.bat testDebugUnitTest --no-daemon`

Expected: `BUILD SUCCESSFUL`.

### Task 3: Release and verify

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Set the default release metadata**

Change fallback metadata to `versionCode 174` and `versionName "1.0.174"`.

- [ ] **Step 2: Build the APK**

Run: `./gradlew.bat assembleDebug --no-daemon`

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/vedio_lb.apk`.

- [ ] **Step 3: Verify package metadata and signing**

Run:

```powershell
& 'C:\mvlbuild\android-sdk\build-tools\35.0.0\aapt.exe' dump badging app\build\outputs\apk\debug\vedio_lb.apk
& 'C:\mvlbuild\android-sdk\build-tools\35.0.0\apksigner.bat' verify --verbose app\build\outputs\apk\debug\vedio_lb.apk
```

Expected: `versionCode='174'`, `versionName='1.0.174'`, and APK Signature Scheme v2 verified.

- [ ] **Step 4: Commit and push**

```powershell
git add app
git commit -m "Show TikTok download retry feedback"
git push
```

Expected: branch `agent/sync-offline-updates` contains the implementation and the existing draft pull request updates.
