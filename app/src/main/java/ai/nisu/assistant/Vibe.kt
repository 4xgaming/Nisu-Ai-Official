package ai.nisu.assistant

import android.content.Context
import kotlin.math.pow

/**
 * v2.48: "she slowly gets to know what he wants right now".
 *
 * Every message he says or types is scored (offline, simple word / emoji lists in Bengali, Banglish, Hindi, English) for a few moods:
 * romantic, playful, sad, angry, curious, task, tired. The scores are smoothed over messages (each new message moves them 30 %), so a mood
 * builds up over several messages and fades when he moves on; they also fade with time (half-life 2 days). The result is turned into a short
 * hint that goes into the prompt of the voice session (at connect) and of every typed-chat message.
 *
 * It is only a hint: the model also reads him by itself inside the conversation (see [Persona.adaptiveNote]) and his newest message always wins.
 * Only seven small numbers are stored, never any message text. Nothing here is used to hold him in the conversation.
 */
object VibeReader {
    private const val KEY = "vibe_state"
    private const val ALPHA = 0.55f
    private const val HALF_LIFE_H = 48.0
    private const val NOTABLE = 0.25f

    val CATS = listOf("romantic", "playful", "sad", "angry", "curious", "task", "tired")

    private val WORDS: Map<String, List<String>> = mapOf(
        "romantic" to listOf(
            "love you", "i love", "miss you", "missing you", "my love", "sweetheart", "darling", "jaan", "janu", "babu", "baby", "babe", "kiss", "hug me", "cuddle",
            "beautiful", "so cute", "handsome", "crush", "date", "valobashi", "bhalobashi", "bhalobasi", "valobasi", "miss korchi", "miss kori", "ador", "chumu",
            "ভালোবাসি", "ভালবাসি", "ভালোবাসা", "মিস করছি", "মিস করি", "জান", "বাবু", "আদর", "চুমু", "জড়িয়ে", "সুন্দর লাগ", "প্রিয়",
            "प्यार", "मोहब्बत", "मिस कर", "जान", "बाबू", "गले लग", "चूम", "सुंदर", "जानू", "तुम मेरी",
            "marry me", "be mine", "tomake chai", "sundori", "tumi amar", "my girl", "sweet dreams", "your smile", "dream about you", "তোমাকে চাই", "জানু", "তুমি আমার",
            "❤", "😘", "🥰", "😍", "💕", "💖", "💋", "😚", "💞"
        ),
        "playful" to listOf(
            "haha", "hehe", "lol", "lmao", "joke", "tease", "kidding", "funny", "pagol", "moja", "mojar", "hasi", "dare",
            "হাহা", "হা হা", "হিহি", "মজা", "পাগল", "ফাজলামি", "ঠাট্টা",
            "हाहा", "हीही", "मज़ा", "मजाक", "मज़ाक", "पागल",
            "😂", "🤣", "😜", "😝", "😆"
        ),
        "sad" to listOf(
            "sad", "cry", "crying", "lonely", "alone", "depress", "hurt", "tired of", "give up", "hopeless", "kharap lagche", "mon kharap", "kosto", "ekla",
            "কষ্ট", "মন খারাপ", "একা", "কান্না", "কাঁদ", "দুঃখ", "ভালো লাগছে না", "ভালো লাগে না",
            "उदास", "अकेला", "अकेली", "दुखी", "रो ", "दर्द", "मन नहीं लग",
            "😢", "😭", "😞", "💔", "🥺"
        ),
        "angry" to listOf(
            "angry", "hate", "stupid", "shut up", "annoy", "irritat", "idiot", "fed up", "rag lagche", "biroktikor", "gussa", "bakwas",
            "রাগ", "বিরক্ত", "চুপ কর", "গাধা", "অসহ্য",
            "गुस्सा", "बकवास", "चिढ़", "परेशान",
            "😡", "🤬", "😠"
        ),
        "curious" to listOf(
            "how does", "how do", "why ", "what is", "what are", "explain", "tell me about", "difference between", "kivabe", "kibhabe", "keno ", "ki kore", "bujhaiya", "bujhao",
            "কেন ", "কীভাবে", "কিভাবে", "কী করে", "বুঝিয়ে", "মানে কী", "মানে কি", "কাকে বলে",
            "क्यों", "कैसे", "समझा", "क्या होता", "मतलब क्या"
        ),
        "task" to listOf(
            "open ", "call ", "send ", "install", "remind", "schedule", "set alarm", "volume", "brightness", "message", "whatsapp", "messenger", "code", "app ", "file", "search",
            "ওপেন", "খোলো", "খুলো", "কল দাও", "কল করো", "পাঠাও", "রিমাইন্ডার", "অ্যালার্ম", "ভলিউম", "মেসেজ", "হোয়াটসঅ্যাপ",
            "खोलो", "कॉल करो", "भेजो", "अलार्म", "वॉल्यूम", "मैसेज"
        ),
        "tired" to listOf(
            "tired", "sleepy", "exhausted", "need sleep", "go to sleep", "ghum", "ghumabo", "klanto",
            "ঘুম", "ক্লান্ত", "ঘুমাব", "ঘুমাচ্ছি", "শুয়ে",
            "थक", "नींद", "सोना", "सोने",
            "😴", "🥱"
        )
    )

