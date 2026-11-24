package ch.leico.declic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Côté appli : lance le moteur (via le débogage sans fil, qui est ensuite coupé), lui écrit ses
 * réglages dans un fichier et lit son état.
 */
object Core {
    lateinit var app: Context
    @Volatile var currentPkg: String? = null
    @Volatile var pickerOpen = false
    @Volatile var testOpen = false
    @Volatile var uiOpen = 0
    @Volatile var detecting = false
    @Volatile var starting = false
    @Volatile var startMsg: String = ""
    @Volatile var detectListener: ((String, Int, Int, Int) -> Unit)? = null
    @Volatile var pairPort = 0
    /** Touches remappées dans l'appli au premier plan (pour bloquer leur action d'origine). */
    @Volatile var activeKeys: Set<Int> = emptySet()

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var inited = false
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private var eventsPos = 0L
    private var lastFailNotif = 0L

    private val dir: File get() = app.getExternalFilesDir(null) ?: app.filesDir
    private val cfgFile get() = File(dir, "config.json")
    private val statusFile get() = File(dir, "status.txt")
    private val eventsFile get() = File(dir, "events.txt")

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        Prefs.init(app)
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) update()
            }
        }, main)
        // Dès que le débogage sans fil est activé (ex. tuile des réglages rapides), on lance le moteur.
        try {
            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"), false,
                object : android.database.ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        val on = Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1
                        if (on && !engineAlive() && Prefs.binds().any { it.enabled } && Prefs.active) {
                            main.postDelayed({ ensureEngine() }, 1500)
                        }
                    }
                })
        } catch (_: Throwable) {
        }
    }

    // ---------- État du moteur ----------

    /** Le moteur tourne-t-il (état récent, même version) ? */
    fun engineAlive(): Boolean = try {
        val first = statusFile.bufferedReader().use { it.readLine() } ?: ""
        val v = Regex("v=(\\d+)").find(first)?.groupValues?.get(1)?.toInt() ?: -1
        val t = Regex("t=(\\d+)").find(first)?.groupValues?.get(1)?.toLong() ?: 0L
        v == BuildConfig.VERSION_CODE && System.currentTimeMillis() - t < 4000
    } catch (_: Throwable) {
        false
    }

    private var aliveAt = 0L
    private var aliveVal = false

    /** Version rapide (mise en cache 1 s) pour les appuis de touches. */
    fun aliveFast(): Boolean {
        val now = System.currentTimeMillis()
        if (now - aliveAt > 1000) {
            aliveVal = engineAlive()
            aliveAt = now
        }
        return aliveVal
    }

    fun engineText(): String = try {
        statusFile.readText().substringAfter('\n')
    } catch (_: Throwable) {
        ""
    }

    fun canToggleDebug(): Boolean =
        app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    // ---------- Réglages envoyés au moteur ----------

    private fun screenOn(): Boolean = try {
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).state == Display.STATE_ON
    } catch (_: Throwable) {
        true
    }

    /** Recalcule ce que le moteur doit faire et l'écrit. Démarre le moteur si besoin. */
    fun update() {
        val binds = JSONArray()
        val keys = HashSet<Int>()
        val on = Prefs.active && screenOn() && !pickerOpen
        if (on && testOpen) {
            val scr = Geo.screen(app)
            val (fx, fy) = Geo.toNatural(scr, scr.w * 0.75f, scr.h * 0.5f)
            for (b in Prefs.binds().filter { it.enabled }) {
                binds.put(JSONObject().put("id", b.id).put("dev", b.dev).put("type", b.type).put("code", b.code).put("fx", fx.toDouble()).put("fy", fy.toDouble()))
                if (b.type == 1) keys.add(b.code)
            }
        } else if (on) {
            val pkg = currentPkg
            if (pkg != null) for ((b, p) in Prefs.activeFor(pkg)) {
                binds.put(JSONObject().put("id", b.id).put("dev", b.dev).put("type", b.type).put("code", b.code).put("fx", p.first.toDouble()).put("fy", p.second.toDouble()))
                if (b.type == 1) keys.add(b.code)
            }
        }
        activeKeys = keys
        val needed = binds.length() > 0 || uiOpen > 0 || detecting
        val anyEnabled = Prefs.binds().any { it.enabled } && Prefs.active
        val cfg = JSONObject()
            .put("v", BuildConfig.VERSION_CODE)
            .put("stop", !anyEnabled && uiOpen == 0 && !detecting)
            .put("mode", Prefs.injectMode)
            .put("minHold", 70)
            .put("detect", detecting)
            .put("binds", binds)
        writeConfig(cfg.toString())
        if (needed && anyEnabled || uiOpen > 0) ensureEngine(binds.length() > 0)
    }

    private fun writeConfig(txt: String) {
        try {
            val tmp = File(dir, "config.tmp")
            tmp.writeText(txt)
            if (!tmp.renameTo(cfgFile)) cfgFile.writeText(txt)
        } catch (_: Throwable) {
        }
    }

    fun onForeground(pkg: String) {
        if (pkg == currentPkg) return
        currentPkg = pkg
        update()
    }

    // ---------- Démarrage du moteur ----------

    /**
     * Lance le moteur s'il ne tourne pas : active le débogage sans fil si on en a le droit,
     * s'y connecte, lance le moteur détaché, puis coupe le débogage sans fil.
     */
    fun ensureEngine(fromGame: Boolean = false) {
        if (starting || engineAlive()) return
        starting = true
        startMsg = "Démarrage du moteur…"
        io.execute {
            var weEnabled = false
            try {
                val cr = app.contentResolver
                val wasOn = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
                if (!wasOn) {
                    if (!canToggleDebug()) throw Exception("Le débogage sans fil est coupé. Active-le (Wi-Fi allumé), puis touche Démarrer : Déclic le coupera tout seul ensuite.")
                    Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                    weEnabled = true
                    Thread.sleep(1500)
                }
                val m = AdbManager.get(app)
                try {
                    m.disconnect()
                } catch (_: Throwable) {
                }
                val ok = try {
                    m.connectTls(app, 10000)
                } catch (_: Throwable) {
                    false
                }
                if (!ok) throw Exception("Connexion au débogage sans fil impossible. Es-tu connecté à un Wi-Fi ? L'association a-t-elle été faite ?")
                val apk = app.applicationInfo.sourceDir
                val pkg = app.packageName
                val script = "pkill -x declic_moteur; pkill -f '[c]h[.]leico[.]declic[.]Engine'; " +
                    "C=ch.leico.declic; CLASSPATH=$apk setsid sh -c 'for g in /acct /dev/cg2_bpf /sys/fs/cgroup /sys/fs/cgroup/uid_0; do echo \$\$ > \$g/cgroup.procs; done 2>/dev/null; exec app_process /system/bin --nice-name=declic_moteur '\$C'.Engine' </dev/null >/dev/null 2>&1 & " +
                    "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; echo GRANT=\$?; " +
                    "(sleep 3; settings put global adb_wifi_enabled 0) </dev/null >/dev/null 2>&1 & " +
                    "echo FIN"
                val s = m.openStream("shell:$script")
                val outTxt = StringBuilder()
                try {
                    s.openInputStream().bufferedReader().forEachLine {
                        outTxt.append(it).append('\n')
                    }
                } catch (_: Throwable) {
                }
                try {
                    m.disconnect()
                } catch (_: Throwable) {
                }
                // on attend le premier « battement de cœur » du moteur
                var alive = false
                for (i in 0 until 30) {
                    Thread.sleep(200)
                    if (engineAlive()) {
                        alive = true
                        break
                    }
                }
                val grant = if (outTxt.contains("GRANT=0") || canToggleDebug()) "activation automatique du débogage autorisée ✓"
                else "après un redémarrage, il suffira d'activer le débogage sans fil (tuile des réglages rapides) : Déclic lancera le moteur et le recoupera tout seul"
                startMsg = if (alive) "✓ Moteur lancé, débogage sans fil coupé · $grant"
                else "✗ Le moteur ne répond pas. Réponse du système : ${outTxt.toString().trim().take(200)}"
                main.post { update() }
            } catch (e: Throwable) {
                startMsg = "✗ " + (e.message ?: e.toString())
                if (weEnabled) try {
                    Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 0)
                } catch (_: Throwable) {
                }
                if (fromGame && System.currentTimeMillis() - lastFailNotif > 60_000) {
                    lastFailNotif = System.currentTimeMillis()
                    PairNotif.info(app, "Déclic n'a pas pu démarrer son moteur", startMsg.removePrefix("✗ "))
                }
            } finally {
                starting = false
            }
        }
    }

    /** Arrête le moteur (il lit « stop » dans ses réglages). */
    fun stopEngine() {
        writeConfig(JSONObject().put("v", BuildConfig.VERSION_CODE).put("stop", true).toString())
    }

    // ---------- Détection des touches ----------

    fun startDetection(listener: (String, Int, Int, Int) -> Unit) {
        detectListener = listener
        detecting = true
        eventsPos = 0L
        update()
        pollEvents()
    }

    fun stopDetection() {
        detecting = false
        detectListener = null
        update()
    }

    private fun pollEvents() {
        if (!detecting) return
        try {
            val f = eventsFile
            if (f.exists()) {
                if (f.length() < eventsPos) eventsPos = 0L
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(eventsPos)
                    while (true) {
                        val line = raf.readLine() ?: break
                        val p = line.split(" ", limit = 4)
                        if (p.size >= 3) {
                            detectListener?.invoke(p.getOrElse(3) { "?" }, p[0].toInt(), p[1].toInt(), p[2].toInt())
                        }
                    }
                    eventsPos = raf.filePointer
                }
            }
        } catch (_: Throwable) {
        }
        main.postDelayed({ pollEvents() }, 80)
    }

    // ---------- Association (une seule fois) ----------

    fun startPairDiscovery() {
        if (nsdListener != null) return
        pairPort = 0
        val nsd = app.getSystemService(NsdManager::class.java)
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo != null) resolve(nsd, serviceInfo)
            }
        }
        try {
            nsd.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, l)
            nsdListener = l
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                    val si = serviceInfo ?: return
                    val host = si.host?.hostAddress
                    if (host == null || isLocal(host)) {
                        pairPort = si.port
                        PairNotif.show(app, "Fenêtre d'association détectée ✓\nTouche « Entrer le code » et tape le code à 6 chiffres.")
                    }
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun isLocal(host: String): Boolean = try {
        val h = host.substringBefore('%')
        NetworkInterface.getNetworkInterfaces().toList().any { ni ->
            ni.inetAddresses.toList().any { it.hostAddress?.substringBefore('%') == h }
        }
    } catch (_: Throwable) {
        true
    }

    fun stopPairDiscovery() {
        val l = nsdListener ?: return
        nsdListener = null
        try {
            app.getSystemService(NsdManager::class.java).stopServiceDiscovery(l)
        } catch (_: Throwable) {
        }
    }

    fun pair(code: String) {
        io.execute {
            try {
                val port = pairPort
                if (port == 0) throw Exception("Fenêtre d'association pas détectée. Ouvre « Associer l'appareil avec un code d'association », puis réessaie.")
                AdbManager.get(app).pair("127.0.0.1", port, code.trim())
                stopPairDiscovery()
                PairNotif.show(app, "Association réussie ✓ Reviens dans Déclic et touche « Démarrer ».", withInput = false)
            } catch (e: Throwable) {
                PairNotif.show(app, "Échec : ${e.message ?: e}")
            }
        }
    }
}
