package ai.nisu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Pure rules of the website builder (unit tested): which paths may be written, mime types, and the helper script put into every page. */
object WebRules {
    private val ALLOWED_EXT = setOf("html", "htm", "css", "js", "mjs", "json", "svg", "txt", "md", "xml", "webmanifest", "ico")
    const val MAX_FILES = 40
    const val MAX_FILE_CHARS = 400_000

    fun cleanSlug(s: String): String = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.trim('-').take(40)

    fun slugOf(name: String): String = cleanSlug(name.trim().replace(Regex("\\s+"), "-")).ifEmpty { "site" + (System.currentTimeMillis() % 100000) }

    /** A relative path inside the site folder with an allowed extension, or null. */
    fun safePath(p0: String): String? {
        val p = p0.trim().replace('\\', '/').trimStart('/').removePrefix("./")
        if (p.isEmpty() || p.length > 120) return null
        val segs = p.split('/')
        if (segs.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
        if (!Regex("^[A-Za-z0-9._/-]+$").matches(p)) return null
        val ext = p.substringAfterLast('.', "").lowercase()
        return if (ext in ALLOWED_EXT) p else null
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "webmanifest" -> "application/manifest+json"
        "svg" -> "image/svg+xml"
        "xml" -> "application/xml"
        "ico" -> "image/x-icon"
        "md", "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    /** Reports errors back to NISU AI and reloads the page when a fix is written. Inserted right after <head> (or at the top). */
    fun helperScript(site: String): String =
        "<script data-nisu=\"1\">(function(){var s='" + site + "';" +
            "function r(m){try{var b=String(m).slice(0,1500);if(navigator.sendBeacon){navigator.sendBeacon('/__nisu/err?site='+s,b)}else{fetch('/__nisu/err?site='+s,{method:'POST',body:b})}}catch(e){}}" +
            "window.addEventListener('error',function(e){var t=e.target;if(t&&t!==window&&(t.src||t.href)){r('Failed to load '+(t.src||t.href));return}" +
            "r((e.message||'Error')+' at '+(e.filename||'')+':'+(e.lineno||0)+':'+(e.colno||0))},true);" +
            "window.addEventListener('unhandledrejection',function(e){var x=e.reason;r('Unhandled promise rejection: '+((x&&x.message)||x))});" +
            "var oe=console.error;console.error=function(){try{r('console.error: '+Array.prototype.map.call(arguments,String).join(' '))}catch(e){}return oe.apply(console,arguments)};" +
            "var v=null;setInterval(function(){fetch('/__nisu/ver?site='+s,{cache:'no-store'}).then(function(x){return x.text()})" +
            ".then(function(t){if(v===null){v=t}else if(t!==v){location.reload()}}).catch(function(){})},2000)})();</script>"

    fun inject(html: String, site: String): String {
        if (html.contains("data-nisu=\"1\"")) return html
        val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (m != null) html.substring(0, m.range.last + 1) + helperScript(site) + html.substring(m.range.last + 1)
        else helperScript(site) + html
    }

    class Edit(val path: String, val find: String, val replace: String, val all: Boolean)

    /** v3.106: {"edits":[{"path","find","replace","all"}]} from a model answer. An empty replace REMOVES the found text. */
    fun editsOf(o: JSONObject): List<Edit> {
        val ea = o.optJSONArray("edits") ?: return emptyList()
        val out = ArrayList<Edit>()
        for (i in 0 until ea.length()) {
            val e = ea.optJSONObject(i) ?: continue
            val path = safePath(e.optString("path")) ?: continue
            val find = e.optString("find")
            if (find.isEmpty()) continue
            out.add(Edit(path, find, e.optString("replace"), e.optBoolean("all", false)))
            if (out.size >= 60) break
        }
        return out
    }

    /** Result of one exact-text edit: the new text, or an error sentence for the model. */
    class Applied(val text: String?, val error: String)

    /**
     * Replaces [find] with [replace] (empty = remove). The text must be copied exactly from the file; a match that differs only in line endings or
     * in the spaces at the line edges is accepted when it is the only one. Not found, or found more than once without [all], is an error (nothing changes).
     */
    fun applyEdit(text: String, find: String, replace: String, all: Boolean): Applied {
        if (find.isEmpty()) return Applied(null, "find is empty")
        val n = text.split(find).size - 1
        if (n == 1 || (n > 1 && all)) return Applied(if (all) text.replace(find, replace) else text.replaceFirst(find, replace), "")
        if (n > 1) return Applied(null, "find matches $n places: add more surrounding text so it matches exactly one, or set all")
        val lines = find.replace("\r\n", "\n").trim('\n').split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Applied(null, "find is blank")
        val rx = Regex(lines.joinToString("\\s*") { Regex.escape(it) })
        val ms = rx.findAll(text).toList()
        if (ms.size == 1) return Applied(text.substring(0, ms[0].range.first) + replace + text.substring(ms[0].range.last + 1), "")
        if (ms.size > 1) return Applied(null, "find matches ${ms.size} places: add more surrounding text so it matches exactly one")
        return Applied(null, "find was not found in the file: copy it exactly from the current file")
    }

    /** Reads {"files":[{"path","content"}]} from a model answer, keeping only safe, size-limited files. */
    fun filesOf(o: JSONObject): List<Pair<String, String>> {
        val fa = o.optJSONArray("files") ?: JSONArray()
        val out = LinkedHashMap<String, String>()
        for (i in 0 until fa.length()) {
            val f = fa.optJSONObject(i) ?: continue
            val path = safePath(f.optString("path")) ?: continue
            val content = f.optString("content")
            if (content.length > MAX_FILE_CHARS) continue
            out[path] = content
            if (out.size >= MAX_FILES) break
        }
        return out.toList()
    }
}

/*
 * WEBSITE BUILDER (v2.99): NISU AI writes a complete website from a description (HTML, CSS, JavaScript, no build step),
 * opens it in Chrome straight from the phone (LocalWeb), watches the real browser errors the page reports, and repairs the code by itself
 * until the page runs clean. The page reloads in Chrome automatically after every fix.
 *
 * The code is written by the Main Brain through the same call the APK builder uses (CodeGen.postJson, streamed to the Live Code page). The answer is untrusted text: only WebRules.safePath files are written, inside filesDir/websites.
 */
object WebBuilder {
    val NAMES = setOf("build_website", "website_fix", "website_open", "website_status", "website_list", "website_read_file", "website_write_file", "website_edit")

    class Job(val slug: String, val name: String) {
        @Volatile var stage = "starting"
        @Volatile var state = "running"      // running | done | failed
        @Volatile var error = ""
        @Volatile var url = ""
        @Volatile var fixes = 0
        @Volatile var notes = ""
    }

    /** v3.106: what the owner really said (the last words heard), set by the voice service. The model's own summary loses details; this does not. */
    @Volatile var ownerWords: String = ""
    @Volatile var currentTurn: String = ""   // what he is saying right now (not yet saved as a finished turn)
    fun said(): String = (ownerWords + " | " + currentTurn).trim(' ', '|').takeLast(1200)

    private val jobs = ConcurrentHashMap<String, Job>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "nisu-webbuild").apply { isDaemon = true } }

