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
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
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
    private lateinit var bindsBox: LinearLayout
    private lateinit var profilesBox: LinearLayout

    private val ticker = object : Runnable {
        override fun run() {
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
        Core.uiOpen++
        Core.update()
        refreshBinds()
        refreshProfiles()
        h.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(ticker)
        Core.uiOpen--
        Core.update()
    }

    // ---------- Construction de l'écran ----------

    private fun build() {
        root.addView(TextView(this).apply {
            text = "Déclic"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(gold)
        })
        root.addView(text("Tes touches → des appuis sur l'écran, seulement dans les applis choisies.", grey, 14f))

        card("1 · Moteur").apply {
            tvEngine = text("", Color.WHITE, 13f).also { addView(it) }
            addView(button("Démarrer") { Core.ensureEngine() })
            addView(text("Le moteur se lance via le débogage sans fil, puis Déclic coupe le débogage : le moteur continue tout seul (même sans Wi-Fi), jusqu'au prochain redémarrage du téléphone.\nAprès un redémarrage : active le débogage sans fil (en Wi-Fi), Déclic s'occupe du reste, même sans l'ouvrir.\nAstuce : Options pour les développeurs → « Tuiles de développeur pour la configuration rapide » → active « Débogage sans fil », pour l'avoir dans le volet.", grey, 12f))
            addView(button("Associer (une seule fois)") { startPairing() })
            addView(button("Ouvrir les options pour les développeurs") { openDevSettings() })
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

        card("3 · Tes touches").apply {
            addView(text("Coche les touches à utiliser. Si tu décoches tout, le moteur s'arrête.", grey, 12f))
            bindsBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }.also { addView(it) }
            addView(button("+ Ajouter une touche") { detect() })
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
                    Core.update()
                }
            })
            profilesBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }.also { addView(it) }
            addView(button("+ Ajouter une appli") { addApp() })
            addView(button("Zone de test (vérifier les doigts et le délai)") {
                startActivity(Intent(this@MainActivity, TestActivity::class.java))
            })
        }

        card("Réglage avancé · type d'appui").apply {
            addView(text("A : Déclic prend la main sur l'écran et y ajoute les doigts des touches, pour diriger, booster et sauter en même temps. B : plus simple, mais un appui peut annuler tes autres doigts.", grey, 12f))
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
                Core.update()
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

    private fun openDevSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- États ----------

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any {
            val c = ComponentName.unflattenFromString(it)
            c != null && c.packageName == packageName && c.className == ForegroundWatcher::class.java.name
        }
    }

    private fun refreshStatus() {
        val a = a11yOn() && ForegroundWatcher.instance != null
        tvA11y.text = if (a) "✓ Activé" else "✗ Pas activé : Réglages → Accessibilité → Applis installées → Déclic"
        btnA11y.visibility = if (a) View.GONE else View.VISIBLE

        val alive = Core.engineAlive()
        val sb = StringBuilder()
        sb.append(if (alive) Core.engineText() else if (Core.starting) "Démarrage du moteur…" else "✗ Moteur arrêté")
        if (Core.startMsg.isNotEmpty() && !Core.starting) sb.append("\n").append(Core.startMsg)
        if (Core.canToggleDebug()) sb.append("\nActivation automatique du débogage : ✓")
        tvEngine.text = sb.toString()
    }

    // ---------- Touches ----------

    private fun refreshBinds() {
        bindsBox.removeAllViews()
        val list = Prefs.binds()
        if (list.isEmpty()) {
            bindsBox.addView(text("Aucune touche pour l'instant.", grey, 13f))
            return
        }
        for (b in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(CheckBox(this).apply {
                text = "${b.name}\n${b.dev}"
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(gold)
                isChecked = b.enabled
                setOnCheckedChangeListener { _, c ->
                    Prefs.setBindEnabled(b.id, c)
                    Core.update()
                    refreshProfiles()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("Supprimer") {
                AlertDialog.Builder(this)
                    .setMessage("Supprimer « ${b.name} » et ses positions ?")
                    .setPositiveButton("Supprimer") { _, _ ->
                        Prefs.removeBind(b.id)
                        Core.update()
                        refreshBinds()
                        refreshProfiles()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            })
            bindsBox.addView(row)
        }
    }

    private class Det(val dev: String, val type: Int, val code: Int, val firstAt: Long) {
        var count = 0
        var value = 0
    }

    private fun evName(type: Int, code: Int): String {
        if (type == 1) return when (code) {
            115 -> "Volume +"
            114 -> "Volume −"
            116 -> "Marche/arrêt"
            212 -> "Appareil photo"
            722 -> "Quick Button (appui)"
            724 -> "Quick Button (double appui)"
            330 -> "Toucher (330)"
            else -> "Touche $code"
        }
        val t = when (type) {
            2 -> "REL"; 3 -> "ABS"; 4 -> "MSC"; 5 -> "Interrupteur"; else -> "Type $type"
        }
        return "$t $code"
    }

    private fun detect() {
        if (!Core.engineAlive()) return toast("Le moteur n'est pas lancé : touche « Démarrer » dans la carte 1.")
        val items = ArrayList<Det>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val list = ListView(this).apply { this.adapter = adapter }
        var open = true
        var t0 = 0L
        val dlg = AlertDialog.Builder(this)
            .setTitle("Appuie sur ta touche…")
            .setMessage("Sans toucher l'écran : appuie sur la touche (ou fais le geste voulu), puis touche la ligne correspondante. Le chrono indique quand chaque signal est arrivé. « Effacer » pour recommencer.")
            .setView(list)
            .setNegativeButton("Fermer", null)
            .setNeutralButton("Effacer", null)
            .create()

        fun redraw() {
            items.sortWith(compareBy<Det> { it.firstAt })
            adapter.clear()
            adapter.addAll(items.map { "+${it.firstAt} ms · ${evName(it.type, it.code)} · ×${it.count} · valeur ${it.value}\n${it.dev}" })
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val d = items.getOrNull(pos) ?: return@setOnItemClickListener
            Prefs.addBind(evName(d.type, d.code), d.dev, d.type, d.code)
            dlg.dismiss()
        }
        dlg.setOnDismissListener {
            open = false
            Core.stopDetection()
            refreshBinds()
            refreshProfiles()
        }
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            items.clear()
            adapter.clear()
        }
        h.postDelayed({
            if (open) Core.startDetection { dev, type, code, value ->
                if (open) {
                    val now = SystemClock.uptimeMillis()
                    if (items.isEmpty()) t0 = now
                    var d = items.firstOrNull { it.dev == dev && it.type == type && it.code == code }
                    if (d == null) {
                        d = Det(dev, type, code, now - t0)
                        items.add(d)
                    }
                    if (type != 1 || value == 1) d.count++
                    d.value = value
                    redraw()
                }
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
                Prefs.addApp(pkg, label)
                refreshProfiles()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun place(pkg: String, bindId: Int) {
        val w = ForegroundWatcher.instance ?: return toast("Active d'abord le service d'accessibilité (étape 2).")
        w.showPicker(pkg, bindId)
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
        val binds = Prefs.binds()
        for (p in list) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(10), 0, px(4))
            }
            box.addView(text(p.label, Color.WHITE, 16f).apply { setTypeface(typeface, Typeface.BOLD) })
            if (binds.isEmpty()) box.addView(text("Ajoute d'abord une touche (carte 3).", grey, 12f))
            for (b in binds) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val placed = p.pos.containsKey(b.id)
                val state = when {
                    !b.enabled -> "décochée"
                    placed -> "placée ✓"
                    else -> "pas placée"
                }
                row.addView(text("${b.name} · $state", if (placed && b.enabled) gold else grey, 13f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button(if (placed) "Déplacer" else "Placer") { place(p.pkg, b.id) })
                box.addView(row)
            }
            box.addView(button("Retirer ${p.label}") {
                Prefs.removeApp(p.pkg)
                Core.update()
                refreshProfiles()
            })
            profilesBox.addView(box)
        }
    }

    // ---------- Association ----------

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
            .setMessage("1. Active « Débogage sans fil » (Wi-Fi allumé) et touche son texte.\n2. Touche « Associer l'appareil avec un code d'association ».\n3. Sans fermer cette fenêtre, descends le volet des notifications : dans celle de Déclic, touche « Entrer le code » et tape le code à 6 chiffres.")
            .setPositiveButton("C'est parti") { _, _ -> openDevSettings() }
            .show()
    }
}
