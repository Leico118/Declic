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
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Le « moteur » : lancé par Déclic via le débogage sans fil, avec les droits de l'ADB (shell).
 *  - lit directement les événements bruts du noyau (/dev/input/eventX) -> aucune attente ;
 *  - mode « fusion » (par défaut) : dans les applis choisies, il prend la main sur l'écran tactile
 *    et renvoie tes vrais doigts + le doigt virtuel de la touche dans un seul flux multi-touch,
 *    pour que l'appui de la touche n'annule pas ton pouce ;
 *  - mode « simple » : doigt virtuel séparé (peut annuler les autres doigts).
 * Dialogue avec l'appli par lignes de texte (entrée/sortie standard).
 */
object Engine {

    private const val VIRTUAL_KEY = 100 // clé interne du doigt de la touche (son numéro Android est attribué librement)
    private const val MAX_SLOTS = 16
    private const val EVIOCGRAB = 0x40044590

    @Volatile private var enabled = false
    @Volatile private var tx = 0f
    @Volatile private var ty = 0f
    @Volatile private var trigDev = ""
    @Volatile private var trigType = -1
    @Volatile private var trigCode = -1
    @Volatile private var injectMode = 0
    @Volatile private var detecting = false
    @Volatile private var lastErr = ""
    @Volatile private var injectCount = 0
    @Volatile private var triggerCount = 0
    @Volatile private var curW = 1080
    @Volatile private var curH = 2400
    @Volatile private var rot = 0

    private val readers = HashMap<String, Reader>()
    private val lastSent = ConcurrentHashMap<String, Long>()
    private val readersLock = Any()
    private val stateLock = Any()
    private val out = System.out

    private val evSize = if (Process.is64Bit()) 24 else 16
    private var injector: Injector? = null

    // État du flux tactile envoyé au système
    private class Ptr(val key: Int, val id: Int, var x: Float, var y: Float)
    private val ptrs = ArrayList<Ptr>()
    private var downTime = 0L

    // Mode simple
    private var simplePressed = false
    private var simpleDown = 0L

