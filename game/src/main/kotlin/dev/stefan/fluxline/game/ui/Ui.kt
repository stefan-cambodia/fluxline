package dev.stefan.fluxline.game.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.utils.Align
import dev.stefan.fluxline.game.Assets
import dev.stefan.fluxline.game.Ease
import dev.stefan.fluxline.game.Haptic
import dev.stefan.fluxline.game.Palette
import dev.stefan.fluxline.game.audio.AudioManager
import dev.stefan.fluxline.game.audio.Sfx

/** Ce dont les widgets ont besoin pour se dessiner et réagir. */
class UiContext(
    val batch: SpriteBatch,
    val assets: Assets,
    val audio: AudioManager,
    val haptic: (Haptic) -> Unit,
) {
    /** Échelle de l'interface : 1 = un écran de référence de 720 × 1280. */
    var scale = 1f
    val layout = GlyphLayout()

    /**
     * Dessine du texte ; [align] = Align.left / center / right. Renvoie la largeur.
     * Si [maxWidth] est donné, un texte trop long est réduit pour tenir (les
     * traductions n'ont pas toutes la longueur du français), en gardant son
     * centre vertical.
     */
    fun text(
        font: BitmapFont,
        text: CharSequence,
        x: Float,
        y: Float,
        color: Color = Palette.TEXT,
        alpha: Float = 1f,
        align: Int = Align.left,
        width: Float = 0f,
        wrap: Boolean = false,
        maxWidth: Float = 0f,
    ): Float {
        font.setColor(color.r, color.g, color.b, color.a * alpha)
        layout.setText(font, text, 0, text.length, font.color, width, if (wrap) align else Align.left, wrap, null)
        var ty = y
        val scale = font.data.scaleX
        val shrink = !wrap && maxWidth > 0f && layout.width > maxWidth
        if (shrink) {
            val k = maxWidth / layout.width
            ty -= font.capHeight * (1f - k) / 2f
            font.data.setScale(scale * k)
            layout.setText(font, text, 0, text.length, font.color, 0f, Align.left, false, null)
        }
        val dx = when {
            wrap -> 0f
            align and Align.center != 0 -> -layout.width / 2f
            align and Align.right != 0 -> -layout.width
            else -> 0f
        }
        font.draw(batch, layout, x + dx, ty)
        if (shrink) font.data.setScale(scale)
        font.color = Color.WHITE
        return layout.width
    }

    fun measure(font: BitmapFont, text: CharSequence): Float {
        layout.setText(font, text)
        return layout.width
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Color, alpha: Float = 1f) {
        batch.setColor(color.r, color.g, color.b, color.a * alpha)
        batch.draw(assets.white, x, y, w, h)
        batch.setColor(1f, 1f, 1f, 1f)
    }

    fun patch(p: NinePatch, x: Float, y: Float, w: Float, h: Float, color: Color = Color.WHITE, alpha: Float = 1f) {
        p.setColor(Palette.tmp(color, alpha))
        p.draw(batch, x, y, w, h)
    }

    fun icon(region: TextureRegion, cx: Float, cy: Float, size: Float, color: Color = Palette.TEXT, alpha: Float = 1f, rotation: Float = 0f) {
        batch.setColor(color.r, color.g, color.b, color.a * alpha)
        batch.draw(region, cx - size / 2, cy - size / 2, size / 2, size / 2, size, size, 1f, 1f, rotation)
        batch.setColor(1f, 1f, 1f, 1f)
    }

    fun click() {
        audio.play(Sfx.CLICK)
        haptic(Haptic.TICK)
    }
}

/** Un élément d'interface rectangulaire. Les coordonnées sont en pixels, y vers le haut. */
abstract class Widget {
    val bounds = Rectangle()
    var visible = true
    var enabled = true
    /** Marge invisible ajoutée à la zone tactile (les doigts ne visent pas au pixel). */
    var touchPad = 0f

    fun set(x: Float, y: Float, w: Float, h: Float): Widget {
        bounds.set(x, y, w, h)
        return this
    }

    open fun hit(x: Float, y: Float): Boolean =
        visible && enabled && x >= bounds.x - touchPad && x <= bounds.x + bounds.width + touchPad &&
            y >= bounds.y - touchPad && y <= bounds.y + bounds.height + touchPad

    open fun touchDown(x: Float, y: Float, ui: UiContext) {}
    open fun touchDragged(x: Float, y: Float, ui: UiContext) {}
    open fun touchUp(x: Float, y: Float, inside: Boolean, ui: UiContext) {}
    open fun update(dt: Float) {}
    abstract fun draw(ui: UiContext, alpha: Float)
}

