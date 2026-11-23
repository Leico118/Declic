package ch.leico.declic

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Une appli où la touche est active. fx/fy = position (0..1) dans l'orientation naturelle (portrait). */
data class Profile(val pkg: String, val label: String, val fx: Float, val fy: Float) {
    val hasPos: Boolean get() = fx >= 0f && fy >= 0f
}

object Prefs {
    private lateinit var sp: SharedPreferences
    @Volatile private var cache: List<Profile>? = null

    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.getSharedPreferences("declic", Context.MODE_PRIVATE)
    }

    var active: Boolean
        get() = sp.getBoolean("active", true)
        set(v) = sp.edit().putBoolean("active", v).apply()

    var trigDevice: String?
        get() = sp.getString("trigDev", null)
        set(v) = sp.edit().putString("trigDev", v).apply()

    var trigType: Int
        get() = sp.getInt("trigType", -1)
        set(v) = sp.edit().putInt("trigType", v).apply()

    var trigCode: Int
        get() = sp.getInt("trigCode", -1)
        set(v) = sp.edit().putInt("trigCode", v).apply()

    var injectMode: Int
        get() = sp.getInt("injectMode", 0)
        set(v) = sp.edit().putInt("injectMode", v).apply()

    fun profiles(): List<Profile> {
        cache?.let { return it }
        val out = ArrayList<Profile>()
        try {
            val arr = JSONArray(sp.getString("profiles", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Profile(
                        o.getString("pkg"), o.optString("label", o.getString("pkg")),
                        o.optDouble("fx", -1.0).toFloat(), o.optDouble("fy", -1.0).toFloat()
                    )
                )
            }
        } catch (_: Exception) {
        }
        cache = out
        return out
    }

    private fun save(list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().put("pkg", p.pkg).put("label", p.label).put("fx", p.fx.toDouble()).put("fy", p.fy.toDouble()))
        }
        sp.edit().putString("profiles", arr.toString()).apply()
        cache = list
    }

    fun profile(pkg: String): Profile? = profiles().firstOrNull { it.pkg == pkg }

    fun upsert(p: Profile) {
        val l = profiles().filter { it.pkg != p.pkg }.toMutableList()
        l.add(p)
        save(l.sortedBy { it.label.lowercase() })
    }

    fun remove(pkg: String) = save(profiles().filter { it.pkg != pkg })
}