    private const val AUTO_FIX_ROUNDS = 3
    private const val WATCH_MS = 9_000L
    private const val MAX_CONTEXT_CHARS = 120_000

    /** v3.00 step 1: an art director writes a design brief first, so the code is written against a real plan. */
    private const val PLAN_RULES = """You are the art director and product designer of a top web agency, planning a PROFESSIONAL website that looks like it was made by a paid studio (think Stripe, Linear, Apple, Vercel quality), not a template.
From the user's description write a precise design brief. Choose a direction that fits the subject (a bakery is warm and friendly, a law firm calm and trustworthy, a tech product sharp and modern).
Answer with ONE JSON object only:
{"brand":"name shown on the site","tagline":"one strong line","audience":"who it is for","palette":{"background":"#hex","surface":"#hex","text":"#hex","muted":"#hex","primary":"#hex","primary_text":"#hex","accent":"#hex"},
"fonts":{"heading":"one Google Font","body":"one Google Font"},"style":"3 to 5 words, e.g. clean, airy, editorial",
"sections":[{"id":"hero","goal":"what this section must achieve","content":"the REAL final copy: headline, sub text, button labels, list items, prices, names, numbers"}],
"features":["every interactive feature that must really work"],"language":"language of all visible text"}
Rules: 3 to 5 colors with strong contrast (WCAG AA), one primary color used for actions only; never purple-blue gradients by default; 5 to 9 sections that a real site of this kind has (for example hero, social proof, features, how it works, pricing or menu, testimonials, FAQ, call to action, footer); write real, specific, persuasive copy, never lorem ipsum or "Feature 1"."""