enum class ButtonStyle { PRIMARY, SECONDARY, ICON, ICON_FLAT, DANGER }

/**
 * Un bouton : enfoncement animé, son et vibration au relâcher. Le clic n'est
 * validé que si le doigt est encore dessus en se levant — on peut toujours
 * annuler en glissant hors du bouton.
 */
class Button(
    var text: String = "",
    var icon: String? = null,
    var style: ButtonStyle = ButtonStyle.PRIMARY,
    var subtitle: String? = null,
    val onClick: () -> Unit,
) : Widget() {
    private var pressed = false
    private var press = 0f
    private var flash = 0f
    var badge: String? = null
    var iconRotation = 0f

    override fun touchDown(x: Float, y: Float, ui: UiContext) {
        pressed = true
    }

    override fun touchDragged(x: Float, y: Float, ui: UiContext) {
        pressed = hit(x, y)
    }

    override fun touchUp(x: Float, y: Float, inside: Boolean, ui: UiContext) {
        val wasPressed = pressed
        pressed = false
        if (inside && wasPressed) {
            flash = 1f
            ui.click()
            onClick()
        }
    }

    override fun update(dt: Float) {
        press = Ease.approach(press, if (pressed) 1f else 0f, 28f, dt)
        flash = (flash - dt * 3f).coerceAtLeast(0f)
    }

    override fun draw(ui: UiContext, alpha: Float) {
        if (!visible) return
        val a = alpha * if (enabled) 1f else 0.4f
        val s = 1f - 0.05f * press
        val w = bounds.width * s
        val h = bounds.height * s
        val x = bounds.x + (bounds.width - w) / 2
        val y = bounds.y + (bounds.height - h) / 2
        val assets = ui.assets
        val fonts = assets.fonts
        when (style) {
            ButtonStyle.ICON_FLAT -> {
                icon?.let { ui.icon(assets.icon(it), x + w / 2, y + h / 2, h * 0.52f, Palette.TEXT, a * (0.85f + 0.15f * (1 - press)), iconRotation) }
                return
            }
            ButtonStyle.ICON -> {
                ui.patch(if (pressed) assets.buttonDown else assets.button, x, y, w, h, Color.WHITE, a)
                icon?.let { ui.icon(assets.icon(it), x + w / 2, y + h / 2, h * 0.46f, Palette.TEXT, a, iconRotation) }
            }
            else -> {
                val patch = when {
                    pressed -> assets.buttonDown
                    style == ButtonStyle.SECONDARY -> assets.buttonCyan
                    else -> assets.button
                }
                ui.patch(patch, x, y, w, h, if (style == ButtonStyle.DANGER) Palette.MAGENTA else Color.WHITE, a)
                val textColor = when (style) {
                    ButtonStyle.PRIMARY -> Palette.AMBER_SOFT
                    ButtonStyle.DANGER -> Palette.MAGENTA
                    else -> Palette.TEXT
                }
                val hasSub = subtitle != null
                val cy = y + h / 2
                var tx = x + w / 2
                val textY = if (hasSub) cy + fonts.body.capHeight * 1.05f else cy + fonts.body.capHeight / 2
                val inner = w - ui.scale * 48f
                var textMax = inner
                icon?.let {
                    val isz = if (hasSub) h * 0.30f else h * 0.40f
                    textMax = inner - isz - ui.scale * 14f
                    val tw = kotlin.math.min(ui.measure(fonts.body, text), textMax)
                    val total = isz + ui.scale * 14f + tw
                    val ix = x + (w - total) / 2 + isz / 2
                    ui.icon(assets.icon(it), ix, textY - fonts.body.capHeight / 2, isz, textColor, a, iconRotation)
                    tx = ix + isz / 2 + ui.scale * 14f + tw / 2
                }
                ui.text(fonts.body, text, tx, textY, textColor, a, Align.center, maxWidth = textMax)
                subtitle?.let { ui.text(fonts.small, it, x + w / 2, cy - fonts.small.capHeight * 0.7f, Palette.TEXT_DIM, a, Align.center, maxWidth = inner) }
                badge?.let { ui.text(fonts.small, it, x + w - ui.scale * 24f, cy + fonts.small.capHeight / 2, Palette.CYAN, a, Align.right) }
            }
        }
        if (flash > 0f) {
            ui.batch.setColor(1f, 1f, 1f, flash * 0.15f * a)
            ui.batch.draw(assets.white, x + ui.scale * 6f, y + ui.scale * 6f, w - ui.scale * 12f, h - ui.scale * 12f)
            ui.batch.setColor(1f, 1f, 1f, 1f)
        }
    }
}

