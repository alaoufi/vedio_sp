# تحسين الصوت والمؤثرات Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** إضافة تحكم محفوظ من 0 إلى 200 بالمئة ومؤثرات صوتية اختيارية للمشغّل دون تغيير واجهته الأساسية أو التأثير في صوت الجهاز.

**Architecture:** تحفظ `SettingsEntity` تفضيلات الصوت ضمن الإعدادات الحالية، وتعرضها `SettingsViewModel` في قسم جديد. تحول `AudioGainPolicy` النسبة إلى مستوى ExoPlayer وكسب `LoudnessEnhancer` القابل للاختبار، بينما يدير `PlayerAudioEffects` مؤثرات Android المرتبطة بجلسة المشغّل ويتجاوز عدم الدعم بأمان.

**Tech Stack:** Kotlin، Room، Hilt، Material Components، Android `android.media.audiofx`، Media3 ExoPlayer، JUnit 4.

---

## هيكل الملفات

- Create: `app/src/main/java/com/myvideolibrary/app/ui/player/AudioGainPolicy.kt` — تحويل النسبة وحماية الحدود، بلا اعتماد على Android.
- Create: `app/src/main/java/com/myvideolibrary/app/ui/player/PlayerAudioEffects.kt` — دورة حياة مؤثرات Android لجلسة ExoPlayer.
- Create: `app/src/test/java/com/myvideolibrary/app/ui/player/AudioGainPolicyTest.kt` — اختبارات سياسة الكسب.
- Modify: `app/src/main/java/com/myvideolibrary/app/data/local/entity/SettingsEntity.kt` — حقول الإعدادات الافتراضية.
- Modify: `app/src/main/java/com/myvideolibrary/app/data/local/AppDatabase.kt` — نسخة Room 16.
- Modify: `app/src/main/java/com/myvideolibrary/app/data/local/Migrations.kt` — انتقال 15 إلى 16.
- Modify: `app/src/main/java/com/myvideolibrary/app/di/DatabaseModule.kt` — تسجيل الانتقال.
- Modify: `app/src/main/java/com/myvideolibrary/app/ui/settings/SettingsViewModel.kt` — الحالة ودوال الحفظ.
- Modify: `app/src/main/java/com/myvideolibrary/app/ui/settings/SettingsActivity.kt` — ربط المنزلق والمفاتيح وعرض القيم.
- Modify: `app/src/main/res/layout/activity_settings.xml` — قسم «الصوت والمؤثرات».
- Modify: `app/src/main/res/values/strings.xml` — النصوص.
- Modify: `app/src/main/java/com/myvideolibrary/app/ui/player/PlayerActivity.kt` — مراقبة التفضيلات واختصار القائمة وتحرير المؤثرات.
- Modify: `app/build.gradle.kts` و`app/src/main/java/com/myvideolibrary/app/ui/settings/ReleaseNotes.kt` — إصدار 1.0.175 وملاحظاته.

### Task 1: سياسة الكسب القابلة لاختبار الوحدة

**Files:**

- Create: `app/src/test/java/com/myvideolibrary/app/ui/player/AudioGainPolicyTest.kt`
- Create: `app/src/main/java/com/myvideolibrary/app/ui/player/AudioGainPolicy.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
class AudioGainPolicyTest {
    @Test fun `normal volume keeps player gain and no loudness boost`() {
        assertEquals(AudioGain(1f, 0), AudioGainPolicy.fromPercent(100))
    }

    @Test fun `boost range keeps player at one and maps remaining percent`() {
        assertEquals(AudioGain(1f, 10), AudioGainPolicy.fromPercent(101))
        assertEquals(AudioGain(1f, 1000), AudioGainPolicy.fromPercent(200))
    }

    @Test fun `values outside range are clamped`() {
        assertEquals(AudioGain(0f, 0), AudioGainPolicy.fromPercent(-8))
        assertEquals(AudioGain(1f, 1000), AudioGainPolicy.fromPercent(250))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests "com.myvideolibrary.app.ui.player.AudioGainPolicyTest" --no-daemon`

Expected: FAIL because `AudioGain` and `AudioGainPolicy` do not exist.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
data class AudioGain(val playerVolume: Float, val loudnessGainMillibels: Int)

object AudioGainPolicy {
    const val MIN_PERCENT = 0
    const val DEFAULT_PERCENT = 100
    const val MAX_PERCENT = 200
    private const val MILLIBELS_PER_BOOST_PERCENT = 10

