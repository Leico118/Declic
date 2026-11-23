package ch.leico.declic

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Une touche remappée. */
data class Bind(val id: Int, val name: String, val dev: String, val type: Int, val code: Int, val enabled: Boolean)

/** Une appli : pour chaque touche (id), la position (0..1) dans l'orientation naturelle (portrait). */
data class Profile(val pkg: String, val label: String, val pos: Map<Int, Pair<Float, Float>>)

object Prefs {
    private lateinit var sp: SharedPreferences
    @Volatile private var bindsCache: List<Bind>? = null
    @Volatile private var profCache: List<Profile>? = null

    fun init(c: Context) {
        if (::sp.isInitialized) return
        sp = c.getSharedPreferences("declic", Context.MODE_PRIVATE)
        migrate()
    }

    var active: Boolean
        get() = sp.getBoolean("active", true)
        set(v) = sp.edit().putBoolean("active", v).apply()

    var injectMode: Int
        get() = sp.getInt("injectMode", 0)
        set(v) = sp.edit().putInt("injectMode", v).apply()

    /** Ancienne version (une seule touche) -> nouvelle (plusieurs touches). */
    private fun migrate() {
        if (sp.contains("binds")) return
        val dev = sp.getString("trigDev", null)
        val list = ArrayList<Bind>()
        if (!dev.isNullOrEmpty()) list.add(Bind(1, "Touche ${sp.getInt("trigCode", 0)}", dev, sp.getInt("trigType", 1), sp.getInt("trigCode", 0), true))
        saveBinds(list)
        val profs = ArrayList<Profile>()
        try {
            val arr = JSONArray(sp.getString("profiles", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val fx = o.optDouble("fx", -1.0).toFloat()
                val fy = o.optDouble("fy", -1.0).toFloat()
                val pos = if (fx >= 0 && fy >= 0 && list.isNotEmpty()) mapOf(1 to (fx to fy)) else emptyMap()
                profs.add(Profile(o.getString("pkg"), o.optString("label", o.getString("pkg")), pos))
            }
        } catch (_: Exception) {
        }
        saveProfiles(profs)
    }

    // ---------- Touches ----------

    fun binds(): List<Bind> {
        bindsCache?.let { return it }
        val out = ArrayList<Bind>()
        try {
            val arr = JSONArray(sp.getString("binds", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Bind(o.getInt("id"), o.getString("name"), o.getString("dev"), o.getInt("type"), o.getInt("code"), o.optBoolean("enabled", true)))
            }
        } catch (_: Exception) {
        }
        bindsCache = out
        return out
    }

    private fun saveBinds(list: List<Bind>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(JSONObject().put("id", b.id).put("name", b.name).put("dev", b.dev).put("type", b.type).put("code", b.code).put("enabled", b.enabled))
        }
        sp.edit().putString("binds", arr.toString()).apply()
        bindsCache = list
    }

    fun addBind(name: String, dev: String, type: Int, code: Int): Bind {
        val id = (binds().maxOfOrNull { it.id } ?: 0) + 1
        val b = Bind(id, name, dev, type, code, true)
        saveBinds(binds() + b)
        return b
    }

    fun setBindEnabled(id: Int, enabled: Boolean) = saveBinds(binds().map { if (it.id == id) it.copy(enabled = enabled) else it })

    fun removeBind(id: Int) {
        saveBinds(binds().filter { it.id != id })
        saveProfiles(profiles().map { it.copy(pos = it.pos - id) })
    }

    // ---------- Applis ----------

    fun profiles(): List<Profile> {
        profCache?.let { return it }
        val out = ArrayList<Profile>()
        try {
            val arr = JSONArray(sp.getString("profiles2", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pos = HashMap<Int, Pair<Float, Float>>()
                val po = o.optJSONObject("pos")
                if (po != null) for (k in po.keys()) {
                    val a = po.getJSONArray(k)
                    pos[k.toInt()] = a.getDouble(0).toFloat() to a.getDouble(1).toFloat()
                }
                out.add(Profile(o.getString("pkg"), o.optString("label", o.getString("pkg")), pos))
            }
        } catch (_: Exception) {
        }
        profCache = out
        return out
    }

    private fun saveProfiles(list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) {
            val po = JSONObject()
            for ((k, v) in p.pos) po.put(k.toString(), JSONArray().put(v.first.toDouble()).put(v.second.toDouble()))
            arr.put(JSONObject().put("pkg", p.pkg).put("label", p.label).put("pos", po))
        }
        sp.edit().putString("profiles2", arr.toString()).apply()
        profCache = list.sortedBy { it.label.lowercase() }
    }

    fun profile(pkg: String): Profile? = profiles().firstOrNull { it.pkg == pkg }

    fun addApp(pkg: String, label: String) {
        if (profile(pkg) != null) return
        saveProfiles(profiles() + Profile(pkg, label, emptyMap()))
    }

    fun setPos(pkg: String, bindId: Int, fx: Float, fy: Float) {
        saveProfiles(profiles().map { if (it.pkg == pkg) it.copy(pos = it.pos + (bindId to (fx to fy))) else it })
    }

    fun removeApp(pkg: String) = saveProfiles(profiles().filter { it.pkg != pkg })

    /** Touches actives (cochées et placées) pour une appli. */
    fun activeFor(pkg: String): List<Pair<Bind, Pair<Float, Float>>> {
        val p = profile(pkg) ?: return emptyList()
        return binds().filter { it.enabled }.mapNotNull { b -> p.pos[b.id]?.let { b to it } }
    }
}
