package dev.stefan.fluxline.game.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.TimeUtils
import dev.stefan.fluxline.game.save.Settings

/** Les bruitages (fichiers assets/audio/sfx/<nom>.ogg, générés par tools/gen_audio.py). */
enum class Sfx(val file: String, val baseVolume: Float = 1f, val minGapMs: Long = 40) {
    PLACE("place", 0.8f),
    REPLACE("replace", 0.85f),
    ROTATE("rotate", 0.55f),
    REFUSE("refuse", 0.6f, 120),
    TICK("tick", 0.6f),
    GO("go", 0.85f),
    FILL("fill", 0.32f, 60),
    CROSS("cross", 0.8f),
    TARGET("target", 0.85f),
    LEAK("leak", 0.9f),
    DRAIN("drain", 0.9f),
    WIN("win", 0.9f),
    FAIL("fail", 0.85f),
    FAST("fast", 0.75f),
    CLICK("click", 0.55f, 60),
    BACK("back", 0.5f, 60),
    STAR("star", 0.75f, 90),
    COUNT("count", 0.25f, 30),
}

/** Les musiques, en boucle. */
enum class Track(val file: String) {
    MENU("menu"),
    GAME("game"),
}

/**
 * Sons courts (Sound : préchargés, polyphoniques) et musiques (Music : lues en
 * flux). Trois volumes séparés : général, musique, effets. Les fondus entre
 * musiques et le bourdonnement du fluide sont gérés dans [update].
 *
 * Un fichier manquant n'est jamais fatal : le jeu reste jouable en silence.
 */
class AudioManager(private val settings: Settings) : Disposable {
    private val sounds = HashMap<Sfx, Sound>()
    private val lastPlay = HashMap<Sfx, Long>()
    private val tracks = HashMap<Track, Music>()

    private var current: Music? = null
    private var currentTrack: Track? = null
    private var fading: Music? = null
    private var fade = 1f
    private var fadeOut = 0f

    private var hum: Sound? = null
    private var humId = -1L
    private var humLevel = 0f
    private var humTarget = 0f
    private var humPitch = 1f

    init {
        for (s in Sfx.entries) {
            val f = Gdx.files.internal("audio/sfx/${s.file}.ogg")
            if (f.exists()) runCatching { sounds[s] = Gdx.audio.newSound(f) }
        }
        val h = Gdx.files.internal("audio/sfx/hum.ogg")
        if (h.exists()) hum = runCatching { Gdx.audio.newSound(h) }.getOrNull()
        for (t in Track.entries) {
            val f = Gdx.files.internal("audio/music/${t.file}.ogg")
            if (f.exists()) runCatching { tracks[t] = Gdx.audio.newMusic(f).also { it.isLooping = true } }
        }
    }

    private val sfxVolume get() = settings.masterVolume * settings.sfxVolume
    private val musicVolume get() = settings.masterVolume * settings.musicVolume

    fun play(s: Sfx, volume: Float = 1f, pitch: Float = 1f, pan: Float = 0f) {
        val v = sfxVolume * s.baseVolume * volume
        if (v <= 0.001f) return
        val now = TimeUtils.millis()
        val last = lastPlay[s] ?: 0L
        if (now - last < s.minGapMs) return
        lastPlay[s] = now
        sounds[s]?.play(v.coerceIn(0f, 1f), pitch.coerceIn(0.5f, 2f), pan.coerceIn(-1f, 1f))
    }

    /** Lance une musique avec un fondu enchaîné (sans effet si elle joue déjà). */
    fun music(track: Track?) {
        if (track == currentTrack) return
        fading?.stop()
        fading = current
        fadeOut = 1f
        currentTrack = track
        current = track?.let { tracks[it] }
        fade = 0f
        current?.let {
            it.volume = 0f
            it.play()
        }
    }

    /** Bourdonnement du fluide : [level] 0..1 (0 = silence), [pitch] selon la vitesse. */
    fun hum(level: Float, pitch: Float = 1f) {
        humTarget = level
        humPitch = pitch
    }

    fun update(dt: Float) {
        if (fade < 1f) fade = (fade + dt / 0.8f).coerceAtMost(1f)
        current?.volume = musicVolume * fade
        fading?.let {
            fadeOut -= dt / 0.6f
            if (fadeOut <= 0f) {
                it.stop()
                fading = null
            } else {
                it.volume = musicVolume * fadeOut
            }
        }
        val h = hum ?: return
        humLevel += (humTarget - humLevel) * (1f - kotlin.math.exp(-dt * 4f))
        val v = humLevel * sfxVolume * 0.35f
        if (v > 0.002f) {
            if (humId < 0) humId = h.loop(v, humPitch, 0f)
            h.setVolume(humId, v)
            h.setPitch(humId, humPitch)
        } else if (humId >= 0) {
            h.stop(humId)
            humId = -1
        }
    }

    /** Réapplique les volumes après un changement de réglages. */
    fun refreshVolumes() {
        current?.volume = musicVolume * fade
    }

    fun pauseAll() {
        current?.pause()
        hum?.let { if (humId >= 0) it.pause(humId) }
    }

    fun resumeAll() {
        current?.play()
        hum?.let { if (humId >= 0) it.resume(humId) }
    }

    override fun dispose() {
        for (s in sounds.values) s.dispose()
        for (m in tracks.values) m.dispose()
        hum?.dispose()
    }
}