/** Un curseur horizontal 0..1, avec son libellé et sa valeur en pourcentage. */
class Slider(
    val label: String,
    var value: Float,
    val onChange: (Float) -> Unit,
) : Widget() {
    private var dragging = false
    private var lastTick = -1

    private fun trackX(ui: UiContext) = bounds.x + ui.scale * 8f
    private fun trackW(ui: UiContext) = bounds.width - ui.scale * 16f

    override fun touchDown(x: Float, y: Float, ui: UiContext) {
        dragging = true
        apply(x, ui)
    }

    override fun touchDragged(x: Float, y: Float, ui: UiContext) {
        if (dragging) apply(x, ui)
    }

    override fun touchUp(x: Float, y: Float, inside: Boolean, ui: UiContext) {
        dragging = false
    }

    private fun apply(x: Float, ui: UiContext) {
        val v = ((x - trackX(ui)) / trackW(ui)).coerceIn(0f, 1f)
        val stepped = Math.round(v * 20f) / 20f
        if (stepped != value) {
            value = stepped
            onChange(value)
            val tick = (value * 10).toInt()
            if (tick != lastTick) {
                lastTick = tick
                ui.audio.play(dev.stefan.fluxline.game.audio.Sfx.COUNT, pitch = 0.8f + value * 0.6f)
                ui.haptic(Haptic.TICK)
            }
        }
    }

    override fun draw(ui: UiContext, alpha: Float) {
        val f = ui.assets.fonts
        val top = bounds.y + bounds.height
        val pct = "${(value * 100).toInt()} %"
        val vw = ui.text(f.body, pct, bounds.x + bounds.width, top - ui.scale * 4f, Palette.CYAN, alpha, Align.right)
        ui.text(f.body, label, bounds.x, top - ui.scale * 4f, Palette.TEXT, alpha, maxWidth = bounds.width - vw - ui.scale * 20f)
        val th = ui.scale * 16f
        val ty = bounds.y + ui.scale * 18f
        val tx = trackX(ui)
        val tw = trackW(ui)
        ui.patch(ui.assets.barTrack, tx, ty, tw, th, Color.WHITE, alpha)
        if (value > 0.01f) ui.patch(ui.assets.barFill, tx, ty, kotlin.math.max(th, tw * value), th, Palette.CYAN, alpha)
        val k = ui.scale * (if (dragging) 54f else 46f)
        ui.batch.setColor(1f, 1f, 1f, alpha)
        ui.batch.draw(ui.assets.knob, tx + tw * value - k / 2, ty + th / 2 - k / 2, k, k)
        ui.batch.setColor(1f, 1f, 1f, 1f)
    }
}

/** Un interrupteur : libellé à gauche, bascule à droite. Toute la ligne est cliquable. */
class Toggle(
    val label: String,
    var value: Boolean,
    val detail: String? = null,
    val onChange: (Boolean) -> Unit,
) : Widget() {
    private var anim = if (value) 1f else 0f
    private var pressed = false

    override fun touchDown(x: Float, y: Float, ui: UiContext) {
        pressed = true
    }

    override fun touchUp(x: Float, y: Float, inside: Boolean, ui: UiContext) {
        if (inside && pressed) {
            value = !value
            ui.click()
            onChange(value)
        }
        pressed = false
    }

    override fun update(dt: Float) {
        anim = Ease.approach(anim, if (value) 1f else 0f, 18f, dt)
    }

    override fun draw(ui: UiContext, alpha: Float) {
        val f = ui.assets.fonts
        val cy = bounds.y + bounds.height / 2
        val hasDetail = detail != null
        val sw = ui.scale * 92f
        val textMax = bounds.width - sw - ui.scale * 20f
        ui.text(f.body, label, bounds.x, if (hasDetail) cy + f.body.capHeight * 1.1f else cy + f.body.capHeight / 2, Palette.TEXT, alpha, maxWidth = textMax)
        detail?.let { ui.text(f.small, it, bounds.x, cy - f.small.capHeight * 0.6f, Palette.TEXT_DIM, alpha, maxWidth = textMax) }
        val sh = ui.scale * 46f
        val sx = bounds.x + bounds.width - sw
        val sy = cy - sh / 2
        ui.patch(ui.assets.barTrack, sx, sy, sw, sh, Color.WHITE, alpha)
        if (anim > 0.02f) ui.patch(ui.assets.barFill, sx, sy, sw, sh, Palette.CYAN, alpha * anim * 0.85f)
        val k = sh * 0.92f
        val kx = sx + sh * 0.04f + (sw - k - sh * 0.08f) * anim
        ui.batch.setColor(1f, 1f, 1f, alpha)
        ui.batch.draw(ui.assets.knob, kx, cy - k / 2, k, k)
        ui.batch.setColor(1f, 1f, 1f, 1f)
    }
}

