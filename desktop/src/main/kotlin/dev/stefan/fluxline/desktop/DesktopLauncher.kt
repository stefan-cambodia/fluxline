package dev.stefan.fluxline.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import dev.stefan.fluxline.game.FluxGame
import dev.stefan.fluxline.game.LaunchOptions
import dev.stefan.fluxline.game.Platform

/**
 * Lanceur PC pour le développement. Options :
 *   --size 1080x2340      taille de la fenêtre (pixels)
 *   --screen menu|game|levels|options|stats|credits
 *   --mode classic|levels|arcade|challenge  --level N
 *   --autoplay            le robot joue
 *   --shot fichier.png --delay 3.0   capture puis quitte
 *   --speed 2.0           accélère le temps
 *   --perf 2              journal des images par seconde toutes les 2 s
 */
fun main(args: Array<String>) {
    fun arg(name: String): String? {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }
    val (w, h) = (arg("--size") ?: "540x1170").split("x").map { it.toInt() }
    val options = LaunchOptions(
        startScreen = arg("--screen") ?: "menu",
        autoplay = "--autoplay" in args,
        shotPath = arg("--shot"),
        shotDelay = arg("--delay")?.toFloat() ?: 2.5f,
        mode = arg("--mode") ?: "classic",
        level = arg("--level")?.toInt() ?: 1,
        timeScale = arg("--speed")?.toFloat() ?: 1f,
        perfLogSeconds = arg("--perf")?.toFloat() ?: 0f,
        language = arg("--lang"),
    )
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("FLUXLINE")
        setWindowedMode(w, h)
        setResizable(true)
        // En capture automatique, la fenêtre peut être masquée (session verrouillée) :
        // la synchronisation verticale bloquerait alors indéfiniment l'affichage.
        useVsync(options.shotPath == null)
        setForegroundFPS(120)
        setBackBufferConfig(8, 8, 8, 8, 0, 0, 4)
        setPreferencesConfig(".fluxline-dev", com.badlogic.gdx.Files.FileType.External)
        if (options.shotPath != null) setInitialVisible(true)
    }
    Lwjgl3Application(FluxGame(object : Platform {
        override val devTools: Boolean = true
    }, options), config)
}
