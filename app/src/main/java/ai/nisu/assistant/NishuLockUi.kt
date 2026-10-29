package ai.nisu.assistant

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared input for a lock secret, used by the lock screen and by the settings page:
 * pattern grid, PIN keypad or password field. It only hands the typed secret to [onSubmit];
 * checking is done by [LockCredentialManager]. Change [resetKey] to clear what was typed.
 */
@Composable
fun CredentialInput(type: LockType, enabled: Boolean, error: Boolean, resetKey: Int, okLabel: String, onSubmit: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when (type) {
            LockType.PATTERN -> PatternLockView(Modifier.fillMaxWidth().widthIn(max = 300.dp), enabled = enabled, error = error) { onSubmit(patternToSecret(it)) }
            LockType.PIN -> PinPad(enabled, error, resetKey, onSubmit)
            LockType.PASSWORD -> PasswordEntry(enabled, resetKey, okLabel, onSubmit)
            LockType.SWIPE -> {}
        }
    }
}

@Composable
private fun PinPad(enabled: Boolean, error: Boolean, resetKey: Int, onSubmit: (String) -> Unit) {
    var pin by remember(resetKey) { mutableStateOf("") }
    Text(
        if (pin.isEmpty()) " " else "\u2022".repeat(pin.length),
        fontSize = 28.sp, color = if (error && pin.isEmpty()) NisuUi.Red else NisuUi.Text
    )
    Spacer(Modifier.height(10.dp))
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("DEL", "0", "OK"))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (k in row) {
                    KeyButton(if (k == "DEL") "\u232B" else k, enabled) {
                        when (k) {
                            "DEL" -> pin = pin.dropLast(1)
                            "OK" -> if (pin.isNotEmpty()) onSubmit(pin)
                            else -> if (pin.length < LockCredentialManager.MAX_PIN) pin += k
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(NisuUi.Card).border(1.dp, NisuUi.Line, CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 20.sp, color = if (enabled) NisuUi.Text else NisuUi.Muted) }
}

@Composable
private fun PasswordEntry(enabled: Boolean, resetKey: Int, okLabel: String, onSubmit: (String) -> Unit) {
    var pw by remember(resetKey) { mutableStateOf("") }
    OutlinedTextField(
        value = pw, onValueChange = { pw = it }, singleLine = true, enabled = enabled, label = { Text("Password") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp), shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NisuUi.Cyan, unfocusedBorderColor = NisuUi.Line,
            focusedTextColor = NisuUi.Text, unfocusedTextColor = NisuUi.Text,
            cursorColor = NisuUi.Cyan, focusedLabelColor = NisuUi.Cyan, unfocusedLabelColor = NisuUi.Muted
        )
    )
    Spacer(Modifier.height(10.dp))
    MChip(okLabel, enabled = enabled && pw.isNotEmpty()) { onSubmit(pw) }
}

// ====================================================================================================== settings page