/**
 * Un choix parmi plusieurs valeurs : libellé à gauche, valeur à droite ;
 * toucher la ligne passe à la valeur suivante. [icon] aide à repérer la ligne
 * même dans une langue qu'on ne lit pas (la langue, justement).
 */
class Choice(
    var label: String,
    var value: String,
    val icon: String? = null,
    val onNext: () -> Unit,
) : Widget() {
    private var pressed = false
    private var flash = 0f

    override fun touchDown(x: Float, y: Float, ui: UiContext) {
        pressed = true
    }

    override fun touchUp(x: Float, y: Float, inside: Boolean, ui: UiContext) {
        if (inside && pressed) {
            flash = 1f
            ui.click()
            onNext()
        }
        pressed = false
    }

    override fun update(dt: Float) {
        flash = (flash - dt * 3f).coerceAtLeast(0f)
    }

    override fun draw(ui: UiContext, alpha: Float) {
        val f = ui.assets.fonts
        val cy = bounds.y + bounds.height / 2
        val pad = ui.scale * 20f
        ui.patch(if (pressed) ui.assets.buttonDown else ui.assets.buttonCyan, bounds.x, bounds.y, bounds.width, bounds.height, Color.WHITE, alpha)
        var lx = bounds.x + pad
        icon?.let {
            val isz = bounds.height * 0.42f
            ui.icon(ui.assets.icon(it), lx + isz / 2, cy, isz, Palette.CYAN, alpha)
            lx += isz + ui.scale * 14f
        }
        val vw = ui.text(f.body, value, bounds.x + bounds.width - pad, cy + f.body.capHeight / 2, Palette.AMBER_SOFT, alpha, Align.right, maxWidth = bounds.width * 0.5f)
        ui.text(f.body, label, lx, cy + f.body.capHeight / 2, Palette.TEXT, alpha, maxWidth = bounds.x + bounds.width - pad - vw - ui.scale * 16f - lx)
        if (flash > 0f) {
            ui.batch.setColor(1f, 1f, 1f, flash * 0.15f * alpha)
            ui.batch.draw(ui.assets.white, bounds.x + ui.scale * 6f, bounds.y + ui.scale * 6f, bounds.width - ui.scale * 12f, bounds.height - ui.scale * 12f)
            ui.batch.setColor(1f, 1f, 1f, 1f)
        }
    }
}

/**
 * Un ensemble de widgets : distribue le toucher (un seul doigt à la fois sur
 * un widget donné), anime et dessine.
 */
class WidgetGroup {
    val widgets = ArrayList<Widget>()
    private var captured: Widget? = null
    private var capturedPointer = -1

    fun <T : Widget> add(w: T): T {
        widgets += w
        return w
    }

    fun clear() {
        widgets.clear()
        captured = null
    }

    fun touchDown(x: Float, y: Float, pointer: Int, ui: UiContext): Boolean {
        if (captured != null) return true
        for (i in widgets.indices.reversed()) {
            val w = widgets[i]
            if (w.hit(x, y)) {
                captured = w
                capturedPointer = pointer
                w.touchDown(x, y, ui)
                return true
            }
        }
        return false
    }

    fun touchDragged(x: Float, y: Float, pointer: Int, ui: UiContext): Boolean {
        val w = captured ?: return false
        if (pointer != capturedPointer) return true
        w.touchDragged(x, y, ui)
        return true
    }

    fun touchUp(x: Float, y: Float, pointer: Int, ui: UiContext): Boolean {
        val w = captured ?: return false
        if (pointer != capturedPointer) return true
        captured = null
        w.touchUp(x, y, w.hit(x, y), ui)
        return true
    }

    /** Annule le geste en cours (changement d'écran, pause…). */
    fun cancel(ui: UiContext) {
        captured?.touchUp(-1e6f, -1e6f, false, ui)
        captured = null
    }

    fun update(dt: Float) {
        for (i in widgets.indices) widgets[i].update(dt)
    }

    fun draw(ui: UiContext, alpha: Float) {
        for (i in widgets.indices) {
            val w = widgets[i]
            if (w.visible) w.draw(ui, alpha)
        }
    }
}
