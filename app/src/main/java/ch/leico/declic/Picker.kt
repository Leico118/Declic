package ch.leico.declic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Cercle déplaçable affiché par-dessus le jeu pour choisir où « appuyer ». */
class Picker(
    private val svc: ForegroundWatcher,
    private val pkg: String,
    private val onClosed: () -> Unit,
) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val dp = svc.resources.displayMetrics.density
    private val size = (72 * dp).toInt()
    private var dot: View? = null
    private var bar: View? = null
    private lateinit var dotLp: WindowManager.LayoutParams
    private lateinit var barLp: WindowManager.LayoutParams

    private fun lp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    fun show() {
        Core.pickerOpen = true
        Core.refreshTarget()
        val scr = Geo.screen(svc)
        val p = Prefs.profile(pkg)
        val (cx, cy) = if (p != null && p.hasPos) Geo.fromNatural(scr, p.fx, p.fy) else (scr.w / 2f) to (scr.h / 2f)

        val d = DotView(svc)
        dotLp = lp(size, size).apply {
            x = (cx - size / 2f).toInt()
            y = (cy - size / 2f).toInt()
        }
        makeDraggable(d, d) { dotLp }
        wm.addView(d, dotLp)
        dot = d

        val b = buildBar()
        barLp = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = (16 * dp).toInt()
            y = (48 * dp).toInt()
        }
        wm.addView(b, barLp)
        bar = b
    }

    private fun buildBar(): View {
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 18, 18, 18))
                cornerRadius = 16 * dp
                setStroke((1 * dp).toInt(), Color.rgb(212, 180, 122))
            }
        }
        val label = TextView(svc).apply {
            text = "✥ Glisse le cercle\nsur le bouton du jeu"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, (10 * dp).toInt(), 0)
        }
        val ok = Button(svc).apply {
            text = "Valider"
            setOnClickListener { validate() }
        }
        val cancel = Button(svc).apply {
            text = "Annuler"
            setOnClickListener { close() }
        }
        box.addView(label)
        box.addView(ok)
        box.addView(cancel)
        makeDraggable(label, box) { barLp } // on peut aussi déplacer ce bandeau par son texte
        return box
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View, window: View, params: () -> WindowManager.LayoutParams) {
        var sx = 0
        var sy = 0
        var rx = 0f
        var ry = 0f
        handle.setOnTouchListener { _, ev ->
            val p = params()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x; sy = p.y; rx = ev.rawX; ry = ev.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (ev.rawX - rx).toInt()
                    p.y = sy + (ev.rawY - ry).toInt()
                    try {
                        wm.updateViewLayout(window, p)
                    } catch (_: Exception) {
                    }
                }
            }
            true
        }
    }

    private fun validate() {
        val d = dot ?: return
        val loc = IntArray(2)
        d.getLocationOnScreen(loc)
        val cx = loc[0] + size / 2f
        val cy = loc[1] + size / 2f
        val (fx, fy) = Geo.toNatural(Geo.screen(svc), cx, cy)
        val label = Prefs.profile(pkg)?.label ?: pkg
        Prefs.upsert(Profile(pkg, label, fx, fy))
        Toast.makeText(svc, "Position enregistrée pour $label", Toast.LENGTH_SHORT).show()
        close()
    }

    fun close() {
        try {
            dot?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            bar?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        dot = null
        bar = null
        Core.pickerOpen = false
        Core.refreshTarget()
        onClosed()
    }

    private class DotView(c: Context) : View(c) {
        private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(212, 180, 122)
            style = Paint.Style.STROKE
            strokeWidth = 3 * c.resources.displayMetrics.density
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 212, 180, 122) }
        private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(212, 180, 122) }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - gold.strokeWidth
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, gold)
            canvas.drawLine(cx - r * 0.35f, cy, cx + r * 0.35f, cy, gold)
            canvas.drawLine(cx, cy - r * 0.35f, cx, cy + r * 0.35f, gold)
            canvas.drawCircle(cx, cy, gold.strokeWidth, center)
        }
    }
}
