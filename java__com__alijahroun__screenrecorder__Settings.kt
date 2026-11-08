package com.alijahroun.screenrecorder

import android.content.Context

enum class AudioMode(val label: String) {
    NONE("No audio"),
    INTERNAL("Device audio"),
    MIC("Microphone"),
    BOTH("Device audio + microphone")
}

/** height = 0 -> automatic, -1 -> maximum the device can encode. */
enum class ResolutionChoice(val label: String, val height: Int) {
    AUTO("Automatic", 0),
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080),
    MAX("Maximum", -1)
}

/** fps == 0 means automatic. */
data class RecorderSettings(
    val automatic: Boolean = true,
    val resolution: ResolutionChoice = ResolutionChoice.AUTO,
    val fps: Int = 0,
    val audio: AudioMode = AudioMode.NONE
)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("recorder_settings", Context.MODE_PRIVATE)

    fun load(): RecorderSettings = RecorderSettings(
        automatic = prefs.getBoolean("automatic", true),
        resolution = runCatching {
            ResolutionChoice.valueOf(prefs.getString("resolution", null) ?: "AUTO")
        }.getOrDefault(ResolutionChoice.AUTO),
        fps = prefs.getInt("fps", 0),
        audio = runCatching {
            AudioMode.valueOf(prefs.getString("audio", null) ?: "NONE")
        }.getOrDefault(AudioMode.NONE)
    )

    fun save(s: RecorderSettings) {
        prefs.edit()
            .putBoolean("automatic", s.automatic)
            .putString("resolution", s.resolution.name)
            .putInt("fps", s.fps)
            .putString("audio", s.audio.name)
            .apply()
    }
}