    /** v3.00 step 3: a design reviewer polishes the first version against a professional checklist. */
    private const val REVIEW_RULES = """You are a strict senior design reviewer at a top web agency. You get a website's files and its design brief.
Check it against this professional checklist and FIX everything that falls short:
1. Strong hero: clear headline, sub text, a primary call-to-action button, good visual weight above the fold on a 360px phone.
2. Consistent spacing scale (multiples of 4 or 8px), generous white space, max content width about 1120px, sections clearly separated.
3. Typography: the two brief fonts loaded, clear size hierarchy (h1 clamp() fluid size), line-height 1.5 to 1.7 for body, no text wider than about 70 characters.
4. Colors only from the brief, CSS custom properties in :root, AA contrast, visible :hover and :focus-visible states on every link and button.
5. Real responsive layout: flexbox / grid, works from 320px to 1440px, a working mobile menu (hamburger with aria-expanded) when there is navigation.
6. Polish: subtle shadows and rounded corners used consistently, smooth transitions (150 to 300ms), scroll-reveal with IntersectionObserver respecting prefers-reduced-motion, sticky header, smooth anchor scrolling.
7. Every feature in the brief really works (forms validate and show a success message, tabs switch, filters filter, carts add up, etc.) and there are no console errors.
8. Images: no broken image links; use inline SVG illustrations/icons, CSS shapes or gradients instead of missing pictures.
9. SEO and accessibility: title, meta description, theme-color, one h1, semantic landmarks, alt text, labels for inputs, favicon as inline SVG data URL.
10. Real copy everywhere, no lorem ipsum, no placeholder names.
Answer with ONE JSON object only: {"files":[{"path":"...","content":"COMPLETE new content of every file you changed"}],"notes":"one short English sentence"}
Return only changed files, and keep everything that already works."""

    private const val BUILD_RULES = """You are a senior front-end engineer at a top web agency inside NISU AI. You turn a design brief into a complete, working, PROFESSIONAL static website that looks hand-crafted by a studio, never like a generic template or an AI demo.
Rules:
- Only plain HTML, CSS and vanilla JavaScript. No build step, no npm, no frameworks that need compiling. The site is opened directly in Chrome on an Android phone.
- index.html is required. Split CSS and JS into style.css and script.js (or a few more files, e.g. extra pages like about.html) with relative paths. No absolute paths, no folders starting with a dot.
- Follow the design brief exactly: its palette as CSS custom properties in :root, its two fonts from Google Fonts, its sections in order with its real copy, every listed feature working.
- Professional craft: mobile-first, responsive from 320px to 1440px with flexbox/grid, consistent 8px spacing scale, fluid type with clamp(), max content width about 1120px, sticky header with a working mobile menu, clear hover/focus states, consistent radius and soft shadows, tasteful transitions and IntersectionObserver scroll reveals that respect prefers-reduced-motion.
- No broken images: draw icons and illustrations as inline SVG, CSS shapes or gradients.
- Semantic, accessible HTML (landmarks, one h1, labels, alt text, aria-expanded on toggles, AA contrast), plus title, meta description, viewport, theme-color and an inline SVG favicon.
- Save user data with localStorage only when the site needs to remember something.
- Prefer self-contained code. Google Fonts is fine; another well-known CDN only when really needed.
- Never build phishing, login pages that imitate real brands, credential collectors, malware, or anything that sends data to third-party servers without the user knowing.
Answer with ONE JSON object only: {"files":[{"path":"index.html","content":"..."}],"notes":"one short English sentence about what was built"}
If the request is harmful answer {"refused":"short reason"}."""

