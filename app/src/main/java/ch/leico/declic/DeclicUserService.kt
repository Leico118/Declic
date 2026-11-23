package ch.leico.declic

import android.hardware.input.InputManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Le « moteur » : tourne via Shizuku avec les droits de l'ADB (utilisateur shell).
 *  - lit directement les événements bruts du noyau (/dev/input/eventX) -> aucune attente ;
 *  - simule l'appui sur l'écran avec InputManager (mode asynchrone) -> quelques millisecondes.
 * Touche enfoncée = doigt posé, touche relâchée = doigt levé (on peut donc maintenir).
 */
class DeclicUserService : IDeclicService.Stub() {

    init {
        try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("")
        } catch (_: Throwable) {
        }
    }

    @Volatile private var enabled = false
    @Volatile private var tx = 0f
    @Volatile private var ty = 0f
    @Volatile private var trigDev = ""
    @Volatile private var trigType = -1
    @Volatile private var trigCode = -1
    @Volatile private var injectMode = 0
    @Volatile private var callback: IEventCallback? = null
    @Volatile private var detecting = false
    @Volatile private var lastErr = ""
    @Volatile private var injectCount = 0
    @Volatile private var triggerCount = 0

    private val readers = HashMap<String, Reader>()
    private val lastSent = ConcurrentHashMap<String, Long>()
    private val lock = Any()

    private var pressed = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var touchDeviceId = -2

    private val evSize = if (Process.is64Bit()) 24 else 16

    private val injector: Injector? by lazy {
        try {
            Injector()
        } catch (e: Throwable) {
            lastErr = "injecteur : $e"
            null
        }
    }

    // ---------- Interface ----------

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun ping(): String {
        val n = synchronized(lock) { readers.size }
        val sb = StringBuilder("Actif (uid ${Process.myUid()}) · $n périph. écouté(s) · touche ×$triggerCount · appuis simulés ×$injectCount")
        if (enabled) sb.append(" · prêt dans cette appli")
        if (lastErr.isNotEmpty()) sb.append("\nDernière erreur : ").append(lastErr)
        return sb.toString()
    }

    override fun setTrigger(deviceName: String?, type: Int, code: Int) {
        trigDev = deviceName ?: ""
        trigType = type
        trigCode = code
        updateReaders()
    }

    override fun setTarget(en: Boolean, x: Float, y: Float) {
        synchronized(this) {
            tx = x
            ty = y
            enabled = en
            if (!en && pressed) release()
        }
        injector // prépare l'injecteur à l'avance pour que le premier appui soit instantané
    }

    override fun setInjectMode(mode: Int) {
        injectMode = mode
    }

    override fun startDetection(cb: IEventCallback?) {
        callback = cb
        detecting = true
        lastSent.clear()
        updateReaders()
    }

    override fun stopDetection() {
        detecting = false
        callback = null
        updateReaders()
    }

    // ---------- Lecture des périphériques ----------

    private fun listDevices(): List<Pair<String, String>> {
        val files = File("/dev/input").listFiles(java.io.FileFilter { f -> f.name.startsWith("event") }) ?: return emptyList()
        return files.map { f ->
            val name = try {
                File("/sys/class/input/${f.name}/device/name").readText().trim()
            } catch (_: Throwable) {
                f.name
            }
            f.absolutePath to name
        }
    }

    private fun updateReaders() {
        synchronized(lock) {
            val wanted = listDevices().filter { (_, name) -> detecting || (trigDev.isNotEmpty() && name == trigDev) }.toMap()
            val it = readers.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (!wanted.containsKey(e.key) || !e.value.isAlive) {
                    e.value.shutdown()
                    it.remove()
                }
            }
            for ((path, name) in wanted) {
                if (!readers.containsKey(path)) {
                    val r = Reader(path, name)
                    readers[path] = r
                    r.start()
                }
            }
        }
    }

    private inner class Reader(val path: String, val name: String) : Thread("declic-${path.substringAfterLast('/')}") {
        @Volatile var stopped = false
        @Volatile var stream: FileInputStream? = null

        override fun run() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {
            }
            val buf = ByteArray(evSize * 64)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder())
            try {
                val s = FileInputStream(path)
                stream = s
                var pending = 0
                while (!stopped) {
                    val n = s.read(buf, pending, buf.size - pending)
                    if (n <= 0) break
                    val total = pending + n
                    var off = 0
                    while (total - off >= evSize) {
                        val type = bb.getShort(off + evSize - 8).toInt() and 0xffff
                        val code = bb.getShort(off + evSize - 6).toInt() and 0xffff
                        val value = bb.getInt(off + evSize - 4)
                        onRawEvent(name, type, code, value)
                        off += evSize
                    }
                    pending = total - off
                    if (pending > 0) System.arraycopy(buf, off, buf, 0, pending)
                }
            } catch (e: Throwable) {
                if (!stopped) lastErr = "lecture $path : ${e.message}"
            } finally {
                try {
                    stream?.close()
                } catch (_: Throwable) {
                }
            }
        }

        fun shutdown() {
            stopped = true
            try {
                stream?.close()
            } catch (_: Throwable) {
            }
            interrupt()
        }
    }

    private fun onRawEvent(dev: String, type: Int, code: Int, value: Int) {
        if (type == 0) return // EV_SYN
        if (type == trigType && code == trigCode && dev == trigDev) {
            if (value != 0) triggerCount++
            handleTrigger(value != 0)
        }
        if (detecting) {
            val cb = callback ?: return
            if (type != 1) { // hors touches : on limite le débit
                val key = "$dev|$type|$code"
                val now = SystemClock.uptimeMillis()
                val last = lastSent[key] ?: 0L
                if (now - last < 250) return
                lastSent[key] = now
            }
            try {
                cb.onEvent(dev, type, code, value)
            } catch (_: Throwable) {
                callback = null
            }
        }
    }

    // ---------- Simulation de l'appui ----------

    @Synchronized
    private fun handleTrigger(down: Boolean) {
        if (down == pressed) return
        if (down) {
            if (!enabled) return
            pressed = true
            downTime = SystemClock.uptimeMillis()
            downX = tx
            downY = ty
            inject(MotionEvent.ACTION_DOWN, downTime)
        } else {
            release()
        }
    }

    private fun release() {
        pressed = false
        inject(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
    }

    private fun deviceId(): Int {
        if (injectMode == 0) return 7777 // doigt virtuel séparé
        if (touchDeviceId == -2) {
            touchDeviceId = 0
            try {
                for (id in InputDevice.getDeviceIds()) {
                    val d = InputDevice.getDevice(id) ?: continue
                    if (d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                        touchDeviceId = id
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return touchDeviceId
    }

    private fun inject(action: Int, time: Long) {
        val inj = injector ?: return
        try {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = downX
                y = downY
                pressure = 1f
                size = 0.05f
            })
            val ev = MotionEvent.obtain(
                downTime, time, action, 1, props, coords, 0, 0, 1f, 1f,
                deviceId(), 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            val ok = inj.inject(ev)
            ev.recycle()
            injectCount++
            if (!ok) lastErr = "appui refusé par le système"
        } catch (e: Throwable) {
            lastErr = "appui : ${e.cause ?: e}"
        }
    }

    private class Injector {
        private val target: Any
        private val method: Method

        init {
            var t: Any? = null
            var m: Method? = null
            try {
                val c = Class.forName("android.hardware.input.InputManagerGlobal")
                t = c.getMethod("getInstance").invoke(null)
                m = c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            } catch (_: Throwable) {
            }
            if (t == null || m == null) {
                val c = InputManager::class.java
                t = c.getMethod("getInstance").invoke(null)
                m = c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            }
            target = t!!
            method = m!!
        }

        /** Mode 0 = asynchrone : on n'attend pas la réponse de l'appli -> latence minimale. */
        fun inject(e: InputEvent): Boolean = method.invoke(target, e, 0) as Boolean
    }
}
