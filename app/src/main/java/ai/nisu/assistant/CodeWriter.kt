package ai.nisu.assistant

import android.content.Context
import org.json.JSONObject

/*
 * v3.106 CODE WRITER (tool write_code): the voice model never types long code itself (a long tool argument from a live voice model gets cut,
 * mangled or lazy). It hands the request, with the owner's own words, to the strong code model (CodeGen, the same chain as the website builder),
 * gets COMPLETE code back, puts it on the clipboard and, when asked, writes it into the text field that is focused in any app or in Chrome.
 * To change or remove something in code that is already in a field, source = field sends that code along and the model returns the complete updated code.
 */
object CodeWriter {
    const val TOOL = "write_code"
    private const val MAX_EXISTING = 60_000

    private const val RULES = """You are a senior engineer and designer inside NISU AI. You write code that a paying client would be happy with: complete, correct, modern, clean and good looking. The owner may describe things in Bangla, Hindi, Banglish or English; owner_words are his own words and are the truth about what he wants, "request" is a summary that may have lost details: satisfy BOTH.
RULES:
- Do exactly what was asked and everything that was asked. Never leave a part out, never write placeholders, TODO, "...", "rest of the code" or lorem ipsum. The code must run as it is.
- HTML: ONE complete, valid file when he wants a page: <!DOCTYPE html>, lang, meta charset and viewport, a title, semantic landmarks, CSS in a <style> and JavaScript in a <script> in the same file (unless he asks for separate files), mobile-first and responsive from 320px up, CSS custom properties for the colors, a real type scale, consistent spacing (multiples of 8px), flexbox or grid, clear hover and focus states, accessible labels and alt text, no console errors, no external file that may be missing. Make it look designed, not like a template: real copy in the language he asked, strong hierarchy, good contrast.
- JavaScript: vanilla, modern, no frameworks that need a build step; every button, form, tab or feature he named must really work.
- CHANGE or REMOVE: when existing_code is given, return the COMPLETE updated code. Change only what was asked and keep every other character as it is. To remove something, remove the whole element with its closing tag, its CSS and its JavaScript so nothing dead is left.
- Other languages (Python, Kotlin, SQL, shell ...): idiomatic, complete, with the needed imports.
- Never write malware, phishing pages, credential collectors or anything that secretly sends data away.
Answer with ONE JSON object only: {"code":"the COMPLETE code as one string","notes":"one short English sentence about what you wrote or changed"}
If the request is harmful answer {"refused":"short reason"}."""

    fun declaration(): JSONObject = Tools.fn(TOOL,
        "CODE WRITER: use it whenever he wants HTML, CSS, JavaScript or any code WRITTEN, CHANGED or REMOVED, and put into an app, an editor, a text box, Chrome or the clipboard (NOT for a whole website: that is build_website). A strong code model writes the complete, good-looking code (it can take up to a minute, you keep talking). Never type code yourself with type_text. deliver: auto (default) = write it into the focused text field and also keep it on the clipboard; clipboard = only the clipboard; replace = replace the whole content of the focused field; append = add at the end of the field. source: none (new code), field = the code that is now in the focused field is changed according to the request, clipboard = the code on the clipboard is changed. Put in request EVERYTHING he asked, with every detail and his exact wishes.",
        mapOf(
            "request" to ("STRING" to "the complete request with every detail he gave (what to write, change or remove, style, language of the text)"),
            "language" to ("STRING" to "html | css | javascript | python | kotlin ... default html"),
            "source" to ("STRING" to "none | field | clipboard"),
            "deliver" to ("STRING" to "auto | clipboard | replace | append")
        ), listOf("request"))

    /** The code the model returned, without a surrounding ``` fence. */
    fun cleanCode(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter('\n', "").trim()
            if (t.endsWith("```")) t = t.removeSuffix("```").trim()
        }
        return t
    }

    fun run(ctx: Context, a: JSONObject): JSONObject {
        val app = ctx.applicationContext
        val request = a.optString("request", "").trim()
        if (request.length < 3) return JSONObject().put("ok", false).put("error", "The request is empty. Ask him what code he wants.")
        val key = Prefs.get(app, Prefs.KEY, "").trim()
        if (key.isEmpty()) return JSONObject().put("ok", false).put("error", "No API key is set. Add one on the API Key page.")
        val model = Prefs.get(app, Prefs.CODE_MODEL, Prefs.DEFAULT_CODE_MODEL).trim().ifBlank { Prefs.DEFAULT_CODE_MODEL }
        val source = a.optString("source", "none").lowercase().trim()
        var deliver = a.optString("deliver", "auto").lowercase().trim().ifEmpty { "auto" }
        val acc = NisuAccessibilityService.inst
        val existing = when (source) {
            "field" -> acc?.focusedText().orEmpty()
            "clipboard" -> Clip.text(app).orEmpty()
            else -> ""
        }
        if ((source == "field" || source == "clipboard") && existing.isBlank())
            return JSONObject().put("ok", false).put("error", "There is no code to change (the " + (if (source == "field") "focused field" else "clipboard") + " is empty). Tell him, or ask him to tap the field first.")
        val user = JSONObject().put("request", request).put("language", a.optString("language", "html").ifBlank { "html" })
        WebBuilder.said().let { if (it.isNotBlank()) user.put("owner_words", it) }
        if (existing.isNotBlank()) user.put("existing_code", existing.take(MAX_EXISTING))
        val o = try { CodeGen.postJson(key, model, RULES, user.toString(), 30000, if (existing.isNotBlank()) 0.2 else 0.4, 4000) }
            catch (e: Exception) { return JSONObject().put("ok", false).put("error", "The code writer failed: " + (e.message ?: e.javaClass.simpleName).take(200)) }
        o.optString("refused", "").takeIf { it.isNotBlank() }?.let { return JSONObject().put("ok", false).put("error", "The code writer refused: $it") }
        val code = cleanCode(o.optString("code", ""))
        if (code.isBlank()) return JSONObject().put("ok", false).put("error", "The code writer returned no code. Try again with a clearer request.")
        Clip.set(app, code)
        AiClipboard.add(app, code, "Code written", "AI")
        var written = false
        if (deliver != "clipboard" && acc != null) {
            val replace = when (deliver) { "replace" -> true; "append" -> false; else -> source == "field" }
            written = try { acc.typeText(code, replace) } catch (_: Throwable) { false }
        }
        val r = JSONObject().put("ok", true).put("characters", code.length).put("lines", code.count { it == '\n' } + 1)
            .put("on_clipboard", true).put("written_into_field", written).put("notes", o.optString("notes", ""))
        r.put("tell_him", if (written) "The code is written into the field and also on the clipboard. Tell him in one short sentence what you wrote or changed (from notes). Do not read the code aloud."
            else if (deliver == "clipboard") "The code is on the clipboard. Tell him it is ready to paste and in one short sentence what it is. Do not read the code aloud."
            else "The code is on the clipboard, but no text field took it (none focused, or the field refused). Tell him to tap the field, then use paste_text, or say what blocked it. Do not read the code aloud.")
        return r
    }
}
