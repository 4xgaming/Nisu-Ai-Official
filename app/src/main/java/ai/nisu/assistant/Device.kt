package ai.nisu.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

/** Things a normal phone user can do, plus a root-powered self-setup that grants NISU AI its own permissions. */
object Device {
    fun findContact(ctx: Context, q: String): JSONObject {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return JSONObject().put("error", "Contacts permission is missing. Run setup_self (needs root) or ask the user to allow it.")
        val out = JSONArray()
        ctx.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null)?.use { c ->
            while (c.moveToNext() && out.length() < 8)
                out.put(JSONObject().put("name", c.getString(0)).put("number", c.getString(1)))
        }
        return JSONObject().put("contacts", out)
    }

    fun sendSms(ctx: Context, number: String, text: String): JSONObject {
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return JSONObject().put("error", "SMS permission is missing. Run setup_self (needs root) or ask the user to allow it.")
        @Suppress("DEPRECATION")
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
        return JSONObject().put("ok", true).put("sent_to", number)
    }

    private val RUNTIME = listOf("READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE",
        "SEND_SMS", "READ_SMS", "RECEIVE_SMS", "READ_CALENDAR", "WRITE_CALENDAR", "CAMERA", "RECORD_AUDIO",
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "READ_PHONE_STATE", "POST_NOTIFICATIONS",
        "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
        "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "WRITE_SECURE_SETTINGS")

    /** With root: grant NISU AI every permission it declares, exempt it from battery limits, and switch on its accessibility service. */
    fun setupSelf(ctx: Context): JSONObject {
        val pkg = ctx.packageName
        val comp = "$pkg/ai.nisu.assistant.NisuAccessibilityService"
        val sb = StringBuilder()
        RUNTIME.forEach { sb.append("pm grant $pkg android.permission.$it 2>/dev/null; ") }
        listOf("SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS", "MANAGE_EXTERNAL_STORAGE", "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "REQUEST_INSTALL_PACKAGES", "READ_CLIPBOARD")
            .forEach { sb.append("appops set $pkg $it allow 2>/dev/null; ") }
        sb.append("pm grant $pkg com.termux.permission.RUN_COMMAND 2>/dev/null; ")
        sb.append("cmd notification allow_listener $pkg/ai.nisu.assistant.NisuNotificationService 2>/dev/null; ")
        sb.append("cmd notification allow_dnd $pkg 2>/dev/null; ")
        sb.append("dumpsys deviceidle whitelist +$pkg 2>/dev/null; ")
        sb.append("cur=\$(settings get secure enabled_accessibility_services); ")
        sb.append("case \"\$cur\" in *$comp*) ;; null|'') settings put secure enabled_accessibility_services $comp;; ")
        sb.append("*) settings put secure enabled_accessibility_services \"\$cur:$comp\";; esac; ")
        sb.append("settings put secure accessibility_enabled 1; ")
        sb.append("echo granted_count=\$(dumpsys package $pkg | grep -c 'granted=true')")
        val (code, out) = Root.exec(sb.toString(), 40000)
        // Android connects the accessibility service a moment after the setting changes: wait for it instead of guessing
        val end = System.currentTimeMillis() + 6000
        while (NisuAccessibilityService.inst == null && System.currentTimeMillis() < end) { try { Thread.sleep(250) } catch (_: InterruptedException) { break } }
        return JSONObject().put("ok", code == 0).put("exit_code", code).put("result", out.trim())
            .put("accessibility_connected", NisuAccessibilityService.inst != null)
            .put("status", PhoneControl.status(ctx, rootKnown = true))
            .put("note", "Report what status says is still missing; do not claim a permission was granted unless status shows it.")
    }
}
