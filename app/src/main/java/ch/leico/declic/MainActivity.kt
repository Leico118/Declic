package ch.leico.declic

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val gold = Color.rgb(212, 180, 122)
    private val grey = Color.rgb(170, 170, 170)
    private val h = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }

    private lateinit var root: LinearLayout
    private lateinit var tvA11y: TextView
    private lateinit var btnA11y: Button
    private lateinit var tvEngine: TextView
    private lateinit var tvTrigger: TextView
    private lateinit var profilesBox: LinearLayout

    private val ticker = object : Runnable {
        override fun run() {
            if (Core.running) Core.send("Q")
            refreshStatus()
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(28), px(18), px(28))
        }
        build()
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        Core.start()
        refreshTrigger()
        refreshProfiles()
        h.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(ticker)
    }

    // ---------- Construction de l'écran ----------

    private fun build() {
        root.addView(TextView(this).apply {
            text = "Déclic"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(gold)
        })
        root.addView(text("Une touche → un appui sur l'écran, seulement dans les applis choisies.", grey, 14f))

        card("1 · Démarrage (débogage sans fil)").apply {
            tvEngine = text("", Color.WHITE, 13f).also { addView(it) }
            addView(button("Démarrer") { Core.start() })
            addView(text("Une seule fois : active les Options pour les développeurs et le « Débogage sans fil » (Wi-Fi allumé), puis touche « Associer » et suis la notification.\nAprès chaque redémarrage du téléphone : réactive le débogage sans fil, puis touche Démarrer.", grey, 12f))
            addView(button("Associer (une seule fois)") { startPairing() })
            addView(button("Ouvrir les options pour les développeurs") {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            })
        }

        card("2 · Service d'accessibilité").apply {
            tvA11y = text("", Color.WHITE, 14f).also { addView(it) }
            btnA11y = button("Ouvrir les réglages d'accessibilité") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.also { addView(it) }
            addView(text("Si l'interrupteur est grisé : Infos de l'appli Déclic → ⋮ en haut à droite → « Autoriser les paramètres restreints ».", grey, 12f))
            addView(button("Infos de l'appli Déclic") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }

        card("3 · Ta touche").apply {
            tvTrigger = text("", Color.WHITE, 14f).also { addView(it) }
            addView(button("Détecter la touche") { detect() })
            addView(text("Pense à régler l'action du Quick Button sur « Aucune » dans les paramètres du téléphone, sinon il ouvrira aussi l'appareil photo.", grey, 12f))
        }

        card("4 · Applis").apply {
            addView(Switch(this@MainActivity).apply {
                text = "Remappage actif"
                textSize = 16f
                setTextColor(Color.WHITE)
                isChecked = Prefs.active
                thumbTintList = ColorStateList.valueOf(gold)
                setOnCheckedChangeListener { _, c ->
                    Prefs.active = c
                    Core.refreshTarget()
                }
            })
            profilesBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }.also { addView(it) }
            addView(button("+ Ajouter une appli") { addApp() })
        }

        card("Réglage avancé · type d'appui").apply {
            addView(text("A : dans les applis choisies, Déclic prend la main sur l'écran et y ajoute le doigt de la touche, pour que tu puisses diriger, booster et sauter en même temps. B : ancien mode, plus simple, mais l'appui peut annuler tes autres doigts.", grey, 12f))
            val rg = RadioGroup(this@MainActivity)
            listOf("A · Fusion multi-touch (conseillé)", "B · Simple").forEachIndexed { i, t ->
                rg.addView(RadioButton(this@MainActivity).apply {
                    id = 100 + i
                    text = t
                    setTextColor(Color.WHITE)
                    buttonTintList = ColorStateList.valueOf(gold)
                })
            }
            rg.check(100 + Prefs.injectMode)
            rg.setOnCheckedChangeListener { _, id ->
                Prefs.injectMode = id - 100
                Core.pushAll()
            }
            addView(rg)
        }

        root.addView(text("Déclic ${BuildConfig.VERSION_NAME}", grey, 11f).apply { gravity = Gravity.CENTER })
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 20, 20))
                cornerRadius = 18 * dp
                setStroke(px(1), Color.rgb(45, 45, 45))
            }
        }
        c.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(gold)
            setPadding(0, 0, 0, px(6))
        })
        root.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
        return c
    }

    private fun text(t: String, color: Int, size: Float) = TextView(this).apply {
        text = t
        setTextColor(color)
        textSize = size
        setPadding(0, px(4), 0, px(4))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(Color.BLACK)
        backgroundTintList = ColorStateList.valueOf(gold)
        setOnClickListener { onClick() }
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- États ----------

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any {
            val c = ComponentName.unflattenFromString(it)
            c != null && c.packageName == packageName && c.className == ForegroundWatcher::class.java.name
        }
    }

    private fun startPairing() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            toast("Autorise les notifications, puis retouche « Associer ».")
            return
        }
        Core.startPairDiscovery()
        PairNotif.show(this, "Ouvre Débogage sans fil → « Associer l'appareil avec un code d'association », puis reviens ici (glisse le volet) pour taper le code.")
        AlertDialog.Builder(this)
            .setTitle("Association")
            .setMessage("1. Dans Options pour les développeurs, touche « Débogage sans fil » (le texte, pas l'interrupteur).\n2. Touche « Associer l'appareil avec un code d'association ».\n3. Sans fermer cette fenêtre, descends le volet des notifications : dans celle de Déclic, touche « Entrer le code » et tape le code à 6 chiffres.")
            .setPositiveButton("C'est parti") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .show()
    }

    private fun refreshStatus() {
        val a = a11yOn() && ForegroundWatcher.instance != null
        tvA11y.text = if (a) "✓ Activé" else "✗ Pas activé : Réglages → Accessibilité → Applis installées → Déclic"
        btnA11y.visibility = if (a) View.GONE else View.VISIBLE

        tvEngine.text = if (Core.running) Core.status + (if (Core.engineInfo.isNotEmpty()) "\n" + Core.engineInfo else "") else Core.status
    }

    private fun refreshTrigger() {
        val d = Prefs.trigDevice
        tvTrigger.text = if (d.isNullOrEmpty()) "Aucune touche choisie." else "✓ ${evName(Prefs.trigType, Prefs.trigCode)}\nsur « $d »"
    }

    // ---------- Détection de la touche ----------

    private class Det(val dev: String, val type: Int, val code: Int) {
        var count = 0
        var value = 0
    }

    private fun evName(type: Int, code: Int): String {
        val t = when (type) {
            1 -> "Touche"; 2 -> "REL"; 3 -> "ABS"; 4 -> "MSC"; 5 -> "Interrupteur"; else -> "Type $type"
        }
        val known = if (type == 1) when (code) {
            212 -> " (CAMERA)"; 528 -> " (CAMERA_FOCUS)"; 330 -> " (TOUCH)"; 115 -> " (VOLUME +)"
            114 -> " (VOLUME −)"; 116 -> " (POWER)"; else -> ""
        } else ""
        return "$t $code$known"
    }

    private fun detect() {
        if (!Core.running) return toast("Le moteur n'est pas démarré : termine d'abord l'étape 1.")
        val items = ArrayList<Det>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val list = ListView(this).apply { this.adapter = adapter }
        var open = true
        val dlg = AlertDialog.Builder(this)
            .setTitle("Appuie sur ta touche…")
            .setMessage("Appuie plusieurs fois sur la touche (sans toucher l'écran), puis touche la ligne qui a le plus de ×. Les lignes « Touche » sont les meilleures.")
            .setView(list)
            .setNegativeButton("Fermer", null)
            .create()

        fun redraw() {
            items.sortWith(compareBy<Det>({ if (it.type == 1) 0 else 1 }, { -it.count }))
            adapter.clear()
            adapter.addAll(items.map { "${evName(it.type, it.code)} · ×${it.count} · valeur ${it.value}\n${it.dev}" })
        }

        val listener: (String, Int, Int, Int) -> Unit = { dev, type, code, value ->
            runOnUiThread {
                if (open) {
                    var d = items.firstOrNull { it.dev == dev && it.type == type && it.code == code }
                    if (d == null) {
                        d = Det(dev, type, code)
                        items.add(d)
                    }
                    if (type != 1 || value == 1) d.count++
                    d.value = value
                    redraw()
                }
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val d = items.getOrNull(pos) ?: return@setOnItemClickListener
            Prefs.trigDevice = d.dev
            Prefs.trigType = d.type
            Prefs.trigCode = d.code
            dlg.dismiss()
        }
        dlg.setOnDismissListener {
            open = false
            Core.detectListener = null
            Core.setDetection(false)
            Core.pushAll()
            refreshTrigger()
        }
        dlg.show()
        h.postDelayed({
            if (open) {
                Core.detectListener = listener
                Core.setDetection(true)
            }
        }, 400)
    }

    // ---------- Applis ----------

    private fun addApp() {
        val pm = packageManager
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("Choisir une appli")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                val (pkg, label) = apps[i]
                if (Prefs.profile(pkg) == null) Prefs.upsert(Profile(pkg, label, -1f, -1f))
                refreshProfiles()
                place(pkg)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun place(pkg: String) {
        val w = ForegroundWatcher.instance ?: return toast("Active d'abord le service d'accessibilité (étape 2).")
        w.showPicker(pkg)
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    private fun refreshProfiles() {
        profilesBox.removeAllViews()
        val list = Prefs.profiles()
        if (list.isEmpty()) {
            profilesBox.addView(text("Aucune appli pour l'instant.", grey, 13f))
            return
        }
        for (p in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(8), 0, px(4))
            }
            row.addView(text(p.label, Color.WHITE, 15f))
            row.addView(text(if (p.hasPos) "Position définie ✓" else "Position à définir", if (p.hasPos) gold else grey, 12f))
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(button("Placer le point") { place(p.pkg) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(8) })
            btns.addView(button("Retirer") {
                Prefs.remove(p.pkg)
                Core.refreshTarget()
                refreshProfiles()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(btns)
            profilesBox.addView(row)
        }
    }
}
