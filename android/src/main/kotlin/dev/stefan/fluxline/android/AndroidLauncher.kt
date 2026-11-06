package dev.stefan.fluxline.android

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import dev.stefan.fluxline.BuildConfig
import dev.stefan.fluxline.game.FluxGame
import dev.stefan.fluxline.game.Haptic
import dev.stefan.fluxline.game.LaunchOptions
import dev.stefan.fluxline.game.Platform

/**
 * L'activité unique. Plein écran immersif, rendu sous l'encoche (le jeu lit
 * les zones sûres), fréquence d'affichage la plus haute disponible.
 */
class AndroidLauncher : AndroidApplication() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            renderUnderCutout = true
            useAccelerometer = false
            useCompass = false
            useGyroscope = false
            useRotationVectorSensor = false
            // Les textures sont déjà lissées : pas besoin de multiéchantillonnage.
            numSamples = 0
            r = 8
            g = 8
            b = 8
            a = 8
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initialize(FluxGame(AndroidPlatform(this), launchOptions()), config)
        preferHighRefreshRate()
    }

    /** Options de développement transmises par `adb shell am start -e …` (versions de debug uniquement). */
    private fun launchOptions(): LaunchOptions {
        if (!BuildConfig.DEV_TOOLS) return LaunchOptions()
        val i = intent ?: return LaunchOptions()
        return LaunchOptions(
            startScreen = i.getStringExtra("screen") ?: "menu",
            autoplay = i.getStringExtra("autoplay") == "1",
            mode = i.getStringExtra("mode") ?: "classic",
            level = i.getStringExtra("level")?.toIntOrNull() ?: 1,
            timeScale = i.getStringExtra("speed")?.toFloatOrNull() ?: 1f,
            perfLogSeconds = i.getStringExtra("perf")?.toFloatOrNull() ?: 0f,
            language = i.getStringExtra("lang"),
        )
    }

    /** 90/120 Hz quand l'écran le permet (sans changer de résolution). */
    @Suppress("DEPRECATION")
    private fun preferHighRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.modeId != current.modeId) {
            window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        }
    }
}

/** Vibrations courtes et discrètes, avec les effets prédéfinis quand ils existent. */
private class AndroidPlatform(context: Context) : Platform {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override val devTools: Boolean get() = BuildConfig.DEV_TOOLS

    override fun haptic(kind: Haptic) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (kind) {
                Haptic.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                Haptic.PLACE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                Haptic.REPLACE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                Haptic.SUCCESS -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                Haptic.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 22, 50, 22), intArrayOf(0, 140, 0, 140), -1)
                Haptic.FAIL -> VibrationEffect.createOneShot(140, 160)
            }
        } else {
            when (kind) {
                Haptic.TICK -> VibrationEffect.createOneShot(8, 60)
                Haptic.PLACE -> VibrationEffect.createOneShot(14, 110)
                Haptic.REPLACE -> VibrationEffect.createOneShot(26, 200)
                Haptic.SUCCESS -> VibrationEffect.createWaveform(longArrayOf(0, 16, 60, 16), intArrayOf(0, 150, 0, 150), -1)
                Haptic.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 22, 50, 22), intArrayOf(0, 140, 0, 140), -1)
                Haptic.FAIL -> VibrationEffect.createOneShot(140, 160)
            }
        }
        runCatching { v.vibrate(effect) }
    }
}
