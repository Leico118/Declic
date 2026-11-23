package ch.leico.declic

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.Display
import io.github.muntashirakon.adb.AdbStream
import java.io.InputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.util.concurrent.Executors

/** Démarre le moteur via le débogage sans fil et lui transmet les réglages. */
object Core {
    lateinit var app: Context
    @Volatile var currentPkg: String? = null
    @Volatile var pickerOpen = false
    @Volatile var running = false
    @Volatile var starting = false
    @Volatile var status: String = "Arrêté"
    @Volatile var engineInfo: String = ""
    @Volatile var detectListener: ((String, Int, Int, Int) -> Unit)? = null
    @Volatile var pairPort = 0
    /** Vrai quand la touche est remappée dans l'appli au premier plan (sert à bloquer son action d'origine). */
    @Volatile var mappingActive = false
    @Volatile var testOpen = false
    @Volatile var lastKeyAt = 0L

    private var stream: AdbStream? = null
    @Volatile private var out: OutputStream? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var inited = false
    private var nsdListener: NsdManager.DiscoveryListener? = null

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        Prefs.init(app)
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) refreshTarget()
            }
        }, main)
    }

    // ---------- Démarrage du moteur ----------

    fun start() {
        if (running || starting) return
        starting = true
        status = "Connexion…"
        io.execute {
            try {
                val m = AdbManager.get(app)
                val apk = app.applicationInfo.sourceDir
                val cmd = "shell:CLASSPATH=$apk exec app_process /system/bin ch.leico.declic.Engine"
                val s = try {
                    m.openStream(cmd)
                } catch (_: Throwable) {
                    val ok = try {
                        m.connectTls(app, 8000)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!ok) throw Exception("Connexion impossible. Le débogage sans fil est-il activé (Wi-Fi allumé) ? L'association a-t-elle été faite ?")
                    m.openStream(cmd)
                }
                stream = s
                out = s.openOutputStream()
                running = true
                status = "✓ Moteur démarré"
                val input = s.openInputStream()
                Thread({ readLoop(input) }, "declic-lecture").start()
                pushAll()
            } catch (e: Throwable) {
                running = false
                status = "✗ " + (e.message ?: e.toString())
            } finally {
                starting = false
            }
        }
    }

    private fun readLoop(input: InputStream) {
        try {
            input.bufferedReader().forEachLine { onLine(it) }
        } catch (_: Throwable) {
        }
        running = false
        mappingActive = false
        out = null
        stream = null
        if (!status.startsWith("✗")) status = "✗ Moteur arrêté (touche Démarrer)"
    }

    private fun onLine(line: String) {
        if (line.isEmpty()) return
        when {
            line.startsWith("E ") -> {
                val p = line.split(" ", limit = 5)
                if (p.size >= 4) {
                    detectListener?.invoke(p.getOrElse(4) { "?" }, p[1].toInt(), p[2].toInt(), p[3].toInt())
                }
            }
            line.startsWith("K ") -> lastKeyAt = line.substring(2).trim().toLongOrNull() ?: 0L
            line.startsWith("S ") -> engineInfo = line.substring(2).replace(" ⏎ ", "\n")
            line == "R" -> status = "✓ Moteur démarré"
            else -> engineInfo = "Message du système : $line"
        }
    }

    fun send(line: String) {
        io.execute {
            val o = out ?: return@execute
            try {
                o.write((line + "\n").toByteArray())
                o.flush()
            } catch (_: Throwable) {
                running = false
                out = null
            }
        }
    }

    fun pushAll() {
        send("M ${Prefs.injectMode}")
        send("H 70")
        val dev = Prefs.trigDevice
        if (!dev.isNullOrEmpty()) send("T ${Prefs.trigType} ${Prefs.trigCode} $dev")
        refreshTarget()
    }

    fun setDetection(on: Boolean) = send(if (on) "D 1" else "D 0")

    fun onForeground(pkg: String) {
        if (pkg == currentPkg) return
        currentPkg = pkg
        refreshTarget()
    }

    /** Envoie au moteur la position à toucher pour l'appli au premier plan (ou désactive). */
    fun refreshTarget() {
        if (!running) {
            mappingActive = false
            return
        }
        val pkg = currentPkg
        val screenOn = try {
            app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).state == Display.STATE_ON
        } catch (_: Throwable) {
            true
        }
        val p = if (Prefs.active && !pickerOpen && screenOn && pkg != null) Prefs.profile(pkg) else null
        if (testOpen && screenOn) {
            val scr = Geo.screen(app)
            send("G ${scr.w} ${scr.h} ${scr.rot}")
            send("P 1 ${scr.w * 0.75f} ${scr.h * 0.5f}")
            mappingActive = true
        } else if (p != null && p.hasPos) {
            val scr = Geo.screen(app)
            val (x, y) = Geo.fromNatural(scr, p.fx, p.fy)
            send("G ${scr.w} ${scr.h} ${scr.rot}")
            send("P 1 $x $y")
            mappingActive = true
        } else {
            send("P 0 0 0")
            mappingActive = false
        }
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
                val ok = AdbManager.get(app).pair("127.0.0.1", port, code.trim())
                if (!ok) throw Exception("Code refusé, réessaie avec un nouveau code.")
                stopPairDiscovery()
                PairNotif.show(app, "Association réussie ✓ Tu peux revenir dans Déclic.", withInput = false)
                main.post { start() }
            } catch (e: Throwable) {
                PairNotif.show(app, "Échec : ${e.message ?: e}")
            }
        }
    }
}
