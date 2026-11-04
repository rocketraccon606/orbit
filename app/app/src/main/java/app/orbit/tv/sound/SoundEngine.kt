package app.orbit.tv.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.orbit.tv.R
import app.orbit.tv.settings.Prefs

enum class SoundPack(val key: String, val label: String) {
    OFF("off", "Off"),
    SOFT("soft", "Soft"),
    CRISP("crisp", "Crisp"),
    CHIME("chime", "Chime"),
    ;

    companion object {
        fun from(key: String?): SoundPack = entries.firstOrNull { it.key == key } ?: SOFT
    }
}

enum class SoundCue { HORIZONTAL, VERTICAL, SELECT, BACK }

class SoundEngine(private val context: Context, private val prefs: Prefs) {

    var pack: SoundPack = SoundPack.from(prefs.soundPack)
        private set

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val ids: Map<SoundPack, Map<SoundCue, Int>> = mapOf(
        SoundPack.SOFT to load(
            R.raw.sfx_soft_h, R.raw.sfx_soft_v, R.raw.sfx_soft_select, R.raw.sfx_soft_back,
        ),
        SoundPack.CRISP to load(
            R.raw.sfx_crisp_h, R.raw.sfx_crisp_v, R.raw.sfx_crisp_select, R.raw.sfx_crisp_back,
        ),
        SoundPack.CHIME to load(
            R.raw.sfx_chime_h, R.raw.sfx_chime_v, R.raw.sfx_chime_select, R.raw.sfx_chime_back,
        ),
    )

    private val handler = Handler(Looper.getMainLooper())
    private val disarm = Runnable { armedCue = null }

    private var armedCue: SoundCue? = null
    private var armedAt = 0L
    private var lastPlayedAt = 0L

    private fun load(h: Int, v: Int, select: Int, back: Int): Map<SoundCue, Int> = mapOf(
        SoundCue.HORIZONTAL to pool.load(context, h, 1),
        SoundCue.VERTICAL to pool.load(context, v, 1),
        SoundCue.SELECT to pool.load(context, select, 1),
        SoundCue.BACK to pool.load(context, back, 1),
    )

    fun setPack(value: SoundPack) {
        pack = value
        prefs.soundPack = value.key
        armedCue = null
    }

    /** Marks the cue that the next focus change should play. */
    fun arm(cue: SoundCue) {
        if (pack == SoundPack.OFF) return
        armedCue = cue
        armedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(disarm)
        handler.postDelayed(disarm, 400)
    }

    /** Called from focusable tiles when they gain focus. */
    fun onFocusGained() {
        val cue = armedCue ?: return
        armedCue = null
        handler.removeCallbacks(disarm)
        if (SystemClock.uptimeMillis() - armedAt <= 400) play(cue)
    }

    fun play(cue: SoundCue) {
        if (pack == SoundPack.OFF) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayedAt < 30) return
        val id = ids[pack]?.get(cue) ?: return
        if (id == 0) return
        lastPlayedAt = now
        pool.play(id, VOLUME, VOLUME, 1, 0, 1f)
    }

    private companion object {
        const val VOLUME = 0.55f
    }
}