    private const val FIX_RULES = """You are a senior front-end engineer inside NISU AI repairing a static website (HTML, CSS, vanilla JavaScript) that runs in Chrome on an Android phone.
You get the current files, the problem the owner described (may be empty) and the real errors Chrome reported (may be empty).
Find the real cause and fix it. Keep everything that already works and keep the design. Do not add a build step.
Prefer small exact "edits" ({"path","find" copied exactly from the file,"replace"}) over rewriting whole files; use "files" (the COMPLETE new content) only when most of a file must change.
Answer with ONE JSON object only: {"edits":[],"files":[{"path":"...","content":"the COMPLETE new content of every file you changed or added"}],"notes":"one short English sentence about the fix"}
If nothing needs to change return {"edits":[],"files":[],"notes":"why"}."""

    /** v3.106: a change / removal asked by the owner: do EXACTLY that, with small exact edits instead of rewriting whole files. */
    private const val CHANGE_RULES = """You are a senior front-end engineer inside NISU AI changing an existing static website (HTML, CSS, vanilla JavaScript) that runs in Chrome on an Android phone.
You get the current files, what the owner wants changed (description) and, when present, the owner's own words (owner_words, may be Bangla, Hindi or Banglish: read them carefully, they are the truth about what he wants).
RULES: 1. Do EXACTLY what was asked, completely, and nothing else. Do not redesign, do not rename, do not move or restyle things he did not mention, do not "improve" other parts. 2. To REMOVE something, remove the whole element or rule including its closing tag, its CSS and any JavaScript that only served it, so no empty box, dead link or console error is left. 3. To ADD something, match the style already used on the page (colors, fonts, spacing, class names) and make it fully working and responsive. 4. Keep everything else byte for byte.
OUTPUT: for a small or medium change use "edits": each edit has "path", "find" (text copied EXACTLY, character for character, from the current file, long enough to match only once) and "replace" (the new text; an empty string removes the find text). For a big change of one file you may instead return the COMPLETE new content of that file in "files". Never use a find that is not in the file.
Answer with ONE JSON object only: {"edits":[{"path":"index.html","find":"...","replace":"..."}],"files":[],"notes":"one short English sentence"}
If the request is impossible or unclear return {"edits":[],"files":[],"notes":"why"}."""

    // ---------------------------------------------------------------- tool declarations
    fun declarations(): List<JSONObject> = listOf(
        Tools.fn("build_website",
            "Website builder: creates a complete website (HTML/CSS/JS) from a description, opens it in Chrome on this phone, watches the real browser errors and fixes them by itself. Also changes an existing website when project is given. For a NEW website tell the user the plan in one or two short sentences and get a yes first; changing or removing something in an existing website (project given) needs no yes: for a small exact change prefer website_edit. Runs in the background: check website_status.",
            mapOf(
                "name" to ("STRING" to "short website name in Latin letters, e.g. Portfolio"),
                "description" to ("STRING" to "complete English description: purpose, sections/pages, features, colors and style, language of the text. For a change: what to change."),
                "project" to ("STRING" to "existing website name from website_list; only when changing a website"),
                "user_confirmed" to ("BOOLEAN" to "true only after the user said yes to the plan")
            ), listOf("description", "user_confirmed")),
        Tools.fn("website_fix",
            "Repair a website: reads the real errors Chrome reported plus the problem the user describes, fixes the code and reloads the page in Chrome. Use whenever the user says something on the site is broken, wrong or looks bad.",
            mapOf("project" to ("STRING" to "website name from website_list; empty for the most recent"),
                "problem" to ("STRING" to "what is wrong in the user's words, in English (optional when Chrome already reported errors)")), emptyList()),
        Tools.fn("website_open", "Open a built website in Chrome on this phone.",
            mapOf("project" to ("STRING" to "website name; empty for the most recent"))),
        Tools.fn("website_status", "Progress or result of the current or last website build or fix (state running / done / failed, stage, url, errors Chrome reported). Never claim a website is ready unless state is done.",
            mapOf("project" to ("STRING" to "website name, optional"))),
        Tools.fn("website_list", "List the websites NISU AI has built on this phone."),
        Tools.fn("website_read_file", "Read one file of a website, 12000 characters at a time. The result says the total length; give start to read further. Read before you edit.",
            mapOf("project" to ("STRING" to "website name"), "path" to ("STRING" to "file path, e.g. index.html"),
                "start" to ("NUMBER" to "first character to read, default 0")), listOf("project", "path")),
        Tools.fn("website_edit", "EXACT EDIT of a website file: replace the text find with replace; an empty replace REMOVES it. Use it to remove, change or add a small piece of HTML, CSS or JavaScript (a section, a button, a color, a line): the rest of the file stays untouched and the page in Chrome reloads by itself. find must be copied exactly from the file (read it first with website_read_file) and match once, or set all.",
            mapOf("project" to ("STRING" to "website name"), "path" to ("STRING" to "file path, e.g. index.html"),
                "find" to ("STRING" to "exact existing text"), "replace" to ("STRING" to "new text, empty = remove"),
                "all" to ("BOOLEAN" to "true to change every match")), listOf("project", "path", "find")),
        Tools.fn("website_write_file", "Save one COMPLETE file of a website (a whole new file only; never to change or remove a part: use website_edit for that). A big file is refused when the new content is much shorter than the old one, so nothing is lost by accident. The page in Chrome reloads by itself.",
            mapOf("project" to ("STRING" to "website name"), "path" to ("STRING" to "file path, e.g. style.css"),
                "content" to ("STRING" to "the complete new file content"), "allow_shrink" to ("BOOLEAN" to "true only when he really wants the file much shorter")), listOf("project", "path", "content"))
    )