    /** How strongly ONE message shows each mood (0 = not at all, 0.8 = very clearly; one message alone never makes a mood "notable", a few in a row do). Pure function, no Android classes. */
    fun classify(text: String): Map<String, Float> {
        val t = " " + text.lowercase() + " "
        val out = HashMap<String, Float>()
        for ((cat, list) in WORDS) {
            var hits = 0
            for (w in list) if (t.contains(w)) hits++
            if (hits > 0) out[cat] = minOf(0.8f, 0.5f + 0.15f * (hits - 1))
        }
        if (text.count { it == '?' || it == '؟' || it == ';' } > 0 && !out.containsKey("curious") && text.length > 12) out["curious"] = 0.4f
        return out
    }

    /** One step of the smoothing: every mood moves [ALPHA] of the way towards this message's score (0 when it does not show). */
    fun step(state: Map<String, Float>, msg: Map<String, Float>): Map<String, Float> =
        CATS.associateWith { c -> (state[c] ?: 0f) * (1f - ALPHA) + (msg[c] ?: 0f) * ALPHA }

    private fun load(ctx: Context): Pair<Map<String, Float>, Long> {
        val raw = Prefs.get(ctx, KEY, "")
        if (raw.isBlank()) return emptyMap<String, Float>() to 0L
        return try {
            val ts = raw.substringBefore(';').toLong()
            val m = raw.substringAfter(';').split(',').mapNotNull { p ->
                val k = p.substringBefore(':'); val v = p.substringAfter(':').toFloatOrNull()
                if (k in CATS && v != null) k to v else null
            }.toMap()
            // fade with time
            val hours = ((System.currentTimeMillis() - ts).coerceAtLeast(0L)) / 3_600_000.0
            val f = 0.5.pow(hours / HALF_LIFE_H).toFloat()
            m.mapValues { it.value * f } to ts
        } catch (_: Exception) { emptyMap<String, Float>() to 0L }
    }

    private fun save(ctx: Context, m: Map<String, Float>) {
        Prefs.put(ctx, KEY, System.currentTimeMillis().toString() + ";" + CATS.joinToString(",") { it + ":" + "%.3f".format(java.util.Locale.US, m[it] ?: 0f) })
    }

    /** Call for every message he says (voice transcript) or types. Only numbers are kept. */
    @Synchronized fun observe(ctx: Context, text: String) {
        if (text.trim().length < 3) return
        try { save(ctx, step(load(ctx).first, classify(text))) } catch (_: Exception) {}
    }

    @Synchronized fun forget(ctx: Context) { Prefs.put(ctx, KEY, "") }

    private fun label(c: String) = when (c) {
        "romantic" -> "romantic and affectionate"
        "playful" -> "playful and joking"
        "sad" -> "low or sad"
        "angry" -> "irritated or angry"
        "curious" -> "curious, asking how and why"
        "task" -> "busy with practical things on the phone"
        else -> "tired or sleepy"
    }

    private fun advice(c: String, mode: String) = when (c) {
        "romantic" -> if (mode == PersonaMode.CARE) "answer at once, one step MORE romantic than he is right now: closer, warmer and more loving than his own words, like a girlfriend who is deeply in love, never colder than he is"
            else "he is being affectionate: answer with warm friendly affection and light teasing (in Boss mode stay calm and professional) and do not return romance"
        "playful" -> "banter, tease back, bring up earlier jokes, keep it light and quick"
        "sad" -> "slow down, be soft, listen first, no jokes, comfort before any advice"
        "angry" -> "stay calm and steady, no jokes, do not argue, show you understand"
        "curious" -> "give real substance, go one level deeper than he asked, share an interesting angle"
        "task" -> "be efficient and exact, short answers, no flirting or small talk until the job is done"
        else -> "keep it short, soft and slow (a sleep reminder only when the SLEEP AND FOOD REMINDERS block allows it)"
    }

    /** The hint for the prompt, or "" when nothing stands out yet. */
    fun note(ctx: Context): String = try {
        val mode = PersonaMode.saved(ctx)
        val top = load(ctx).first.filter { it.value >= NOTABLE }.entries.sortedByDescending { it.value }.take(3)
        if (top.isEmpty()) "" else buildString {
            append("\n\nHOW HE SEEMS RECENTLY (your own slow reading of his last messages, only a hint: his newest message always wins, and never tell him you are analysing him): ")
            append(top.joinToString("; ") { (if (it.value >= 0.6f) "strongly " else if (it.value >= 0.4f) "clearly " else "a little ") + label(it.key) })
            append(". How to meet it: ")
            append(top.joinToString("; ") { label(it.key) + " -> " + advice(it.key, mode) })
            append(".\n")
        }
    } catch (_: Exception) { "" }
}
