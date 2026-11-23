package ch.leico.declic

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Zone de test : Déclic y est actif (point virtuel à droite) et dessine tous les doigts reçus,
 * pour vérifier le mode fusion et mesurer le délai touche -> appui.
 */
class TestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        setContentView(Pad(this))
    }

    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (Core.running) Core.send("Q")
            window.decorView.findViewById<View>(android.R.id.content)?.let { (it as? android.view.ViewGroup)?.getChildAt(0)?.invalidate() }
            h.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        Core.testOpen = true
        Core.refreshTarget()
        h.post(poll)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(poll)
        Core.testOpen = false
        Core.refreshTarget()
    }

    private class Pad(c: Context) : View(c) {
        private val dp = c.resources.displayMetrics.density
        private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(212, 180, 122); style = Paint.Style.STROKE; strokeWidth = 3 * dp }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 212, 180, 122) }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * dp }
        private val grey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 160, 160); textSize = 12 * dp }
        private val log = ArrayDeque<String>()
        private var last: MotionEvent? = null
        private var total = 0
        private var cancels = 0

        init {
            setBackgroundColor(Color.BLACK)
        }

        private fun actionName(e: MotionEvent): String = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            else -> "action ${e.actionMasked}"
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            total++
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) cancels++
            if (e.actionMasked != MotionEvent.ACTION_MOVE) {
                val idx = e.actionIndex
                val id = e.getPointerId(idx)
                var line = "${actionName(e)} doigt $id · ${e.pointerCount} doigt(s)"
                // Délai entre l'envoi de l'événement (par Déclic ou l'écran) et sa réception ici
                line += " · attente système ${SystemClock.uptimeMillis() - e.eventTime} ms"
                log.addFirst(line)
                while (log.size > 12) log.removeLast()
            }
            last?.recycle()
            last = MotionEvent.obtain(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                last?.recycle()
                last = null
            }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val tx = width * 0.75f
            val ty = height * 0.5f
            canvas.drawCircle(tx, ty, 34 * dp, gold)
            canvas.drawText("point de la touche", tx - 60 * dp, ty + 54 * dp, grey)
            last?.let { e ->
                for (i in 0 until e.pointerCount) {
                    val x = e.getX(i)
                    val y = e.getY(i)
                    canvas.drawCircle(x, y, 28 * dp, fill)
                    canvas.drawText("doigt ${e.getPointerId(i)}", x - 24 * dp, y - 34 * dp, white)
                }
            }
            var y = 28 * dp
            canvas.drawText("Zone de test Déclic : pose des doigts + appuie sur ta touche", 12 * dp, y, white)
            y += 20 * dp
            for (l in Core.engineInfo.split("\n")) {
                canvas.drawText(l.take(140), 12 * dp, y, grey)
                y += 16 * dp
            }
            canvas.drawText("Mode ${if (Prefs.injectMode == 0) "A · fusion" else "B · simple"} · événements $total · annulations $cancels", 12 * dp, y, grey)
            y += 22 * dp
            for (l in log) {
                canvas.drawText(l, 12 * dp, y, white)
                y += 18 * dp
            }
        }
    }
}
