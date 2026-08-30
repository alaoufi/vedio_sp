package com.myvideolibrary.app.util

import android.content.Context

/**
 * Persistent default volume boost + sound effect, applied to every clip that opens.
 * The in-player control still adjusts the current session live; this is the global
 * default the player starts from. Kept in plain prefs (no secret), separate from
 * the database so it is independent of it.
 */
object AudioPrefs {

    private const val PREFS = "mvl_audio_prefs"
    private const val KEY_BOOST = "default_boost"
    private const val KEY_EFFECT = "default_effect"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Default boost percentage (100 = normal). */
    fun boost(context: Context): Int =
        runCatching { prefs(context).getInt(KEY_BOOST, 100) }.getOrDefault(100)
            .coerceIn(100, 500)

    fun setBoost(context: Context, percent: Int) {
        runCatching { prefs(context).edit().putInt(KEY_BOOST, percent.coerceIn(100, 500)).apply() }
    }

    /** Default effect preset name (one of PlayerAudioEffects.Preset). */
    fun effect(context: Context): PlayerAudioEffects.Preset {
        val name = runCatching { prefs(context).getString(KEY_EFFECT, null) }.getOrNull()
        return PlayerAudioEffects.Preset.entries.firstOrNull { it.name == name }
            ?: PlayerAudioEffects.Preset.NONE
    }

    fun setEffect(context: Context, preset: PlayerAudioEffects.Preset) {
        runCatching { prefs(context).edit().putString(KEY_EFFECT, preset.name).apply() }
    }
}
