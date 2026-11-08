package org.example.voicepitchlooper

import android.content.Context

/** Three persistent, named score slots. Stored privately inside this Android application's data. */
data class SavedScorePreset(val name: String, val scoreText: String, val bpm: Int)

class ScorePresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("utamaru_presets_v1", Context.MODE_PRIVATE)

    fun load(slot: Int): SavedScorePreset? {
        require(slot in 0..2)
        val text = prefs.getString("score_$slot", null) ?: return null
        val name = prefs.getString("name_$slot", null)?.takeIf { it.isNotBlank() } ?: "曲${slot + 1}"
        val bpm = prefs.getInt("bpm_$slot", 200).coerceIn(60, 500)
        return try {
            // Unversioned slots were saved by v0.6.6 or earlier. In that
            // notation, whitespace only separated notes (never represented a rest).
            val notes = if (prefs.getInt("format_$slot", 1) >= 2)
                ScoreParser.parse(text) else ScoreParser.parseLegacy(text)
            SavedScorePreset(name, ScoreParser.serialize(ScoreParser.completeMeasures(notes)), bpm)
        } catch (_: Exception) { null }
    }

    /** commit() makes completion synchronous, so the app reports success only after persistence. */
    fun save(slot: Int, preset: SavedScorePreset): Boolean {
        require(slot in 0..2 && preset.bpm in 60..500 && preset.name.isNotBlank())
        val complete = ScoreParser.parseCompleteMeasures(preset.scoreText)
        return prefs.edit()
            .putString("score_$slot", ScoreParser.serialize(complete))
            .putInt("format_$slot", 2)
            .putString("name_$slot", preset.name.take(24))
            .putInt("bpm_$slot", preset.bpm)
            .putInt("last_slot", slot)
            .commit()
    }

    fun markLastSlot(slot: Int) {
        require(slot in 0..2)
        prefs.edit().putInt("last_slot", slot).apply()
    }

    fun lastSlot(): Int? {
        val value = prefs.getInt("last_slot", -1)
        return value.takeIf { it in 0..2 }
    }
}
