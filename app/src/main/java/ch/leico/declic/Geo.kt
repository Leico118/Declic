package ch.leico.declic

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

/** Conversions de coordonnées entre l'orientation actuelle et l'orientation naturelle (portrait). */
object Geo {
    data class Screen(val w: Int, val h: Int, val rot: Int)

    fun screen(ctx: Context): Screen {
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION")
        d.getRealSize(p)
        return Screen(p.x, p.y, d.rotation)
    }

    private fun natural(s: Screen): Pair<Float, Float> =
        if (s.rot % 2 == 0) s.w.toFloat() to s.h.toFloat() else s.h.toFloat() to s.w.toFloat()

    /** Point de l'écran actuel -> fractions (0..1) dans l'orientation naturelle. */
    fun toNatural(s: Screen, x: Float, y: Float): Pair<Float, Float> {
        val (nw, nh) = natural(s)
        val (X, Y) = when (s.rot) {
            1 -> (nw - y) to x
            2 -> (nw - x) to (nh - y)
            3 -> y to (nh - x)
            else -> x to y
        }
        return (X / nw).coerceIn(0f, 1f) to (Y / nh).coerceIn(0f, 1f)
    }

    /** Fractions dans l'orientation naturelle -> point de l'écran actuel. */
    fun fromNatural(s: Screen, fx: Float, fy: Float): Pair<Float, Float> {
        val (nw, nh) = natural(s)
        val X = fx * nw
        val Y = fy * nh
        return when (s.rot) {
            1 -> Y to (nw - X)
            2 -> (nw - X) to (nh - Y)
            3 -> (nh - Y) to X
            else -> X to Y
        }
    }
}
