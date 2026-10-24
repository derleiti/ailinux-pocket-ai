package me.ailinux.pocket

import android.content.SharedPreferences

/** Validated and persistent llama.cpp sampling settings. */
data class SamplingSettings(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.15f,
    val repeatLastN: Int = 128
) {
    fun asStrings(): List<String> = listOf(temperature.toString(), topK.toString(),
        topP.toString(), minP.toString(), repeatPenalty.toString(), repeatLastN.toString())

    fun save(prefs: SharedPreferences) {
        prefs.edit().putFloat("sampling_temperature", temperature)
            .putInt("sampling_top_k", topK)
            .putFloat("sampling_top_p", topP)
            .putFloat("sampling_min_p", minP)
            .putFloat("sampling_repeat_penalty", repeatPenalty)
            .putInt("sampling_repeat_last_n", repeatLastN).apply()
    }

    companion object {
        val presets = listOf(
            SamplingSettings(),
            SamplingSettings(0.3f, 30, 0.85f, 0.05f, 1.15f, 128),
            SamplingSettings(1.0f, 60, 0.95f, 0.03f, 1.10f, 128)
        )

        fun parse(values: List<String>): SamplingSettings {
            require(values.size == 6) { "Sechs Sampling-Werte benötigt" }
            val t = values[0].toFloatOrNull()
            val k = values[1].toIntOrNull()
            val p = values[2].toFloatOrNull()
            val min = values[3].toFloatOrNull()
            val repeat = values[4].toFloatOrNull()
            val last = values[5].toIntOrNull()
            require(t != null && t.isFinite() && t in 0f..2f) { "Temperature: 0–2" }
            require(k != null && k in 1..200) { "Top-K: 1–200" }
            require(p != null && p.isFinite() && p in 0.05f..1f) { "Top-P: 0,05–1" }
            require(min != null && min.isFinite() && min in 0f..0.5f) { "Min-P: 0–0,5" }
            require(repeat != null && repeat.isFinite() && repeat in 1f..2f) {
                "Repeat Penalty: 1–2"
            }
            require(last != null && last in 0..512) { "Repeat Last N: 0–512" }
            return SamplingSettings(t, k, p, min, repeat, last)
        }

        fun fromPrefs(prefs: SharedPreferences): SamplingSettings = SamplingSettings(
            prefs.getFloat("sampling_temperature", 0.7f),
            prefs.getInt("sampling_top_k", 40),
            prefs.getFloat("sampling_top_p", 0.9f),
            prefs.getFloat("sampling_min_p", 0.05f),
            prefs.getFloat("sampling_repeat_penalty", 1.15f),
            prefs.getInt("sampling_repeat_last_n", 128)
        )
    }
}