    // Couche « fusion »
    @Volatile private var touchReader: Reader? = null
    @Volatile private var layerActive = false
    private var touchDevId = 0
    private var minX = 0
    private var maxX = 1
    private var minY = 0
    private var maxY = 1
    private var curSlot = 0
    private val slotTrack = IntArray(MAX_SLOTS) { -1 }
    private val slotX = IntArray(MAX_SLOTS)
    private val slotY = IntArray(MAX_SLOTS)
    private var virtualDown = false
    @Volatile private var layerInfo = "pas encore lancée"
    @Volatile private var grabErr = ""
    @Volatile private var keyGrabInfo = "touche non réservée"
    private val powerCache = HashMap<String, Boolean>()
    // Durée minimale d'un appui : certains boutons (Quick Button) envoient « enfoncé » et
    // « relâché » au même instant, trop vite pour que le jeu le voie.
    @Volatile private var minHoldMs = 70L
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private var rawDown = false
    private var rawAt = 0L
    private var pressGen = 0
    @Volatile private var lastDur = -1L
    @Volatile private var frames = 0
    @Volatile private var sent = 0
    @Volatile private var refused = 0

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
        emit("R")
        val br = System.`in`.bufferedReader()
        while (true) {
            val line = try {
                br.readLine()
            } catch (_: Throwable) {
                null
            } ?: break
            try {
                handle(line.trim())
            } catch (e: Throwable) {
                lastErr = "commande « $line » : $e"
            }
        }
        stopLayer()
        System.exit(0) // l'appli s'est fermée : on s'arrête aussi (et on rend l'écran au système)
    }

    private fun emit(s: String) {
        synchronized(out) {
            out.print(s.replace("\n", " ⏎ "))
            out.print('\n')
            out.flush()
        }
    }

    private fun handle(line: String) {
        if (line.isEmpty()) return
        when (line[0]) {
            'T' -> {
                val p = line.split(" ", limit = 4)
                trigType = p[1].toInt()
                trigCode = p[2].toInt()
                trigDev = p.getOrElse(3) { "" }
                updateReaders()
            }
            'G' -> if (!displayOk) {
                val p = line.split(" ")
                curW = p[1].toInt()
                curH = p[2].toInt()
                rot = p[3].toInt()
            }
            'P' -> {
                val p = line.split(" ")
                setTarget(p[1] == "1", p[2].toFloat(), p[3].toFloat())
            }
            'M' -> {
                val m = line.substring(2).trim().toInt()
                if (m != injectMode) {
                    stopLayer()
                    injectMode = m
                    if (enabled) startLayer()
                }
            }
            'D' -> {
                detecting = line.endsWith("1")
                lastSent.clear()
                updateReaders()
            }
            'H' -> minHoldMs = line.substring(2).trim().toLong()
            'Q' -> emit("S " + status())
        }
    }

    private fun status(): String {
        val n = synchronized(readersLock) { readers.size }
        val sb = StringBuilder("uid ${Process.myUid()} · $n périph. · touche ×$triggerCount · appuis ×$injectCount")
        if (enabled) sb.append(if (layerActive) " · prêt (fusion multi-touch)" else " · prêt (simple)")
        sb.append(" · ").append(keyGrabInfo)
        sb.append(" · dernier appui réel ${if (lastDur < 0) "—" else "$lastDur ms"} (min. $minHoldMs ms)")
        sb.append("\n[Fusion] ").append(layerInfo).append(" · écran ${curW}x$curH rot $rot")
        if (displayErr.isNotEmpty()) sb.append(" · ").append(displayErr)
            .append(" · trames écran ×$frames · envoyés ×$sent · refusés ×$refused")
        if (lastErr.isNotEmpty()) sb.append("\nDernière erreur : ").append(lastErr)
        return sb.toString()
    }

    private fun setTarget(en: Boolean, x: Float, y: Float) {
        synchronized(stateLock) {
            tx = x
            ty = y
        }
        if (en && !enabled) {
            enabled = true
            startLayer()
            updateReaders()
        } else if (!en && enabled) {
            enabled = false
            stopLayer()
            synchronized(stateLock) { if (simplePressed) simpleRelease() }
            updateReaders()
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

    /** Le périphérique contient-il le bouton marche/arrêt ? Dans ce cas on ne le réserve jamais. */
    private fun hasPowerKey(path: String): Boolean = powerCache.getOrPut(path) {
        val txt = getevent(path)
        val keys = Regex("KEY \\(0001\\):([\\s\\S]*?)(?:\\n\\s*[A-Z]{2,} \\(|$)").find(txt)?.groupValues?.get(1) ?: txt
        keys.contains(Regex("\\b0074\\b"))
    }

    private fun updateReaders() {
        synchronized(readersLock) {
            val wanted = listDevices().filter { (_, name) -> detecting || (trigDev.isNotEmpty() && name == trigDev) }.toMap()
            val grabKey = enabled && !detecting && trigType == 1
            val it = readers.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val wantGrab = grabKey && wanted[e.key] == trigDev
                if (!wanted.containsKey(e.key) || !e.value.isAlive || e.value.grab != wantGrab) {
                    e.value.shutdown()
                    it.remove()
                }
            }
            if (!grabKey) keyGrabInfo = "touche non réservée"
            for ((path, name) in wanted) {
                if (!readers.containsKey(path)) {
                    val wantGrab = grabKey && name == trigDev
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
        @Volatile var grabFailed = false

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
                            grabFailed = true
                            layerInfo = layerInfo.replace("prise en main…", "prise en main ÉCHEC ($grabErr) → mode simple")
                            onGrabFailed()
                            return
                        }
                        layerInfo = layerInfo.replace("prise en main…", "prise en main OK")
                    } else {
                        keyGrabInfo = if (ok) "touche réservée à Déclic ✓" else "réservation de la touche impossible ($grabErr)"
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
                    stream?.close() // fermer = rendre l'écran au système
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

    /** EVIOCGRAB : l'écran tactile n'envoie plus rien au système, seulement à Déclic. */
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

    private fun onRawEvent(dev: String, type: Int, code: Int, value: Int, grabbed: Boolean = false) {
        if (type == 0) return // EV_SYN
        // Composant réservé : on renvoie au système les autres boutons (marche/arrêt, volume…)
        if (grabbed && type == 1 && !(code == trigCode && trigType == 1)) forwardKey(code, value)
        if (type == trigType && code == trigCode && dev == trigDev) {
            if (value != 0) {
                triggerCount++
                if (!detecting) emit("K ${SystemClock.uptimeMillis()}")
            }
            handleTrigger(value != 0)
        }
        if (detecting) {
            if (type != 1) { // hors touches : on limite le débit
                val key = "$dev|$type|$code"
                val now = SystemClock.uptimeMillis()
                val last = lastSent[key] ?: 0L
                if (now - last < 250) return
                lastSent[key] = now
            }
            emit("E $type $code $value $dev")
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
        layerInfo = "écran « ${ts.second} » (${ts.first.substringAfterLast('/')}) X $minX..$maxX Y $minY..$maxY id $touchDevId · ${curW}x$curH rot $rot · prise en main…"
        synchronized(stateLock) {
            for (i in 0 until MAX_SLOTS) slotTrack[i] = -1
            curSlot = 0
            ptrs.clear()
            virtualDown = false
            layerActive = true
        }
        refreshDisplay()
        val r = Reader(ts.first, true) { t, c, v -> onTouch(t, c, v) }
        touchReader = r
        r.start()
        startDisplayPoll()
    }

    /** Lit la taille et la rotation réelles de l'écran (ne dépend plus de l'appli). */
    private fun refreshDisplay() {
        try {
            val dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val inst = dmg.getMethod("getInstance").invoke(null)
            val info = dmg.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(inst, 0) ?: return
            val c = info.javaClass
            val w = c.getField("logicalWidth").getInt(info)
            val hh = c.getField("logicalHeight").getInt(info)
            val r = c.getField("rotation").getInt(info)
            if (w > 0 && hh > 0) {
                displayOk = true
                curW = w
                curH = hh
                rot = r
            }
        } catch (e: Throwable) {
            displayErr = "lecture rotation : ${e.cause ?: e}"
        }
    }

    @Volatile private var displayErr = ""
    @Volatile private var displayOk = false

    private fun startDisplayPoll() {
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
    }

    private fun onGrabFailed() {
        synchronized(stateLock) {
            layerActive = false
            ptrs.clear()
        }
        touchReader = null
    }

    private fun onLayerDied() {
        synchronized(stateLock) {
            layerActive = false
            releaseAll()
        }
        touchReader = null
    }

    /** Choisit le vrai écran tactile : celui qui a la plus grande surface multi-touch. Remplit minX… */
    private var tsCache: Pair<String, String>? = null

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
            if (tsNames.containsKey(dev.second)) area *= 4 // priorité aux écrans connus d'Android
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
    private fun map(rx: Int, ry: Int): Pair<Float, Float> {
        val nw = (if (rot % 2 == 0) curW else curH).toFloat()
        val nh = (if (rot % 2 == 0) curH else curW).toFloat()
        val X = (rx - minX).toFloat() / (maxX - minX) * nw
        val Y = (ry - minY).toFloat() / (maxY - minY) * nh
        return when (rot) {
            1 -> Y to (nw - X)
            2 -> (nw - X) to (nh - Y)
            3 -> (nh - Y) to X
            else -> X to Y
        }
    }

    private fun onTouch(type: Int, code: Int, value: Int) {
        synchronized(stateLock) {
            if (!layerActive) return
            if (type == 3) {
                when (code) {
                    0x2f -> curSlot = value.coerceIn(0, MAX_SLOTS - 1) // ABS_MT_SLOT
                    0x39 -> slotTrack[curSlot] = if (value < 0) -1 else value // ABS_MT_TRACKING_ID
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

    /** Plus petit numéro de doigt libre, comme le fait Android (0, 1, 2…). */
    private fun freeId(): Int {
        var id = 0
        while (ptrs.any { it.id == id }) id++
        return id
    }

    private fun commitFrame() {
        var moved = false
        // 1) positions des doigts déjà posés
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
        // 2) doigts levés
        for (s in 0 until MAX_SLOTS) {
            if (slotTrack[s] < 0 && ptrIndex(s) >= 0) {
                removePointer(s)
                emitted = true
            }
        }
        // 3) nouveaux doigts
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
        virtualDown = false
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
            val ok = inj.inject(ev)
            ev.recycle()
            sent++
            if (!ok) {
                refused++
                lastErr = "appui refusé par le système (action $action)"
            }
        } catch (e: Throwable) {
            refused++
            lastErr = "appui : ${e.cause ?: e}"
        }
    }

    // ---------- La touche ----------

    private fun handleTrigger(down: Boolean) {
        synchronized(stateLock) {
            val now = SystemClock.uptimeMillis()
            if (down) {
                if (rawDown) return // répétition automatique : on ignore
                rawDown = true
                rawAt = now
                pressGen++
                if (!enabled) return
                if (isVirtualDown()) virtualUp() // relâchement encore en attente (double appui rapide)
                virtualDownNow()
            } else {
                if (!rawDown) return
                rawDown = false
                lastDur = now - rawAt
                if (!isVirtualDown()) return
                val wait = minHoldMs - lastDur
                if (wait > 0) {
                    val g = pressGen
                    sched.schedule({
                        synchronized(stateLock) { if (g == pressGen && !rawDown) virtualUp() }
                    }, wait, TimeUnit.MILLISECONDS)
                } else {
                    virtualUp()
                }
            }
        }
    }

    private val keyDownAt = HashMap<Int, Long>()

    /** Renvoie au système un bouton physique du composant réservé (code noyau -> touche Android). */
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
            val repeat = if (value == 2) 1 else 0
            val ev = KeyEvent(down, now, action, kc, repeat, 0, -1, code, 0, InputDevice.SOURCE_KEYBOARD)
            inj.inject(ev)
        } catch (e: Throwable) {
            lastErr = "renvoi bouton $code : ${e.cause ?: e}"
        }
    }

    private fun isVirtualDown() = if (layerActive) virtualDown else simplePressed

    private fun virtualDownNow() {
        if (layerActive) {
            virtualDown = true
            addPointer(VIRTUAL_KEY, tx, ty)
        } else {
            simplePressed = true
            simpleDown = SystemClock.uptimeMillis()
            simpleInject(MotionEvent.ACTION_DOWN)
        }
        injectCount++
    }

    private fun virtualUp() {
        if (layerActive) {
            if (virtualDown) {
                virtualDown = false
                removePointer(VIRTUAL_KEY)
            }
        } else if (simplePressed) {
            simpleRelease()
        }
    }

    private fun simpleRelease() {
        simplePressed = false
        simpleInject(MotionEvent.ACTION_UP)
    }

    private fun simpleInject(action: Int) {
        val inj = injector ?: return
        try {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = tx
                y = ty
                pressure = 1f
                size = 0.05f
            })
            val ev = MotionEvent.obtain(
                simpleDown, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f,
                7777, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            val ok = inj.inject(ev)
            ev.recycle()
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
