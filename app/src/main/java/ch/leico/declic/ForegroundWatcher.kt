package ch.leico.declic

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Service d'accessibilité : repère l'appli au premier plan et affiche le cercle de placement. */
class ForegroundWatcher : AccessibilityService() {

    companion object {
        @Volatile var instance: ForegroundWatcher? = null
    }

    private var picker: Picker? = null
    private val activityCache = HashMap<String, Boolean>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Core.init(this)
        Core.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: return
        if (!isActivity(pkg, cls)) return // ignore volets, claviers, bulles…
        Core.onForeground(pkg)
    }

    private fun isActivity(pkg: String, cls: String): Boolean =
        activityCache.getOrPut("$pkg/$cls") {
            try {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (_: Exception) {
                false
            }
        }

    fun showPicker(pkg: String) {
        picker?.close()
        picker = Picker(this, pkg) { picker = null }.also { it.show() }
    }

    /** Bloque l'action d'origine de la touche (ex. volume) dans les applis où elle est remappée. */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val e = event ?: return false
        return Core.mappingActive && Prefs.trigType == 1 && e.scanCode == Prefs.trigCode
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        picker?.close()
        instance = null
        super.onDestroy()
    }
}
