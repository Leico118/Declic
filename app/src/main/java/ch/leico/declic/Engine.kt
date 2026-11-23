package ch.leico.declic

import android.hardware.input.InputManager
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileWriter
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Le « moteur » de Déclic. Lancé une fois via le débogage sans fil (droits ADB/shell), puis
 * détaché : il continue de tourner seul même quand le débogage sans fil est coupé.
 * Il lit ses réglages dans un fichier écrit par l'appli et y écrit son état.
 *  - lit directement les événements bruts du noyau (/dev/input/eventX) -> aucune attente ;
 *  - mode « fusion » : dans les applis choisies, prend la main sur l'écran tactile et renvoie
 *    tes vrais doigts + les doigts des touches dans un seul flux multi-touch ;
 *  - mode « simple » : doigt virtuel séparé (peut annuler les autres doigts).
 */
object Engine {

    private const val DIR = "/sdcard/Android/data/ch.leico.declic/files"
    private const val MAX_SLOTS = 16
    private const val EVIOCGRAB = 0x40044590

    private val cfgFile = File(DIR, "config.json")
    private val statusFile = File(DIR, "status.txt")
    private val eventsFile = File(DIR, "events.txt")

    /** Une touche active dans l'appli au premier plan. */
    private class Bind(val id: Int, val dev: String, val type: Int, val code: Int, val fx: Float, val fy: Float) {
        var rawDown = false
        var rawAt = 0L
        var gen = 0
        var vDown = false
        var sDown = 0L
        var x = 0f
        var y = 0f
        val key get() = 100 + id
    }

    @Volatile private var binds: List<Bind> = emptyList()
    @Volatile private var enabled = false
    @Volatile private var injectMode = 0
    @Volatile private var minHoldMs = 70L
    @Volatile private var detecting = false
    @Volatile private var lastErr = ""
    @Volatile private var injectCount = 0
    @Volatile private var triggerCount = 0
    @Volatile private var lastDur = -1L
    @Volatile private var curW = 1080
    @Volatile private var curH = 2400
    @Volatile private var rot = 0
    @Volatile private var displayErr = ""
    @Volatile private var keyGrabInfo = "—"
    @Volatile private var lastCfg = ""

    private val readers = HashMap<String, Reader>()
    private val lastSent = ConcurrentHashMap<String, Long>()
    private val readersLock = Any()
    private val stateLock = Any()
    private val evSize = if (Process.is64Bit()) 24 else 16
    private var injector: Injector? = null
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private var eventsOut: FileWriter? = null

    // Flux tactile envoyé au système
    private class Ptr(val key: Int, val id: Int, var x: Float, var y: Float)
    private val ptrs = ArrayList<Ptr>()
    private var downTime = 0L