/** Nishu Settings > Security / Custom Lock. Every change of an existing lock asks for the current credential first. */
@Composable
fun LockSettingsPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf("IDLE") }          // IDLE, VERIFY, PICK, ENTER, CONFIRM
    var pendingType by remember { mutableStateOf(LockType.PIN) }
    var first by remember { mutableStateOf("") }
    var afterVerify by remember { mutableStateOf<(() -> Unit)?>(null) }
    var msg by remember { mutableStateOf("") }
    var resetKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val lockState by LockStateManager.state.collectAsState()

    val enabled = remember(rev) { LockCredentialManager.isEnabled(ctx) }
    val type = remember(rev) { LockCredentialManager.type(ctx) }
    val fpSupported = remember(rev) { BiometricUnlockManager.fingerprintSupported(ctx) }
    val faceSupported = remember(rev) { BiometricUnlockManager.faceSupported(ctx) }
    val fpOn = remember(rev) { LockCredentialManager.fingerprintOn(ctx) }
    val faceOn = remember(rev) { LockCredentialManager.faceOn(ctx) }

    fun requireAuth(action: () -> Unit) {
        if (!LockCredentialManager.isEnabled(ctx) || LockCredentialManager.type(ctx) == LockType.SWIPE || !LockCredentialManager.hasCredential(ctx)) action()
        else { afterVerify = action; msg = ""; resetKey++; step = "VERIFY" }
    }

    fun saveLock(t: LockType, secret: String?) {
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.Default) { LockCredentialManager.save(ctx, t, secret) }
            busy = false
            if (ok) LockStateManager.unlocked(ctx)   // you are using NISU AI right now: it stays open until the screen goes off
            msg = if (ok) "Lock saved: ${t.label}." else "The lock could not be saved."
            rev++; step = "IDLE"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Security / Custom Lock", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))

        when (step) {
            "VERIFY" -> LockPanel {
                Text("Enter your current ${type.label}", fontSize = 15.sp, color = NisuUi.Cyan)
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = NisuUi.Red)
                CredentialInput(type, enabled = !busy, error = msg.isNotEmpty(), resetKey = resetKey, okLabel = "Confirm") { s ->
                    scope.launch {
                        busy = true
                        val r = withContext(Dispatchers.Default) { LockCredentialManager.verify(ctx, s) }
                        busy = false
                        when (r) {
                            is VerifyResult.Ok -> { val a = afterVerify; afterVerify = null; msg = ""; step = "IDLE"; a?.invoke() }
                            is VerifyResult.Wrong -> { msg = "Wrong. ${r.attemptsBeforeLockout} tries left before a pause."; resetKey++ }
                            is VerifyResult.LockedOut -> { msg = "Too many wrong tries. Wait ${(r.remainingMs + 999) / 1000} s."; resetKey++ }
                            is VerifyResult.NotConfigured -> { LockCredentialManager.remove(ctx); rev++; msg = "The stored lock could not be read, so it was switched off."; step = "IDLE" }
                        }
                    }
                }
                MChip("Cancel", NisuUi.Muted) { afterVerify = null; msg = ""; step = "IDLE" }
            }

            "PICK" -> LockPanel {
                Text("Choose lock type", fontSize = 15.sp, color = NisuUi.Cyan)
                Text("Swipe has no secret. Pattern, PIN and Password protect NISU AI with a secret that is stored only as a salted hash.", fontSize = 12.sp, color = NisuUi.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MChip("Swipe") { saveLock(LockType.SWIPE, null) }
                    MChip("Pattern") { pendingType = LockType.PATTERN; msg = ""; resetKey++; step = "ENTER" }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MChip("PIN") { pendingType = LockType.PIN; msg = ""; resetKey++; step = "ENTER" }
                    MChip("Password") { pendingType = LockType.PASSWORD; msg = ""; resetKey++; step = "ENTER" }
                }
                MChip("Cancel", NisuUi.Muted) { step = "IDLE" }
            }

            "ENTER", "CONFIRM" -> LockPanel {
                val confirming = step == "CONFIRM"
                Text(
                    (if (confirming) "Confirm your " else "Set a new ") + pendingType.label.lowercase(),
                    fontSize = 15.sp, color = NisuUi.Cyan
                )
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = NisuUi.Red)
                CredentialInput(pendingType, enabled = !busy, error = msg.isNotEmpty(), resetKey = resetKey, okLabel = if (confirming) "Save" else "Next") { s ->
                    if (!confirming) {
                        val e = LockCredentialManager.validate(pendingType, s)
                        if (e != null) { msg = e; resetKey++ } else { first = s; msg = ""; resetKey++; step = "CONFIRM" }
                    } else if (s == first) {
                        first = ""; saveLock(pendingType, s)
                    } else {
                        first = ""; msg = "They did not match. Start again."; resetKey++; step = "ENTER"
                    }
                }
                MChip("Cancel", NisuUi.Muted) { first = ""; msg = ""; step = "IDLE" }
            }

            else -> {
                LockPanel {
                    Text("Custom Lock", fontSize = 15.sp, color = NisuUi.Cyan)
                    Text("NISU AI's own lock. It is shown when the screen turns on and guards NISU AI's screens. The background service, automation, notification processing, root features and the AI session keep running while it is locked or the screen is off.", fontSize = 12.sp, color = NisuUi.Muted)
                    LockSwitchRow("Enable Custom Lock", enabled) { on ->
                        if (on) { msg = ""; step = "PICK" }
                        else requireAuth { LockCredentialManager.remove(ctx); msg = "Custom lock is off."; rev++ }
                    }
                    if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = NisuUi.Green)
                }

                if (enabled) {
                    LockPanel {
                        Text("Lock type: ${type.label}", fontSize = 15.sp, color = NisuUi.Cyan)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MChip("Change lock") { requireAuth { msg = ""; step = "PICK" } }
                            MChip("Lock now", NisuUi.Amber) { NishuLockManager.lockNow(ctx) }
                        }
                        Text("State: ${when (lockState) { LockState.UNLOCKED -> "unlocked"; LockState.LOCKED -> "locked"; LockState.SCREEN_OFF -> "screen off" }}. Wrong tries since the last success: ${LockCredentialManager.failedAttempts(ctx)}.", fontSize = 12.sp, color = NisuUi.Muted)
                    }

                    LockPanel {
                        Text("Fingerprint / Face", fontSize = 15.sp, color = NisuUi.Cyan)
                        if (type == LockType.SWIPE) {
                            Text("Biometric unlock needs a Pattern, PIN or Password lock as the main method.", fontSize = 12.sp, color = NisuUi.Muted)
                        } else if (!fpSupported && !faceSupported) {
                            Text("This device does not offer a supported fingerprint or face unlock (or none is enrolled in Android settings).", fontSize = 12.sp, color = NisuUi.Muted)
                        } else {
                            if (fpSupported) LockSwitchRow("Fingerprint unlock", fpOn) { on ->
                                if (on) requireAuth { LockCredentialManager.setFingerprint(ctx, true); rev++ } else { LockCredentialManager.setFingerprint(ctx, false); rev++ }
                            }
                            if (faceSupported) LockSwitchRow("Face unlock", faceOn) { on ->
                                if (on) requireAuth { LockCredentialManager.setFace(ctx, true); rev++ } else { LockCredentialManager.setFace(ctx, false); rev++ }
                            }
                            Text("Android shows its own biometric prompt and only tells NISU AI whether it succeeded. The PIN / pattern / password always stays available.", fontSize = 12.sp, color = NisuUi.Muted)
                        }
                    }

                    if (!NishuLockManager.canShowFromBackground(ctx)) {
                        LockPanel {
                            Text("Allow \"Display over other apps\"", fontSize = 15.sp, color = NisuUi.Amber)
                            Text("Without it Android 10+ may not let NISU AI open the lock screen by itself when the screen turns on. NISU AI's own screens are still guarded.", fontSize = 12.sp, color = NisuUi.Muted)
                            MChip("Allow") {
                                try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                            }
                        }
                    }
                }

                LockPanel {
                    val adminActive by remember(rev) { mutableStateOf(NishuDeviceAdmin.isActive(ctx)) }
                    Text("Uninstall Protection", fontSize = 15.sp, color = NisuUi.Cyan)
                    Text(
                        if (adminActive) "On. NISU AI cannot be uninstalled from the Home screen or App info until this is turned off."
                        else "Off. Anyone can uninstall NISU AI like any other app.",
                        fontSize = 12.sp, color = NisuUi.Muted
                    )
                    if (type == LockType.SWIPE) {
                        Text("Set a Pattern, PIN or Password above first, so turning this off needs that secret.", fontSize = 12.sp, color = NisuUi.Amber)
                    } else if (adminActive) {
                        MChip("Turn off", NisuUi.Amber) {
                            requireAuth {
                                NishuDeviceAdmin.deactivate(ctx)
                                rev++
                            }
                        }
                    } else {
                        MChip("Turn on") {
                            try { ctx.startActivity(NishuDeviceAdmin.requestIntent(ctx)) } catch (_: Throwable) {}
                        }
                    }
                }

                LockPanel {
                    Text("Limits", fontSize = 15.sp, color = NisuUi.Cyan)
                    Text("This is a software lock inside NISU AI. It is not the Android Keyguard and is not as strong as it: Android's own lock, verified boot, device encryption and the hardware-backed biometric checks stay in charge of the phone. After 5 wrong tries the lock pauses (30 s, doubling, up to 30 min).", fontSize = 12.sp, color = NisuUi.Muted)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LockPanel(content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) { content() }
    }
}

@Composable
private fun LockSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = NisuUi.Blue,
                uncheckedThumbColor = NisuUi.Muted, uncheckedTrackColor = NisuUi.Card, uncheckedBorderColor = NisuUi.Line
            )
        )
    }
}