    // ---------------------------------------------------------------- tool dispatch
    fun run(ctx: Context, name: String, a: JSONObject): JSONObject {
        val app = ctx.applicationContext
        return try {
            when (name) {
                "build_website" -> start(app, a)
                "website_fix" -> fix(app, a)
                "website_open" -> open(app, resolve(app, a.optString("project", "")))
                "website_status" -> status(app, a.optString("project", ""))
                "website_list" -> list(app)
                "website_read_file" -> readOne(app, a)
                "website_write_file" -> writeOne(app, a)
                "website_edit" -> editOne(app, a)
                else -> err("Unknown tool $name")
            }
        } catch (e: Exception) { err(e.message ?: e.javaClass.simpleName) }
    }

    private fun err(m: String) = JSONObject().put("error", m)
    private fun key(ctx: Context) = Prefs.get(ctx, Prefs.KEY, "").trim()
    private fun model(ctx: Context) = Prefs.get(ctx, Prefs.CODE_MODEL, Prefs.DEFAULT_CODE_MODEL).trim().ifBlank { Prefs.DEFAULT_CODE_MODEL }
    private fun dirOf(ctx: Context, slug: String) = File(LocalWeb.root(ctx), slug)
    private fun say(job: Job, s: String) { job.stage = s; LiveCode.step = "Website: $s"; AppState.add("Website builder: $s") }