    // Couche « fusion »
    @Volatile private var touchReader: Reader? = null
    @Volatile private var layerActive = false
    @Volatile private var layerInfo = "inactive"
    @Volatile private var grabErr = ""
    @Volatile private var frames = 0
    @Volatile private var sent = 0
    private var touchDevId = 0
    private var tsCache: Pair<String, String>? = null
    private var minX = 0
    private var maxX = 1
    private var minY = 0
    private var maxY = 1
    private var curSlot = 0
    private val slotTrack = IntArray(MAX_SLOTS) { -1 }
    private val slotX = IntArray(MAX_SLOTS)
    private val slotY = IntArray(MAX_SLOTS)

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            Looper.prepareMainLooper()
        } catch (_: Throwable) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("")
        } catch (_: Throwable) {
        }
        injector = try {
            Injector()
        } catch (e: Throwable) {
            lastErr = "injecteur : $e"
            null
        }
        refreshDisplay()
        // État écrit chaque seconde (sert aussi de « battement de cœur » pour l'appli)
        Thread({
            while (true) {
                writeStatus()
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                }
            }
        }, "declic-etat").apply { isDaemon = true }.start()
        // Lecture des réglages
        while (true) {
            try {
                val txt = if (cfgFile.exists()) cfgFile.readText() else ""
                if (txt.isNotEmpty() && txt != lastCfg) {
                    lastCfg = txt
                    applyConfig(JSONObject(txt))
                }
            } catch (e: Throwable) {
                lastErr = "réglages : $e"
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun quit() {
        stopLayer()
        synchronized(readersLock) {
            for (r in readers.values) r.shutdown()
            readers.clear()
        }
        try {
            statusFile.writeText("v=0 t=0\nMoteur arrêté")
        } catch (_: Throwable) {
        }
        System.exit(0)
    }

    private fun applyConfig(c: JSONObject) {
        if (c.optBoolean("stop", false)) quit()
        if (c.optInt("v", -1) != BuildConfig.VERSION_CODE) quit() // appli mise à jour : on laisse la place au nouveau moteur
        val m = c.optInt("mode", 0)
        minHoldMs = c.optLong("minHold", 70)
        val det = c.optBoolean("detect", false)
        if (det && !detecting) {
            try {
                eventsOut?.close()
                eventsOut = FileWriter(eventsFile, false)
            } catch (e: Throwable) {
                lastErr = "fichier de détection : $e"
            }
            lastSent.clear()
        }
        detecting = det
        val list = ArrayList<Bind>()
        val arr = c.optJSONArray("binds")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Bind(o.getInt("id"), o.getString("dev"), o.getInt("type"), o.getInt("code"), o.getDouble("fx").toFloat(), o.getDouble("fy").toFloat()))
        }
        synchronized(stateLock) {
            // on garde l'état des touches déjà enfoncées
            for (b in list) binds.firstOrNull { it.id == b.id }?.let { old ->
                b.rawDown = old.rawDown; b.rawAt = old.rawAt; b.gen = old.gen; b.vDown = old.vDown; b.sDown = old.sDown; b.x = old.x; b.y = old.y
            }
            for (old in binds) if (list.none { it.id == old.id } && old.vDown) virtualUp(old)
            binds = list
        }
        val en = list.isNotEmpty()
        if (m != injectMode) {
            stopLayer()
            injectMode = m
        }
        if (en && !enabled) {
            enabled = true
            startLayer()
        } else if (!en && enabled) {
            enabled = false
            stopLayer()
        } else if (en && touchReader == null) {
            startLayer()
        }
        updateReaders()
    }

    private fun writeStatus() {
        try {
            val n = synchronized(readersLock) { readers.size }
            val sb = StringBuilder("v=${BuildConfig.VERSION_CODE} t=${System.currentTimeMillis()}\n")
            sb.append("✓ Moteur actif (uid ${Process.myUid()}) · $n périph. · touches ×$triggerCount · appuis ×$injectCount")
            sb.append(if (enabled) (if (layerActive) " · prêt (fusion)" else " · prêt (simple)") else " · en veille")
            sb.append("\n").append(keyGrabInfo).append(" · dernier appui réel ${if (lastDur < 0) "—" else "$lastDur ms"}")
            sb.append("\n[Fusion] ").append(layerInfo).append(" · écran ${curW}x$curH rot $rot · trames ×$frames · envoyés ×$sent")
            if (displayErr.isNotEmpty()) sb.append("\n").append(displayErr)
            if (lastErr.isNotEmpty()) sb.append("\nDernière erreur : ").append(lastErr)
            val tmp = File(DIR, "status.tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(statusFile)) statusFile.writeText(sb.toString())
        } catch (_: Throwable) {
        }
    }

    private fun emitEvent(dev: String, type: Int, code: Int, value: Int) {
        try {
            val w = eventsOut ?: return
            synchronized(w) {
                w.write("$type $code $value $dev\n")
                w.flush()
            }
        } catch (_: Throwable) {
        }
    }

    // ---------- Écran (taille, rotation) ----------

    private fun refreshDisplay() {
        try {
            val dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val inst = dmg.getMethod("getInstance").invoke(null)
            val info = dmg.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(inst, 0) ?: return
            val c = info.javaClass
            val w = c.getField("logicalWidth").getInt(info)
            val h = c.getField("logicalHeight").getInt(info)
            val r = c.getField("rotation").getInt(info)
            if (w > 0 && h > 0) {
                curW = w
                curH = h
                rot = r
            }
        } catch (e: Throwable) {
            displayErr = "lecture rotation : ${e.cause ?: e}"
        }
    }

    /** Fractions dans l'orientation naturelle -> point de l'écran actuel. */
    private fun fromNatural(fx: Float, fy: Float): Pair<Float, Float> {
        val nw = (if (rot % 2 == 0) curW else curH).toFloat()
        val nh = (if (rot % 2 == 0) curH else curW).toFloat()
        val X = fx * nw
        val Y = fy * nh
        return when (rot) {
            1 -> Y to (nw - X)
            2 -> (nw - X) to (nh - Y)
            3 -> (nh - Y) to X
            else -> X to Y
        }
    }

    // ---------- Lecture des périphériques ----------

    private fun listDevices(): List<Pair<String, String>> {
        val files = File("/dev/input").listFiles(java.io.FileFilter { f -> f.name.startsWith("event") })
        if (files == null) {
            lastErr = "impossible de lister /dev/input"
            return emptyList()
        }
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
        synchronized(readersLock) {
            val active = binds
            val trigDevs = active.map { it.dev }.toSet()
            val wanted = listDevices().filter { (_, name) -> detecting || name in trigDevs }.toMap()
            val grabOk = enabled && !detecting
            val it = readers.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val wantGrab = grabOk && wanted[e.key]?.let { it in trigDevs } == true
                if (!wanted.containsKey(e.key) || !e.value.isAlive || e.value.grab != wantGrab) {
                    e.value.shutdown()
                    it.remove()
                }
            }
            if (!grabOk) keyGrabInfo = "touches non réservées"
            for ((path, name) in wanted) {
                if (!readers.containsKey(path)) {
                    val wantGrab = grabOk && name in trigDevs
                    val r = Reader(path, wantGrab, false) { t, c, v -> onRawEvent(name, t, c, v, wantGrab) }
                    readers[path] = r
                    r.start()
                }
            }
        }
    }

    private class Reader(
        val path: String,
        val grab: Boolean,
        val required: Boolean = true,
        val handler: (Int, Int, Int) -> Unit,
    ) : Thread("declic-${path.substringAfterLast('/')}") {
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
                if (grab) {
                    val ok = grabDevice(s.fd)
                    if (required) {
                        if (!ok) {
                            layerInfo = "prise en main ÉCHEC ($grabErr) → mode simple"
                            onLayerDied()
                            return
                        }
                        layerInfo = layerInfo.replace("prise en main…", "prise en main OK")
                    } else {
                        keyGrabInfo = if (ok) "touches réservées à Déclic ✓" else "réservation des touches impossible ($grabErr)"
                    }
                }
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
                        handler(type, code, value)
                        off += evSize
                    }
                    pending = total - off
                    if (pending > 0) System.arraycopy(buf, off, buf, 0, pending)
                }
            } catch (e: Throwable) {
                if (!stopped) lastErr = "lecture $path : ${e.message}"
            } finally {
                try {
                    stream?.close() // fermer = rendre le périphérique au système
                } catch (_: Throwable) {
                }
                if (grab && required && !stopped) onLayerDied()
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

    /** EVIOCGRAB : le périphérique n'envoie plus rien au système, seulement à Déclic. */
    private fun grabDevice(fd: FileDescriptor): Boolean {
        val candidates = ArrayList<Pair<Any?, Method>>()
        try {
            Class.forName("android.system.Os").declaredMethods.filter { it.name == "ioctlInt" }.forEach { candidates.add(null to it) }
        } catch (_: Throwable) {
        }
        try {
            val lc = Class.forName("libcore.io.Libcore")
            val os = lc.getDeclaredField("os").apply { isAccessible = true }.get(null)
            os.javaClass.methods.filter { it.name == "ioctlInt" }.forEach { candidates.add(os to it) }
        } catch (_: Throwable) {
        }
        var err = "aucune méthode ioctl"
        for ((target, m) in candidates) {
            try {
                m.isAccessible = true
                val pt = m.parameterTypes
                when (pt.size) {
                    2 -> m.invoke(target, fd, EVIOCGRAB)
                    3 -> {
                        val ref = if (pt[2] == Int::class.javaPrimitiveType) 1
                        else pt[2].getConstructor(Int::class.javaPrimitiveType).newInstance(1)
                        m.invoke(target, fd, EVIOCGRAB, ref)
                    }
                    else -> continue
                }
                return true
            } catch (e: Throwable) {
                err = (e.cause ?: e).toString()
            }
        }
        grabErr = err
        return false
    }

    private fun onRawEvent(dev: String, type: Int, code: Int, value: Int, grabbed: Boolean) {
        if (type == 0) return // EV_SYN
        var matched = false
        for (b in binds) {
            if (b.type == type && b.code == code && b.dev == dev) {
                matched = true
                if (value != 0) triggerCount++
                handleTrigger(b, value != 0)
            }
        }
        // Composant réservé : on renvoie au système les autres boutons (marche/arrêt, volume…)
        if (grabbed && type == 1 && !matched) forwardKey(code, value)
        if (detecting) {
            if (type != 1) { // hors touches : on limite le débit
                val key = "$dev|$type|$code"
                val now = SystemClock.uptimeMillis()
                val last = lastSent[key] ?: 0L
                if (now - last < 250) return
                lastSent[key] = now
            }
            emitEvent(dev, type, code, value)
        }
    }

    private val keyDownAt = HashMap<Int, Long>()

    private fun forwardKey(code: Int, value: Int) {
        val kc = when (code) {
            116 -> KeyEvent.KEYCODE_POWER
            115 -> KeyEvent.KEYCODE_VOLUME_UP
            114 -> KeyEvent.KEYCODE_VOLUME_DOWN
            113 -> KeyEvent.KEYCODE_VOLUME_MUTE
            212 -> KeyEvent.KEYCODE_CAMERA
            528 -> KeyEvent.KEYCODE_FOCUS
            else -> return
        }
        val inj = injector ?: return
        try {
            val now = SystemClock.uptimeMillis()
            val action = if (value == 0) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN
            if (value == 1) keyDownAt[code] = now
            val down = keyDownAt[code] ?: now
            val ev = KeyEvent(down, now, action, kc, if (value == 2) 1 else 0, 0, -1, code, 0, InputDevice.SOURCE_KEYBOARD)
            inj.inject(ev)
        } catch (e: Throwable) {
            lastErr = "renvoi bouton $code : ${e.cause ?: e}"
        }
    }

    // ---------- Couche « fusion multi-touch » ----------

    private fun startLayer() {
        if (injectMode != 0 || touchReader != null) return
        val ts = findTouchscreen()
        if (ts == null) {
            layerInfo = "écran tactile introuvable → mode simple"
            return
        }
        layerInfo = "écran ${ts.first.substringAfterLast('/')} · prise en main…"
        synchronized(stateLock) {
            for (i in 0 until MAX_SLOTS) slotTrack[i] = -1
            curSlot = 0
            ptrs.clear()
            for (b in binds) b.vDown = false
            layerActive = true
        }
        refreshDisplay()
        val r = Reader(ts.first, true) { t, c, v -> onTouch(t, c, v) }
        touchReader = r
        r.start()
        Thread({
            while (layerActive) {
                refreshDisplay()
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "declic-ecran").apply { isDaemon = true }.start()
    }

    private fun stopLayer() {
        val r = touchReader ?: return
        touchReader = null
        synchronized(stateLock) {
            layerActive = false
            releaseAll()
        }
        r.shutdown()
        layerInfo = "inactive"
    }

    private fun onLayerDied() {
        synchronized(stateLock) {
            layerActive = false
            releaseAll()
        }
        touchReader = null
    }

    private fun findTouchscreen(): Pair<String, String>? {
        tsCache?.let { return it }
        val tsNames = HashMap<String, Int>()
        try {
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id) ?: continue
                if (d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) && !d.isVirtual) tsNames[d.name] = id
            }
        } catch (_: Throwable) {
        }
        var best: Pair<String, String>? = null
        var bestArea = -1L
        var bx = intArrayOf(0, 1, 0, 1)
        for (dev in listDevices()) {
            val r = ranges(dev.first) ?: continue
            var area = (r[1] - r[0]).toLong() * (r[3] - r[2]).toLong()
            if (tsNames.containsKey(dev.second)) area *= 4
            if (area > bestArea) {
                bestArea = area
                best = dev
                bx = r
            }
        }
        val b = best ?: return null
        minX = bx[0]; maxX = bx[1]; minY = bx[2]; maxY = bx[3]
        touchDevId = tsNames[b.second] ?: tsNames.values.firstOrNull() ?: 0
        tsCache = b
        return b
    }

    private fun getevent(path: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("getevent", "-p", path))
        val t = p.inputStream.bufferedReader().readText()
        p.waitFor()
        t
    } catch (_: Throwable) {
        ""
    }

    private fun ranges(path: String): IntArray? {
        val txt = getevent(path)
        fun range(code: String): Pair<Int, Int>? {
            val m = Regex("\\b$code\\s*:\\s*value\\s*-?\\d+,\\s*min\\s*(-?\\d+),\\s*max\\s*(-?\\d+)").find(txt) ?: return null
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        val rx = range("0035") ?: return null
        val ry = range("0036") ?: return null
        if (rx.second <= rx.first || ry.second <= ry.first) return null
        return intArrayOf(rx.first, rx.second, ry.first, ry.second)
    }

    /** Position brute de l'écran tactile -> coordonnées de l'écran dans l'orientation actuelle. */
    private fun map(rx: Int, ry: Int): Pair<Float, Float> =
        fromNatural((rx - minX).toFloat() / (maxX - minX), (ry - minY).toFloat() / (maxY - minY))

    private fun onTouch(type: Int, code: Int, value: Int) {
        synchronized(stateLock) {
            if (!layerActive) return
            if (type == 3) {
                when (code) {
                    0x2f -> curSlot = value.coerceIn(0, MAX_SLOTS - 1)
                    0x39 -> slotTrack[curSlot] = if (value < 0) -1 else value
                    0x35 -> {
                        slotX[curSlot] = value
                        if (slotTrack[curSlot] < 0 && ptrIndex(curSlot) < 0) slotTrack[curSlot] = 0
                    }
                    0x36 -> slotY[curSlot] = value
                }
            } else if (type == 0 && code == 0) {
                frames++
                commitFrame()
            }
        }
    }

    private fun ptrIndex(key: Int): Int {
        for (i in ptrs.indices) if (ptrs[i].key == key) return i
        return -1
    }

    private fun freeId(): Int {
        var id = 0
        while (ptrs.any { it.id == id }) id++
        return id
    }

    private fun commitFrame() {
        var moved = false
        for (s in 0 until MAX_SLOTS) {
            if (slotTrack[s] < 0) continue
            val i = ptrIndex(s)
            if (i >= 0) {
                val (x, y) = map(slotX[s], slotY[s])
                val p = ptrs[i]
                if (p.x != x || p.y != y) {
                    p.x = x
                    p.y = y
                    moved = true
                }
            }
        }
        var emitted = false
        for (s in 0 until MAX_SLOTS) {
            if (slotTrack[s] < 0 && ptrIndex(s) >= 0) {
                removePointer(s)
                emitted = true
            }
        }
        for (s in 0 until MAX_SLOTS) {
            if (slotTrack[s] >= 0 && ptrIndex(s) < 0) {
                val (x, y) = map(slotX[s], slotY[s])
                addPointer(s, x, y)
                emitted = true
            }
        }
        if (moved && !emitted && ptrs.isNotEmpty()) send(MotionEvent.ACTION_MOVE)
    }

    private fun addPointer(key: Int, x: Float, y: Float) {
        ptrs.add(Ptr(key, freeId(), x, y))
        if (ptrs.size == 1) {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN)
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN or ((ptrs.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
    }

    private fun removePointer(key: Int) {
        val i = ptrIndex(key)
        if (i < 0) return
        if (ptrs.size == 1) send(MotionEvent.ACTION_UP)
        else send(MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        ptrs.removeAt(i)
    }

    private fun releaseAll() {
        while (ptrs.isNotEmpty()) removePointer(ptrs.last().key)
        for (b in binds) b.vDown = false
    }

    private fun send(action: Int) {
        val inj = injector ?: return
        try {
            val n = ptrs.size
            val props = Array(n) { i ->
                MotionEvent.PointerProperties().apply {
                    id = ptrs[i].id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(n) { i ->
                MotionEvent.PointerCoords().apply {
                    x = ptrs[i].x
                    y = ptrs[i].y
                    pressure = 1f
                    size = 0.05f
                }
            }
            val ev = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, n, props, coords, 0, 0, 1f, 1f,
                touchDevId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            inj.inject(ev)
            ev.recycle()
            sent++
        } catch (e: Throwable) {
            lastErr = "appui : ${e.cause ?: e}"
        }
    }

    // ---------- Les touches ----------

    private fun handleTrigger(b: Bind, down: Boolean) {
        synchronized(stateLock) {
            val now = SystemClock.uptimeMillis()
            if (down) {
                if (b.rawDown) return // répétition automatique
                b.rawDown = true
                b.rawAt = now
                b.gen++
                if (!enabled) return
                if (b.vDown) virtualUp(b) // relâchement encore en attente (appuis rapprochés)
                virtualDown(b)
            } else {
                if (!b.rawDown) return
                b.rawDown = false
                lastDur = now - b.rawAt
                if (!b.vDown) return
                val wait = minHoldMs - lastDur
                if (wait > 0) {
                    val g = b.gen
                    sched.schedule({
                        synchronized(stateLock) { if (g == b.gen && !b.rawDown) virtualUp(b) }
                    }, wait, TimeUnit.MILLISECONDS)
                } else {
                    virtualUp(b)
                }
            }
        }
    }

    private fun virtualDown(b: Bind) {
        if (!layerActive) refreshDisplay()
        val (x, y) = fromNatural(b.fx, b.fy)
        b.x = x
        b.y = y
        b.vDown = true
        if (layerActive) {
            addPointer(b.key, x, y)
        } else {
            b.sDown = SystemClock.uptimeMillis()
            simpleInject(b, MotionEvent.ACTION_DOWN)
        }
        injectCount++
    }

    private fun virtualUp(b: Bind) {
        if (!b.vDown) return
        b.vDown = false
        if (layerActive) removePointer(b.key) else simpleInject(b, MotionEvent.ACTION_UP)
    }

    private fun simpleInject(b: Bind, action: Int) {
        val inj = injector ?: return
        try {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = b.x
                y = b.y
                pressure = 1f
                size = 0.05f
            })
            val ev = MotionEvent.obtain(
                b.sDown, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f,
                7777 + b.id, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            inj.inject(ev)
            ev.recycle()
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
