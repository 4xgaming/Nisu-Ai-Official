package ai.nisu.assistant

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * v3.76: change the voice model by voice ("change to 3.8", "use Beta Fast", "Beta all worked"). The tool saves the choice and tells the service to
 * reconnect; the new model is active when the connection is back (the Edge Glow lights for a second then).
 */
object ModelSwitch {
    fun declaration(): JSONObject = Tools.fn("switch_model",
        "Change the voice model he talks to you with, ONLY when he asks (\"change the model to 3.8\", \"use Beta Fast\", \"Beta all worked\"). Models: " +
            LiveModels.ALL.joinToString(", ") { it.label } + ". For Beta Fast you can give mode fast or all_worked. You reconnect by yourself in a few seconds: say ONE very short sentence (for example \"Okay, changing\") and nothing more.",
        mapOf("model" to ("STRING" to "name of the model, e.g. 3.1, 3.8, beta fast, beta all worked"),
            "mode" to ("STRING" to "only for Beta Fast: fast | all_worked")), listOf("model"))

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9.]"), "")

    fun resolve(q: String): LiveModels.Model? {
        val n = norm(q)
        if (n.isEmpty()) return null
        if (n.contains("beta") || n.contains("extrafast") || n == "fast") return LiveModels.find(LiveModels.BETA_ID)
        if (n.contains("3.8") || n == "38") return LiveModels.find("gemini-3.8-live")
        if (n.contains("3.1") || n == "31" || n.contains("flash")) return LiveModels.find(Prefs.DEFAULT_MODEL)
        return LiveModels.ALL.firstOrNull { norm(it.label) == n || norm(it.id) == n }
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    fun run(ctx: Context, a: JSONObject): JSONObject {
        val said = a.optString("model", "").trim()
        val m = resolve(said) ?: return err("I do not know a model called \"$said\". The models are: " + LiveModels.ALL.joinToString(", ") { it.label } + ".")
        val sel = Prefs.selectedModel(ctx)
        var betaChanged = false
        if (m.id == LiveModels.BETA_ID) {
            val w = (a.optString("mode", "") + " " + said).lowercase()
            val want = when {
                listOf("all", "full", "worked").any { it in w } -> "full"
                "fast" in w -> "fast"
                else -> Prefs.betaMode(ctx)
            }
            if (want != Prefs.betaMode(ctx)) { Prefs.put(ctx, Prefs.BETA_MODE, want); RuleSet.stamp = System.currentTimeMillis(); betaChanged = true }
        }
        if (m.id == sel && !betaChanged) return JSONObject().put("ok", true).put("note", "Already on " + m.label + ". Tell him in one short sentence.")
        val i = Intent(ctx, AssistantService::class.java)
        if (m.id == sel) i.setAction(AssistantService.ACTION_PERSONA) else i.setAction(AssistantService.ACTION_SWITCH).putExtra("model", m.id)
        ctx.startService(i)
        val label = m.label + (if (m.id == LiveModels.BETA_ID) (if (Prefs.betaFull(ctx)) " (all worked)" else " (fast)") else "")
        return JSONObject().put("ok", true).put("switching_to", label).put("note", "Reconnecting now. Say one very short sentence only.")
    }
}
