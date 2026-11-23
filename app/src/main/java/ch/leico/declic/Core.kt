package ch.leico.declic

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import rikka.shizuku.Shizuku

/** Lien entre l'appli et le « moteur » (processus lancé par Shizuku, qui lit la touche et simule l'appui). */
object Core {
    lateinit var app: Context
    @Volatile var service: IDeclicService? = null
    @Volatile var currentPkg: String? = null
    @Volatile var lastError: String? = null
    @Volatile var pickerOpen = false
    private val main = Handler(Looper.getMainLooper())
    private var inited = false
    private var binding = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, DeclicUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("moteur")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder != null && binder.pingBinder()) {
                service = IDeclicService.Stub.asInterface(binder)
                pushAll()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        Prefs.init(app)
        Shizuku.addBinderReceivedListenerSticky(Shizuku.OnBinderReceivedListener { main.post { tryBind() } })
        Shizuku.addBinderDeadListener(Shizuku.OnBinderDeadListener { service = null; binding = false })
        Shizuku.addRequestPermissionResultListener(Shizuku.OnRequestPermissionResultListener { _, res ->
            if (res == PackageManager.PERMISSION_GRANTED) main.post { tryBind() }
        })
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) refreshTarget()
            }
        }, main)
    }

    /** 0 = Shizuku absent ou arrêté, 1 = autorisation manquante, 2 = prêt. */
    fun shizukuState(): Int = try {
        when {
            !Shizuku.pingBinder() -> 0
            Shizuku.isPreV11() -> 0
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> 1
            else -> 2
        }
    } catch (_: Throwable) {
        0
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(42)
        } catch (e: Throwable) {
            lastError = e.toString()
        }
    }

    fun tryBind() {
        if (service != null || binding) return
        if (shizukuState() != 2) return
        try {
            binding = true
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) {
            binding = false
            lastError = e.toString()
        }
    }

    fun restartEngine() {
        try {
            Shizuku.unbindUserService(args, conn, true)
        } catch (_: Throwable) {
        }
        service = null
        binding = false
        main.postDelayed({ tryBind() }, 700)
    }

    fun pushAll() {
        val s = service ?: return
        try {
            s.setInjectMode(Prefs.injectMode)
            s.setTrigger(Prefs.trigDevice ?: "", Prefs.trigType, Prefs.trigCode)
        } catch (e: Throwable) {
            lastError = e.toString()
        }
        refreshTarget()
    }

    fun onForeground(pkg: String) {
        if (pkg == currentPkg) return
        currentPkg = pkg
        refreshTarget()
    }

    /** Envoie au moteur la position à toucher pour l'appli au premier plan (ou désactive). */
    fun refreshTarget() {
        val s = service ?: return
        val pkg = currentPkg
        val p = if (Prefs.active && !pickerOpen && pkg != null) Prefs.profile(pkg) else null
        try {
            if (p != null && p.hasPos) {
                val (x, y) = Geo.fromNatural(Geo.screen(app), p.fx, p.fy)
                s.setTarget(true, x, y)
            } else {
                s.setTarget(false, 0f, 0f)
            }
        } catch (e: Throwable) {
            lastError = e.toString()
        }
    }
}