    private fun sites(ctx: Context): List<File> =
        LocalWeb.root(ctx).listFiles()?.filter { it.isDirectory && File(it, "index.html").isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun resolve(ctx: Context, given: String): String {
        val g = WebRules.cleanSlug(given)
        if (g.isNotEmpty() && dirOf(ctx, g).isDirectory) return g
        if (g.isNotEmpty()) sites(ctx).firstOrNull { it.name.contains(g) }?.let { return it.name }
        return sites(ctx).firstOrNull()?.name ?: throw RuntimeException("No website has been built yet. Use build_website first.")
    }

    // ---------------------------------------------------------------- build
    private fun start(ctx: Context, a: JSONObject): JSONObject {
        val givenProject0 = WebRules.cleanSlug(a.optString("project", ""))
        val isChange0 = givenProject0.isNotEmpty() && dirOf(ctx, givenProject0).isDirectory
        if (!isChange0 && !a.optBoolean("user_confirmed", false)) return err("Tell the user the website plan in one or two short sentences, get a yes, then call again with user_confirmed true.")
        if (key(ctx).isEmpty()) return err("No API key is set. Add one on the API Key page.")
        val desc = a.optString("description", "").trim()
        if (desc.length < 5) return err("The description is too short. Ask the user what the website must do.")
        if (jobs.values.any { it.state == "running" }) return err("Another website job is still running. Check website_status.")
        val givenProject = WebRules.cleanSlug(a.optString("project", ""))
        val isChange = givenProject.isNotEmpty() && dirOf(ctx, givenProject).isDirectory
        val slug = if (isChange) givenProject else uniqueSlug(ctx, WebRules.slugOf(a.optString("name", "").ifBlank { desc.take(24) }))
        val job = Job(slug, a.optString("name", "").ifBlank { slug }.take(40))
        jobs[slug] = job
        worker.execute {
            try { pipeline(ctx, job, desc, isChange) }
            catch (e: Exception) { fail(job, e.message ?: e.javaClass.simpleName) }
        }
        return JSONObject().put("ok", true).put("project", slug).put("state", "running")
            .put("message", "The website is being written in the background. It opens in Chrome by itself when ready; check website_status.")
    }

    private fun uniqueSlug(ctx: Context, base: String): String {
        var s = base
        var n = 2
        while (dirOf(ctx, s).exists()) s = (base.take(36) + "-" + n++)
        return s
    }

    private fun pipeline(ctx: Context, job: Job, desc: String, isChange: Boolean) {
        val user = JSONObject().put("description", desc)
        said().let { if (it.isNotBlank()) user.put("owner_words", it) }
        var brief: JSONObject? = null
        if (!isChange) {
            say(job, "planning a professional design")
            brief = try { CodeGen.postJson(key(ctx), model(ctx), PLAN_RULES, user.toString(), 4000, 0.6, 1200) } catch (_: Exception) { null }
            brief?.let { user.put("design_brief", it) }
        }
        say(job, if (isChange) "changing the website" else "writing the website")
        if (isChange) user.put("current_files", filesJson(ctx, job.slug))
        val o = CodeGen.postJson(key(ctx), model(ctx), if (isChange) CHANGE_RULES else BUILD_RULES, user.toString(), 30000, if (isChange) 0.2 else 0.4, 4000)
        o.optString("refused", "").takeIf { it.isNotBlank() }?.let { fail(job, "Refused: $it"); return }
        val files = WebRules.filesOf(o)
        if (!isChange && files.none { it.first == "index.html" }) { fail(job, "The model did not write an index.html. Try again with a simpler description."); return }
        val applied = applyAnswer(ctx, job.slug, o)
        if (isChange && applied.changed == 0) { fail(job, "Nothing was changed" + (if (applied.problems.isNotEmpty()) ": " + applied.problems.first() else (": " + o.optString("notes", "the model made no change")))); return }
        job.notes = o.optString("notes", "") + (if (applied.problems.isNotEmpty()) " (some edits could not be applied: " + applied.problems.first() + ")" else "")
        if (!isChange) polish(ctx, job, desc, brief)
        LocalWeb.clearErrors(job.slug)
        LocalWeb.bump(job.slug)

        say(job, "opening it in Chrome")
        job.url = LocalWeb.url(ctx, job.slug)
        PhoneControl.openUrl(ctx, job.url, "chrome")
        watchAndRepair(ctx, job, "")
    }

    /** One design-review pass before the page is shown. A failed review keeps the first version. */
    private fun polish(ctx: Context, job: Job, desc: String, brief: JSONObject?) {
        say(job, "reviewing the design like a pro")
        try {
            val user = JSONObject().put("description", desc).put("current_files", filesJson(ctx, job.slug))
            brief?.let { user.put("design_brief", it) }
            val o = CodeGen.postJson(key(ctx), model(ctx), REVIEW_RULES, user.toString(), 30000, 0.3, 4000)
            val files = WebRules.filesOf(o)
            if (files.isNotEmpty()) {
                writeFiles(ctx, job.slug, files)
                o.optString("notes", "").takeIf { it.isNotBlank() }?.let { job.notes = it }
            }
        } catch (_: Exception) { }
    }

    /** Waits for Chrome to report errors; repairs and reloads until the page is clean or the rounds are used up. */
    private fun watchAndRepair(ctx: Context, job: Job, problem: String) {
        var userProblem = problem
        for (round in 1..AUTO_FIX_ROUNDS) {
            say(job, "checking the page in Chrome")
            Thread.sleep(WATCH_MS)
            val errs = LocalWeb.errorsOf(job.slug)
            if (errs.isEmpty() && userProblem.isBlank()) break
            say(job, "fixing " + (if (errs.isNotEmpty()) "${errs.size} browser error(s)" else "the problem") + " (round $round)")
            val user = JSONObject()
                .put("problem", userProblem)
                .put("chrome_errors", JSONArray(errs))
                .put("current_files", filesJson(ctx, job.slug))
            val o = CodeGen.postJson(key(ctx), model(ctx), FIX_RULES, user.toString(), 30000, 0.2, 4000)
            val applied = applyAnswer(ctx, job.slug, o)
            if (applied.changed == 0) break
            job.fixes++
            job.notes = o.optString("notes", job.notes)
            userProblem = ""
            LocalWeb.clearErrors(job.slug)
            LocalWeb.bump(job.slug)   // the open page reloads itself
        }
        job.state = "done"
        val left = LocalWeb.errorsOf(job.slug)
        say(job, if (left.isEmpty()) "ready" else "ready with ${left.size} error(s) left")
        AppBuilder.notice("[WEBSITE] \"${job.name}\" is open in Chrome" +
            (if (job.fixes > 0) " after ${job.fixes} automatic fix(es)" else "") +
            (if (left.isNotEmpty()) "; Chrome still reports: " + left.first().take(160) else "") +
            ". Tell him in one short sentence.")
    }

    private fun fail(job: Job, msg: String) {
        job.state = "failed"
        job.error = msg.take(400)
        say(job, "failed: " + msg.take(120))
        AppBuilder.notice("[WEBSITE] Building \"${job.name}\" failed: ${msg.take(160)}. Tell him briefly and offer to try again.")
    }

    // ---------------------------------------------------------------- fix / open / status / files
    private fun fix(ctx: Context, a: JSONObject): JSONObject {
        if (key(ctx).isEmpty()) return err("No API key is set. Add one on the API Key page.")
        if (jobs.values.any { it.state == "running" }) return err("Another website job is still running. Check website_status.")
        val slug = resolve(ctx, a.optString("project", ""))
        val problem = a.optString("problem", "").trim()
        if (problem.isEmpty() && LocalWeb.errorsOf(slug).isEmpty())
            return err("Chrome reported no errors for $slug. Ask the user what exactly is wrong and call again with problem.")
        val job = Job(slug, slug).apply { url = LocalWeb.url(ctx, slug) }
        jobs[slug] = job
        worker.execute {
            try {
                PhoneControl.openUrl(ctx, job.url, "chrome")
                watchAndRepair(ctx, job, problem.ifEmpty { "Fix the errors Chrome reported." })
            } catch (e: Exception) { fail(job, e.message ?: e.javaClass.simpleName) }
        }
        return JSONObject().put("ok", true).put("project", slug).put("state", "running")
            .put("message", "Fixing in the background; the page in Chrome reloads by itself.")
    }

    private fun open(ctx: Context, slug: String): JSONObject {
        val url = LocalWeb.url(ctx, slug)
        val r = PhoneControl.openUrl(ctx, url, "chrome")
        return r.put("project", slug).put("url", url)
    }

    private fun status(ctx: Context, project: String): JSONObject {
        val slug = WebRules.cleanSlug(project)
        val job = (if (slug.isNotEmpty()) jobs[slug] else null) ?: jobs.values.lastOrNull()
            ?: return JSONObject().put("state", "none").put("message", "No website job has run since NISU AI started.")
        return JSONObject().put("project", job.slug).put("state", job.state).put("stage", job.stage)
            .put("url", job.url).put("fixes", job.fixes).put("notes", job.notes).put("error", job.error)
            .put("chrome_errors", JSONArray(LocalWeb.errorsOf(job.slug).take(8)))
    }

    private fun list(ctx: Context): JSONObject {
        val arr = JSONArray()
        sites(ctx).take(30).forEach { d ->
            arr.put(JSONObject().put("project", d.name).put("files", d.walkTopDown().count { it.isFile }).put("state", jobs[d.name]?.state ?: "saved"))
        }
        return JSONObject().put("websites", arr)
    }

    private fun readOne(ctx: Context, a: JSONObject): JSONObject {
        val slug = resolve(ctx, a.optString("project", ""))
        val path = WebRules.safePath(a.optString("path")) ?: return err("Not an allowed website file path.")
        val f = File(dirOf(ctx, slug), path)
        if (!f.isFile) return err("$path does not exist in $slug.")
        val all = f.readText()
        val from = a.optInt("start", 0).coerceIn(0, all.length)
        val to = minOf(all.length, from + 12000)
        return JSONObject().put("project", slug).put("path", path).put("total_length", all.length).put("start", from).put("end", to)
            .put("content", all.substring(from, to)).put("more", to < all.length)
    }

    class AnswerResult(val changed: Int, val problems: List<String>)

    /** Writes the "files" of a model answer, then applies its "edits" to the files on disk. Edits that do not match are reported, never guessed. */
    private fun applyAnswer(ctx: Context, slug: String, o: JSONObject): AnswerResult {
        var changed = 0
        val problems = ArrayList<String>()
        val files = WebRules.filesOf(o)
        if (files.isNotEmpty()) { writeFiles(ctx, slug, files); changed += files.size }
        for (e in WebRules.editsOf(o)) {
            val f = File(dirOf(ctx, slug), e.path)
            if (!f.isFile) { problems.add("${e.path} does not exist"); continue }
            val r = WebRules.applyEdit(f.readText(), e.find, e.replace, e.all)
            if (r.text == null) { problems.add("${e.path}: ${r.error}"); continue }
            f.writeText(r.text); changed++
        }
        if (changed > 0) dirOf(ctx, slug).setLastModified(System.currentTimeMillis())
        return AnswerResult(changed, problems)
    }

    private fun editOne(ctx: Context, a: JSONObject): JSONObject {
        val slug = resolve(ctx, a.optString("project", ""))
        val path = WebRules.safePath(a.optString("path")) ?: return err("Not an allowed website file path.")
        val f = File(dirOf(ctx, slug), path)
        if (!f.isFile) return err("$path does not exist in $slug.")
        val r = WebRules.applyEdit(f.readText(), a.optString("find"), a.optString("replace"), a.optBoolean("all", false))
        val text = r.text ?: return err("Not changed: ${r.error}. Read the file again with website_read_file and copy the text exactly.")
        if (text.length > WebRules.MAX_FILE_CHARS) return err("The file would be too big.")
        f.writeText(text)
        LocalWeb.clearErrors(slug)
        LocalWeb.bump(slug)
        return JSONObject().put("ok", true).put("project", slug).put("path", path).put("removed", a.optString("replace").isEmpty()).put("new_length", text.length)
            .put("note", "The page in Chrome reloads by itself. Say it is done only after checking website_status shows no Chrome errors if it matters.")
    }

    private fun writeOne(ctx: Context, a: JSONObject): JSONObject {
        val slug = resolve(ctx, a.optString("project", ""))
        val path = WebRules.safePath(a.optString("path")) ?: return err("Not an allowed website file path.")
        val content = a.optString("content")
        if (content.length > WebRules.MAX_FILE_CHARS) return err("The file is too big.")
        val old = File(dirOf(ctx, slug), path).takeIf { it.isFile }?.length() ?: 0L
        if (old > 2000L && content.length < old * 0.5 && !a.optBoolean("allow_shrink", false))
            return err("REFUSED: the new content is much shorter than the existing file ($old characters), so a part would be lost. To remove or change a part use website_edit (find / replace). Only if he really wants the file that short, call again with allow_shrink true.")
        writeFiles(ctx, slug, listOf(path to content))
        LocalWeb.clearErrors(slug)
        LocalWeb.bump(slug)
        return JSONObject().put("ok", true).put("project", slug).put("path", path)
    }

    private fun writeFiles(ctx: Context, slug: String, files: List<Pair<String, String>>) {
        val dir = dirOf(ctx, slug).apply { mkdirs() }
        val base = dir.canonicalPath + File.separator
        for ((path, content) in files) {
            val f = File(dir, path)
            if (!f.canonicalPath.startsWith(base)) continue
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        dir.setLastModified(System.currentTimeMillis())
    }

    private fun filesJson(ctx: Context, slug: String): JSONObject {
        val o = JSONObject()
        var total = 0
        dirOf(ctx, slug).walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
            val rel = f.relativeTo(dirOf(ctx, slug)).path.replace('\\', '/')
            if (WebRules.safePath(rel) == null || total > MAX_CONTEXT_CHARS) return@forEach
            val t = f.readText()
            total += t.length
            o.put(rel, t)
        }
        return o
    }
}