    fun fromPercent(percent: Int): AudioGain {
        val value = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        return if (value <= DEFAULT_PERCENT) {
            AudioGain(value / DEFAULT_PERCENT.toFloat(), 0)
        } else {
            AudioGain(1f, (value - DEFAULT_PERCENT) * MILLIBELS_PER_BOOST_PERCENT)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat testDebugUnitTest --tests "com.myvideolibrary.app.ui.player.AudioGainPolicyTest" --no-daemon`

Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/myvideolibrary/app/ui/player/AudioGainPolicy.kt app/src/test/java/com/myvideolibrary/app/ui/player/AudioGainPolicyTest.kt
git commit -m "test: define audio gain policy"
```

### Task 2: تخزين إعدادات الصوت بترحيل آمن

**Files:**

- Modify: `app/src/main/java/com/myvideolibrary/app/data/local/entity/SettingsEntity.kt`
- Modify: `app/src/main/java/com/myvideolibrary/app/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/myvideolibrary/app/data/local/Migrations.kt`
- Modify: `app/src/main/java/com/myvideolibrary/app/di/DatabaseModule.kt`

- [ ] **Step 1: Add settings fields with defaults**

```kotlin
@ColumnInfo(name = "audio_volume_percent") val audioVolumePercent: Int = 100,
@ColumnInfo(name = "audio_bass_boost_enabled") val audioBassBoostEnabled: Boolean = false,
@ColumnInfo(name = "audio_surround_enabled") val audioSurroundEnabled: Boolean = false,
@ColumnInfo(name = "audio_speech_clarity_enabled") val audioSpeechClarityEnabled: Boolean = false,
```

- [ ] **Step 2: Add and register migration 15 to 16**

```kotlin
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settings ADD COLUMN audio_volume_percent INTEGER NOT NULL DEFAULT 100")
        db.execSQL("ALTER TABLE settings ADD COLUMN audio_bass_boost_enabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE settings ADD COLUMN audio_surround_enabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE settings ADD COLUMN audio_speech_clarity_enabled INTEGER NOT NULL DEFAULT 0")
    }
}
```

Set `AppDatabase` to version `16`, import and append `MIGRATION_15_16` to `DatabaseModule.addMigrations(...)`.

- [ ] **Step 3: Compile Room and regenerate schema**

Run: `./gradlew.bat :app:kspDebugKotlin --no-daemon`

Expected: PASS and `app/schemas/com.myvideolibrary.app.data.local.AppDatabase/16.json` contains the four new columns.

- [ ] **Step 4: Commit**

```powershell
git add app/src/main/java/com/myvideolibrary/app/data/local/entity/SettingsEntity.kt app/src/main/java/com/myvideolibrary/app/data/local/AppDatabase.kt app/src/main/java/com/myvideolibrary/app/data/local/Migrations.kt app/src/main/java/com/myvideolibrary/app/di/DatabaseModule.kt app/schemas/com.myvideolibrary.app.data.local.AppDatabase/16.json
git commit -m "feat: persist audio enhancement preferences"
```

### Task 3: عرض الإعدادات وحفظها فوراً

**Files:**

- Modify: `app/src/main/java/com/myvideolibrary/app/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/myvideolibrary/app/ui/settings/SettingsActivity.kt`
- Modify: `app/src/main/res/layout/activity_settings.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Extend the UI state and view model**

Add the following fields to `SettingsUiState` and populate them from `settingsRepository.getSettings()`:

```kotlin
val audioVolumePercent: Int = 100,
val audioBassBoostEnabled: Boolean = false,
val audioSurroundEnabled: Boolean = false,
val audioSpeechClarityEnabled: Boolean = false,
```

Add `setAudioVolumePercent`, `setAudioBassBoostEnabled`, `setAudioSurroundEnabled`, and `setAudioSpeechClarityEnabled`. The volume method must clamp with `AudioGainPolicy.MIN_PERCENT` and `AudioGainPolicy.MAX_PERCENT` before persisting and publishing the state.

- [ ] **Step 2: Add the settings section**

Insert a collapsible `headerAudio` and `bodyAudio` after `bodyAppearance`. Inside it place a Material `Slider` with id `audioVolumeSlider`, a value TextView `audioVolumeValue`, help text `audio_volume_desc`, and Material switches `audioBassBoostSwitch`, `audioSurroundSwitch`, and `audioSpeechClaritySwitch`. Use the existing Settings styles.

- [ ] **Step 3: Bind input and rendering**

In `bindActions`, add `setupSection(binding.headerAudio, binding.bodyAudio, binding.chevAudio)`. Register `addOnChangeListener` on the slider and call `viewModel.setAudioVolumePercent(value.toInt())` only when `fromUser` is true. Bind each switch to its matching view-model method. In `render`, set the slider only if its current value differs, render the percent text, and assign switch states.

- [ ] **Step 4: Add all localized text**

Add `section_audio`, `audio_settings`, `audio_volume`, `audio_volume_desc`, `audio_bass_boost`, `audio_bass_boost_desc`, `audio_surround`, `audio_surround_desc`, `audio_speech_clarity`, and `audio_speech_clarity_desc`. The volume description must state that use above 100% can distort some sources.

- [ ] **Step 5: Build the debug app**

Run: `./gradlew.bat :app:assembleDebug --no-daemon`

Expected: PASS with generated view binding references compiling.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/myvideolibrary/app/ui/settings/SettingsViewModel.kt app/src/main/java/com/myvideolibrary/app/ui/settings/SettingsActivity.kt app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml
git commit -m "feat: add audio settings controls"
```

### Task 4: تطبيق مؤثرات الجهاز بأمان داخل المشغّل

**Files:**

- Create: `app/src/main/java/com/myvideolibrary/app/ui/player/PlayerAudioEffects.kt`
- Modify: `app/src/main/java/com/myvideolibrary/app/ui/player/PlayerActivity.kt`

- [ ] **Step 1: Implement the effect owner**

Create `PlayerAudioSettings` containing `volumePercent`, `bassBoostEnabled`, `surroundEnabled`, and `speechClarityEnabled`. `PlayerAudioEffects.apply(player, settings)` sets `player.volume` from `AudioGainPolicy`, then creates/reuses `LoudnessEnhancer`, `BassBoost`, `Virtualizer`, and `Equalizer` only for a positive `player.audioSessionId` and only when their feature is enabled. Wrap each creation and mutation in `runCatching`; release and null an effect when disabled or unavailable. Use `BassBoost.setStrength(650)`, `Virtualizer.setStrength(500)`, and an `Equalizer` helper that raises only the band containing 2 kHz by 300 mB for speech clarity. `release()` safely releases all effects and resets the session id.

- [ ] **Step 2: Wire settings to playback**

Inject `SettingsRepository` into `PlayerActivity`, collect `observeSettings()` inside `repeatOnLifecycle(Lifecycle.State.STARTED)`, convert each entity to `PlayerAudioSettings`, and call `audioEffects.apply(player, settings)` when a player exists. Store the latest settings so `preparePlayer` can apply them after creating `ExoPlayer`. In the existing `Player.Listener`, override `onAudioSessionIdChanged` to reapply the latest settings when the session changes.

- [ ] **Step 3: Add the small player shortcut**

In `showPlayerMenu`, add `m.add(0, 13, 10, getString(R.string.audio_settings))`. Its selection branch starts `SettingsActivity`; do not add a permanent toolbar control.

- [ ] **Step 4: Release resources**

Call `audioEffects.release()` before `player?.release()` in `onDestroy`. Do not change the current buffering, gesture, rotation, background-play, or end-of-clip behavior.

- [ ] **Step 5: Build and run audio policy tests**

Run: `./gradlew.bat testDebugUnitTest :app:assembleDebug --no-daemon`

Expected: PASS; unavailable hardware effects are skipped at runtime without a crash.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/myvideolibrary/app/ui/player/PlayerAudioEffects.kt app/src/main/java/com/myvideolibrary/app/ui/player/PlayerActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat: apply optional player audio effects"
```

### Task 5: إصدار APK والتحقق منه

**Files:**

- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/myvideolibrary/app/ui/settings/ReleaseNotes.kt`

- [ ] **Step 1: Raise the version and release note**

Set fallback `versionCode` to `175` and `versionName` to `"1.0.175"`. Set `ReleaseNotes.latest().version` to `"1.0.175"` and list only the audio slider, optional effects, and player shortcut in the existing release-note language.

- [ ] **Step 2: Run the complete verification build**

```powershell
$javaHome='C:\mvlbuild\jdk17\jdk-17.0.20+8'
$env:JAVA_HOME=$javaHome
$env:ANDROID_HOME='C:\mvlbuild\android-sdk'
$env:ANDROID_SDK_ROOT='C:\mvlbuild\android-sdk'
$env:Path="$javaHome\bin;$env:Path"
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify install metadata and signature**

```powershell
$apk='app\build\outputs\apk\debug\vedio_lb.apk'
& 'C:\mvlbuild\android-sdk\build-tools\35.0.0\aapt.exe' dump badging $apk
& 'C:\mvlbuild\android-sdk\build-tools\35.0.0\apksigner.bat' verify --verbose $apk
Get-FileHash $apk -Algorithm SHA256
```

Expected: package `com.myvideolibrary.app.debug`, versionCode `175`, versionName `1.0.175`, and verified signing.

- [ ] **Step 4: Commit, push, and publish the verified APK**

```powershell
git add app/build.gradle.kts app/src/main/java/com/myvideolibrary/app/ui/settings/ReleaseNotes.kt
git commit -m "release: 1.0.175 audio enhancements"
git push origin agent/sync-offline-updates
```

Create or update the GitHub `apk-latest` release asset `vedio_lb.apk`, download the published asset, compare its SHA-256 to the locally verified APK, and report the release URL, direct download URL, version, and checksum.
