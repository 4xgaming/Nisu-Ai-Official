package ai.nisu.assistant

import android.content.Context
import org.json.JSONObject

/**
 * v3.00: lets the typed chat (the Main Brain chat window) hand website and app requests to the Main Brain builder.
 * A build request gets a short plan first; the next "yes" starts the real builder (WebBuilder / AppBuilder),
 * so nothing is built without his confirmation, exactly like in voice mode.
 */
object BuildChat {
    class Plan(val kind: String, val name: String, val description: String, val template: String, val summary: String, val time: Long = System.currentTimeMillis())

    @Volatile private var pending: Plan? = null
    private const val PLAN_TTL_MS = 15 * 60 * 1000L

    /** Messages from the builders (ready, failed) that the chat page shows as her lines. */
    @Volatile var onNotice: ((String) -> Unit)? = null

    private val BUILD_WORD = Regex(
        "website|web ?site|web ?page|landing ?page|portfolio|\\bsite\\b|\\bapp\\b|\\bapk\\b|application|" +
            "ওয়েবসাইট|ওয়েব ?সাইট|সাইট|ওয়েবপেজ|অ্যাপ|এপ|অ্যাপস|এপিকে|" +
            "वेबसाइट|साइट|ऐप|एप",
        RegexOption.IGNORE_CASE)
    private val MAKE_WORD = Regex(
        "\\b(make|build|create|develop|design|banao|banaw|banai|baniye|banate|baniye ?dao|bana ?do|banado|kore ?dao|toiri|tairi|chai)\\b|" +
            "বানাও|বানিয়ে|বানাতে|বানাই|বানা|তৈরি|তৈরী|করে দাও|চাই|" +
            "बनाओ|बना दो|बनाना|बनाइए|तैयार",
        RegexOption.IGNORE_CASE)
    private val YES = Regex(
        "^\\s*(yes|yeah|yep|ok|okay|sure|go|go ahead|start|do it|haa+|ha+n?|hm+|hu+m+|thik ?ache|accha|achha|ji|kor|koro|banao|" +
            "হ্যাঁ|হা|হুম|ঠিক আছে|আচ্ছা|করো|বানাও|শুরু করো|হ্যা|" +
            "हाँ|हां|ठीक है|करो|बनाओ)(?:[\\s,.!?।]|$).*",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val NO = Regex("^\\s*(no|nope|cancel|stop|na|naa|nah|না|থাক|বাদ দাও|नहीं|रहने दो)(?:[\\s,.!?।]|$).*",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** True when the message looks like a request to build a website or an app (cheap, no network). */
    fun looksLikeBuild(text: String): Boolean = BUILD_WORD.containsMatchIn(text) && MAKE_WORD.containsMatchIn(text)

    fun hasPending(): Boolean = pending?.let { System.currentTimeMillis() - it.time < PLAN_TTL_MS } == true

    /**
     * Handles [text] when it belongs to the builder and returns her reply, or null when the normal chat should answer.
     * Runs on a background thread (network).
     */
    fun handle(ctx: Context, text: String): String? {
        val app = ctx.applicationContext
        val plan = pending
        if (plan != null && hasPending()) {
            if (YES.matches(text.trim())) { pending = null; return start(app, plan) }
            if (NO.matches(text.trim())) { pending = null; return "ঠিক আছে, বাদ দিলাম।" }
        }
        if (!looksLikeBuild(text)) return null
        if (Prefs.get(app, Prefs.KEY, "").trim().isEmpty())
            return "আগে API Key page এ key দিতে হবে।"
        val p = understand(app, text) ?: return null
        if (p.kind == "none") return null
        pending = p
        val what = if (p.kind == "website") "website" else "Android app (source code + APK)"
        return "Main Brain দিয়ে একটা professional $what বানাবো: ${p.name}।\n\n${p.summary.ifBlank { p.description.take(300) }}\n\nশুরু করবো? \"হ্যাঁ\" বললেই বানানো শুরু।"
    }

    private const val UNDERSTAND_RULES = """You turn a user's chat message into a build request for NISU AI's Main Brain builder.
The message may be Bengali, Banglish, Hindi, Hinglish or English.
Answer with ONE JSON object only:
{"kind":"website" | "app" | "none","name":"short name in Latin letters, max 3 words","description":"complete professional English brief: purpose, target audience, every section or screen, features, style and color direction, and the language the visible text must use (the user's language unless he asked otherwise)","template":"webview" | "native","plan":"2 to 4 short lines in the SAME language and writing style as the user's message describing what will be built"}
kind is "app" for an Android app or APK, "website" for a website, web page or web tool, "none" if he is not asking to build something now.
Use template "native" only when real Android features (sensors, notifications, camera, files) are needed, otherwise "webview".
Refuse phishing, fake brand logins, malware or data theft with kind "none"."""

    private fun understand(ctx: Context, text: String): Plan? = try {
        val key = Prefs.get(ctx, Prefs.KEY, "").trim()
        val model = Prefs.get(ctx, Prefs.CODE_MODEL, Prefs.DEFAULT_CODE_MODEL).trim().ifBlank { Prefs.DEFAULT_CODE_MODEL }
        val o = CodeGen.postJson(key, model, UNDERSTAND_RULES, JSONObject().put("message", text).toString(), 1500, 0.3, 400)
        Plan(
            o.optString("kind", "none").trim().lowercase(),
            o.optString("name", "").trim().ifBlank { "Project" }.take(40),
            o.optString("description", "").trim().ifBlank { text },
            if (o.optString("template", "webview") == "native") "native" else "webview",
            o.optString("plan", "").trim()
        )
    } catch (_: Exception) { null }

    private fun start(ctx: Context, p: Plan): String {
        val args = JSONObject().put("name", p.name).put("description", p.description).put("user_confirmed", true)
        val r = if (p.kind == "website") WebBuilder.run(ctx, "build_website", args)
            else AppBuilder.run(ctx, "build_app", args.put("template", p.template).put("build", true))
        r.optString("error", "").takeIf { it.isNotBlank() }?.let { return "শুরু করা গেলো না: ${it.take(200)}" }
        return if (p.kind == "website")
            "শুরু করে দিয়েছি! Main Brain এখন design plan করে professional website লিখছে, তারপর নিজেই Chrome এ খুলে error থাকলে ঠিক করে দেবে। হয়ে গেলে এখানে জানাবো।"
        else
            "শুরু করে দিয়েছি! Main Brain এখন app এর source code লিখছে, তারপর Termux এ APK build করবে আর build error হলে নিজেই ঠিক করবে। হয়ে গেলে এখানে জানাবো।"
    }

    /** Called by the builders' notice; turns the English instruction into a short line for the chat page. */
    internal fun notice(raw: String) {
        val cb = onNotice ?: return
        val t = raw.substringBefore(". Tell him").substringBefore("Tell him").trim()
        val line = when {
            raw.startsWith("[WEBSITE]") && raw.contains("failed") -> "Website বানাতে সমস্যা হলো: " + t.removePrefix("[WEBSITE]").trim()
            raw.startsWith("[WEBSITE]") -> "Website রেডি! Chrome এ খুলে দিয়েছি। (" + t.removePrefix("[WEBSITE]").trim() + ")"
            raw.startsWith("[APP BUILD]") && (raw.contains("could not") || raw.contains("failed")) -> "App বানাতে সমস্যা হলো: " + t.removePrefix("[APP BUILD]").trim()
            raw.startsWith("[APP BUILD]") -> "App রেডি! " + t.removePrefix("[APP BUILD]").trim()
            else -> return
        }
        try { cb(line) } catch (_: Exception) {}
    }
}
