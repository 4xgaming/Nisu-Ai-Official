package ai.nisu.assistant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The NISU AI lock screen: lightweight, full screen, shown above the Android Keyguard when the screen turns on
 * while the lock is closed. Back is disabled. It does not touch any service.
 */
class LockScreenActivity : ComponentActivity() {
    companion object {
        /** true while the lock screen is the resumed screen: the accessibility lock gate must not act then (fingerprint prompt, keyboard). */
        @Volatile var visible = false
    }
    private var bioSignal: CancellationSignal? = null
    private var bioRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)   // no screenshots / recents thumbnail of the lock screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* stays locked */ }
        })
        hideSystemBars()
        Thread { LockCredentialManager.warmUp() }.start()   // first unlock must not wait for the Keystore / hash code to load
        setContent { NisuTheme { LockScreen() } }
    }

    /** Immersive: no status bar on the lock screen, so the notification / quick-settings panel is much harder to pull down. */
    private fun hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
        } catch (_: Throwable) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (!LockStateManager.isLocked(this)) { visible = false; finish(); return }
        NisuAccessibilityService.inst?.dropCover()
        startBiometricIfWanted()
    }

    override fun onPause() {
        visible = false
        cancelBiometric()
        super.onPause()
    }

    private fun cancelBiometric() {
        try { bioSignal?.cancel() } catch (_: Throwable) {}
        bioSignal = null; bioRunning = false
    }

    fun startBiometricIfWanted() {
        if (bioRunning || !BiometricUnlockManager.wanted(this)) return
        bioRunning = true
        bioSignal = BiometricUnlockManager.authenticate(this,
            onSuccess = { bioRunning = false; LockCredentialManager.resetFailures(this); unlock() },
            onDone = { _ -> bioRunning = false })
        if (bioSignal == null) bioRunning = false
    }

    override fun onDestroy() { visible = false; super.onDestroy() }

    private fun unlock() {
        if (isFinishing || isDestroyed) return            // a duplicate / late callback (second biometric result, double tap)
        if (!LockStateManager.unlocked(this)) return      // screen is off: a late callback must not open the lock
        NisuAccessibilityService.inst?.dropCover()      // remove the cover at once
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)   // no close animation = instant
    }

    @Composable
    private fun LockScreen() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val type = remember { LockCredentialManager.type(ctx) }
        val bio = remember { BiometricUnlockManager.wanted(ctx) }
        val state by LockStateManager.state.collectAsState()
        var message by remember { mutableStateOf("") }
        var error by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var resetKey by remember { mutableIntStateOf(0) }
        var lockLeft by remember { mutableLongStateOf(LockCredentialManager.lockoutRemainingMs(ctx)) }
        var lockKey by remember { mutableIntStateOf(0) }
        var clock by remember { mutableStateOf(nowTexts()) }
        var battery by remember { mutableStateOf(batteryText(ctx)) }

        LaunchedEffect(Unit) { while (true) { clock = nowTexts(); battery = batteryText(ctx); delay(5000) } }
        LaunchedEffect(lockKey) {
            while (true) {
                val l = LockCredentialManager.lockoutRemainingMs(ctx)
                lockLeft = l
                if (l <= 0L) break
                delay(500)
            }
        }
        LaunchedEffect(state) { if (state == LockState.UNLOCKED && !LockStateManager.isLocked(ctx)) finish() }

        fun submit(secret: String) {
            if (busy || lockLeft > 0L) return
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.Default) { LockCredentialManager.verify(ctx, secret) }
                busy = false
                when (r) {
                    is VerifyResult.Ok -> unlock()
                    is VerifyResult.Wrong -> { error = true; message = "Wrong ${type.label.lowercase()}. ${r.attemptsBeforeLockout} tries left before a pause."; resetKey++ }
                    is VerifyResult.LockedOut -> { error = true; message = ""; resetKey++; lockKey++ }
                    is VerifyResult.NotConfigured -> {
                        // the stored credential cannot be read any more: switch the NISU lock off instead of locking the user out of the app for good
                        LockCredentialManager.remove(ctx)
                        unlock()
                    }
                }
            }
        }

        val scrollMod = if (type == LockType.PASSWORD) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().then(scrollMod).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("NISU AI", color = NisuUi.Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
            Spacer(Modifier.height(18.dp))
            Text(clock.first, fontSize = 56.sp, fontWeight = FontWeight.Light, color = NisuUi.Text)
            Text(clock.second, fontSize = 15.sp, color = NisuUi.Muted)
            Spacer(Modifier.height(6.dp))
            Text(battery, fontSize = 13.sp, color = NisuUi.Muted)
            Spacer(Modifier.height(4.dp))
            Text(if (state == LockState.SCREEN_OFF) "Screen off" else "Locked", fontSize = 12.sp, color = NisuUi.Muted)
            Spacer(Modifier.height(28.dp))

            val status = when {
                lockLeft > 0L -> "Too many wrong tries. Try again in ${(lockLeft + 999) / 1000} s."
                else -> message
            }
            if (status.isNotEmpty()) {
                Text(status, fontSize = 13.sp, color = if (error) NisuUi.Red else NisuUi.Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
            }

            if (type == LockType.SWIPE) {
                SwipeToUnlock { unlock() }
            } else {
                CredentialInput(type, enabled = !busy && lockLeft <= 0L, error = error, resetKey = resetKey, okLabel = "Unlock") { submit(it) }
            }

            if (bio) {
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (LockCredentialManager.fingerprintOn(ctx) && BiometricUnlockManager.fingerprintSupported(ctx)) {
                        Box(
                            Modifier.size(56.dp).clip(CircleShape).border(1.dp, NisuUi.Cyan.copy(alpha = 0.6f), CircleShape).clickable { startBiometricIfWanted() },
                            contentAlignment = Alignment.Center
                        ) { FingerprintGlyph(NisuUi.Cyan) }
                    }
                    if (LockCredentialManager.faceOn(ctx) && BiometricUnlockManager.faceSupported(ctx)) {
                        MChip("Face unlock") { startBiometricIfWanted() }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            MChip("Emergency call", NisuUi.Amber) {
                try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
            }
        }
    }

    private fun nowTexts(): Pair<String, String> {
        val d = Date()
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(d) to DateFormat.getDateInstance(DateFormat.FULL).format(d)
    }

    private fun batteryText(c: Context): String {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return ""
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0 || scale <= 0) return ""
        return "Battery ${level * 100 / scale}%" + if (charging) " (charging)" else ""
    }
}

/** Swipe up to open: the "basic" lock, no secret. */
@Composable
fun SwipeToUnlock(onUnlock: () -> Unit) {
    var drag by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().height(150.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (drag < -140f) onUnlock(); drag = 0f },
                    onDragCancel = { drag = 0f },
                    onVerticalDrag = { _, amount -> drag += amount }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(44.dp).height(5.dp).clip(CircleShape).background(NisuUi.Cyan))
            Spacer(Modifier.height(10.dp))
            Text("Swipe up to unlock", color = NisuUi.Muted, fontSize = 14.sp)
        }
    }
}

/** Small drawn fingerprint mark (concentric arcs); no icon library needed. */
@Composable
fun FingerprintGlyph(color: Color) {
    Canvas(Modifier.size(30.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        for (k in 1..4) {
            val r = size.width * 0.11f * k
            drawArc(color, startAngle = 200f, sweepAngle = 280f, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        }
    }
}
