package ai.nisu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.76: SYSTEM ACCESS. One switch per tool / connection of the AI brain, plus a MASTER switch.
 * A tool that is off is not offered to the model at all and is refused again when it is called (Tools.run), in every mode
 * (Extra Fast Beta, Balanced, DEFAULT and CUSTOM rules). MASTER off = every tool is off, she can only talk; the single switches keep
 * their state, so MASTER on brings back exactly what was on before. Memory notes can be switched off too.
 */
object Access {
    private const val KEY = "access_json"
    class State(var master: Boolean, val off: MutableSet<String>, var memory: Boolean)
    @Volatile private var cache: State? = null

    private fun parse(raw: String): State? {
        if (raw.isBlank()) return null
        val j = JSONObject(raw)
        val off = HashSet<String>()
        j.optJSONArray("off")?.let { a -> for (i in 0 until a.length()) off.add(a.optString(i)) }
        return State(j.optBoolean("master", true), off, j.optBoolean("memory", true))
    }

    fun load(c: Context): State {
        cache?.let { return it }
        val st = (try { parse(Prefs.get(c, KEY, "")) } catch (_: Exception) { null }) ?: State(true, HashSet(), true)
        cache = st
        return st
    }

    fun save(c: Context, master: Boolean, off: Set<String>, memory: Boolean) {
        val arr = JSONArray()
        for (t in off.sorted()) arr.put(t)
        val json = JSONObject().put("master", master).put("off", arr).put("memory", memory).toString()
        Prefs.put(c, KEY, json)
        cache = try { parse(json) } catch (_: Exception) { null }
        RuleSet.stamp = System.currentTimeMillis()   // the voice service starts its next session clean, with the new tool list
    }

    fun allowed(c: Context, name: String): Boolean { val st = load(c); return st.master && name !in st.off }
    fun memoryOn(c: Context): Boolean { val st = load(c); return st.master && st.memory }

    fun filter(c: Context, all: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            if (allowed(c, o.optString("name"))) out.put(o)
        }
        return out
    }

    /** A line for the prompt, so she says plainly what she cannot do instead of guessing. Empty while everything is on. */
    fun note(c: Context): String {
        val st = load(c)
        if (!st.master) return "\n\nSYSTEM ACCESS: the owner switched ALL your tools and connections off. You can only talk. Do not pretend to use any tool; if he asks for an action, say in one short sentence that it is switched off."
        if (st.off.isEmpty()) return ""
        return "\n\nSYSTEM ACCESS: the owner switched these tools off, you cannot use them: " + st.off.sorted().joinToString(", ") + ". If he asks for one of them, say in one short sentence that it is switched off."
    }

    /** Every tool the brain can call, grouped for the System Access page: (group label, list of tool name to short description). */
    fun catalog(): List<Pair<String, List<Pair<String, String>>>> {
        val arr = Tools.declarations(LiveModels.ToolMode.BLOCKING)
        val all = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name")
            if (n.isNotBlank()) all[n] = o.optString("description").substringBefore(". ").take(90)
        }
        val used = HashSet<String>()
        val out = ArrayList<Pair<String, List<Pair<String, String>>>>()
        for (g in RuleSet.GROUPS) {
            if (g.id == "other") continue
            val items = g.tools.filter { it in all && it !in used }.sorted().map { it to (all[it] ?: "") }
            used.addAll(items.map { it.first })
            if (items.isNotEmpty()) out.add(g.label.substringBefore(":") to items)
        }
        val rest = all.keys.filter { it !in used }.sorted().map { it to (all[it] ?: "") }
        if (rest.isNotEmpty()) out.add("Everything else" to rest)
        return out
    }
}
