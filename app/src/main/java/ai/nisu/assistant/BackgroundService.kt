package ai.nisu.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Background Service foundation.
 *
 * A small foreground service whose only job is to keep NISU AI's process alive and to host long-running background
 * modules (see [BackgroundModule]). It is independent from [AssistantService], which still owns the microphone and the
 * Gemini Live session. Nothing here touches voice, phone control, or messaging.
 *
 * Foreground type: `specialUse` (no microphone, no media projection, no timeout on Android 15). It needs no new
 * permission: FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE and POST_NOTIFICATIONS were already declared.
 */
class NisuBackgroundService : Service() {

    companion object {
        const val ACTION_START = "ai.nisu.bg.START"
        const val ACTION_STOP = "ai.nisu.bg.STOP"
        const val CHANNEL_ID = "nisu_background"
        private const val NOTIFICATION_ID = 8
        /** The user's wish: should the service be running? Survives reboot, app update and process death. */
        private const val PREF_ENABLED = "bg_enabled"

        fun isEnabled(ctx: Context) = Prefs.getBool(ctx, PREF_ENABLED, true)   // v2.0: default ON. The reply pipeline lives in this service; a stored OFF (switch in Additional Settings) still wins

        /**
         * Starts the service. Call it from the app UI (foreground). [persist] = true remembers the user's choice so the
         * service is restored after reboot / update; restore paths pass false so a refused start never erases that choice.
         * Returns false (and fills [BackgroundState.lastError]) when Android refused the start.
         */
        fun start(ctx: Context, persist: Boolean = true): Boolean {
            if (!PremiumGate.isUnlocked(ctx)) return false
            if (persist) Prefs.putBool(ctx, PREF_ENABLED, true)
            return try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, NisuBackgroundService::class.java).setAction(ACTION_START))
                true
            } catch (e: Exception) {
                // e.g. Android 12+ ForegroundServiceStartNotAllowedException when the app is in the background
                BackgroundState.lastError.value = "Android did not allow the background service to start: " + (e.message ?: e.javaClass.simpleName).take(120)
                AutoReplyLog.init(ctx)
                AutoReplyLog.add("service", "start_refused_" + e.javaClass.simpleName.take(40), true)
                if (persist) Prefs.putBool(ctx, PREF_ENABLED, false)
                false
            }
        }

        /** Stops the service and remembers that the user does not want it. */
        fun stop(ctx: Context) {
            Prefs.putBool(ctx, PREF_ENABLED, false)
            ctx.stopService(Intent(ctx, NisuBackgroundService::class.java))
        }

        /** Boot / app-update / app-open path: starts the service only if the user turned it on and it is not already running. */
        fun restoreIfEnabled(ctx: Context) {
            if (!isEnabled(ctx) || BackgroundState.running.value) return
            start(ctx, persist = false)
        }
    }

    private var scope: CoroutineScope? = null
    private val jobs = HashMap<String, Job>()
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP && !PremiumGate.isUnlocked(this)) {
            shutdown()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.putBool(this, PREF_ENABLED, false)
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_START -> { /* continue below */ }
            else -> {
                // intent == null: Android recreated the service after the process was killed (START_STICKY).
                // Only come back if the user still wants the service.
                if (!isEnabled(this)) { stopSelf(); return START_NOT_STICKY }
                if (!active) BackgroundState.restarts.value = BackgroundState.restarts.value + 1
            }
        }
        // Must happen right away after startForegroundService().
        if (!enterForeground()) { shutdown(); return START_NOT_STICKY }
        if (!active) begin()
        return START_STICKY
    }

    private fun begin() {
        active = true
        BackgroundState.lastError.value = null
        BackgroundState.moduleErrors.value = emptyMap()
        BackgroundState.startedAt.value = System.currentTimeMillis()
        BackgroundState.running.value = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        val env = BackgroundEnv(applicationContext, s)
        for (m in BackgroundModules.create(applicationContext)) {
            jobs[m.id] = s.launch {
                setModule(m.id, true)
                try {
                    m.run(env)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // One failing module never takes the service or the other modules down.
                    BackgroundState.moduleErrors.update { it + (m.id to (e.message ?: e.javaClass.simpleName)) }
                } finally {
                    setModule(m.id, false)
                }
            }
        }
        refreshNotification()
    }

    private fun setModule(id: String, on: Boolean) {
        BackgroundState.modules.update { if (on) (it + id).distinct() else it - id }
        refreshNotification()
    }

    /** Cancels every module and resets the shared state. Safe to call more than once. */
    private fun teardown() {
        active = false
        scope?.cancel(); scope = null
        jobs.clear()
        BackgroundState.modules.value = emptyList()
        BackgroundState.running.value = false
        BackgroundState.startedAt.value = 0L
    }

    private fun shutdown() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Also reached when the app UI calls stopService() and when Android destroys the service.
        teardown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun enterForeground(): Boolean {
        return try {
            ensureChannel()
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, n)
            updateNotificationHealth()
            true
        } catch (e: Exception) {
            BackgroundState.lastError.value = "Android did not allow the background service to run: " + (e.message ?: e.javaClass.simpleName).take(120)
            false
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "NISU AI background service", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shown while NISU AI's background service is running."
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(this, 11, Intent(this, NisuBackgroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 12, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val count = BackgroundState.modules.value.size
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NISU AI background service")
            .setContentText(if (count == 0) "Running. No background tasks active." else "Running. $count background module(s) active.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setWhen(BackgroundState.startedAt.value.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setUsesChronometer(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun refreshNotification() {
        if (!active) return
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) {}
        updateNotificationHealth()
    }

    /** The service keeps running when notifications are blocked; say so honestly instead of pretending the user can see it. */
    private fun updateNotificationHealth() {
        val nm = getSystemService(NotificationManager::class.java)
        val channelOff = nm.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        BackgroundState.notificationVisible.value = nm.areNotificationsEnabled() && !channelOff
    }
}

/** Real, process-wide state of the background service. Nothing here is a placeholder: it only changes when the service changes. */
object BackgroundState {
    val running = MutableStateFlow(false)
    /** Epoch millis when this run started; 0 while stopped. */
    val startedAt = MutableStateFlow(0L)
    /** Ids of the modules that are currently running inside the service. */
    val modules = MutableStateFlow<List<String>>(emptyList())
    /** Module id -> message of the exception that ended it. */
    val moduleErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** How many times Android recreated the service after killing it (this process only). */
    val restarts = MutableStateFlow(0)
    /** False when notifications (or this channel) are blocked: the service still runs, but its notification is hidden. */
    val notificationVisible = MutableStateFlow(true)
    val lastError = MutableStateFlow<String?>(null)
}

/** What a module gets from the service. [scope] is cancelled when the service stops. */
class BackgroundEnv(val context: Context, val scope: CoroutineScope)

/**
 * A long-running unit of work hosted by [NisuBackgroundService]. [run] suspends for as long as the module is alive and
 * must be cooperative with cancellation (the service cancels it on stop). An exception ends only that module.
 *
 * The Main Brain is one such module ([MainBrainModule]). The shared task queue (TaskManager.kt) runs inside the Main Brain module, so it needs no module of its own; other modules plug in here the same way;
 * the WhatsApp / Messenger Master Agents are not modules: they register with the Main Brain's AgentManager (see AgentCatalog).
 */
interface BackgroundModule {
    val id: String
    suspend fun run(env: BackgroundEnv)
}

/** The single place that lists the modules the service hosts. The Main Brain (MainBrain.kt) hosts the agents (AgentCatalog: WhatsApp and Messenger Master Agents). */
object BackgroundModules {
    fun create(@Suppress("UNUSED_PARAMETER") ctx: Context): List<BackgroundModule> = listOf(MessageAccessModule(), PhoneControlWatchdogModule(), MainBrainModule())
}

/**
 * Keeps an eye on Notification Access while the background service runs: notices at once when the user grants or revokes
 * it, and when access is granted but Android has not connected the listener, asks Android to reconnect (growing delay,
 * at most every 5 minutes). It never reads a notification itself: that is done by [NisuNotificationService].
 */
class MessageAccessModule : BackgroundModule {
    override val id = "notification-listener"

    override suspend fun run(env: BackgroundEnv) {
        val ctx = env.context
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { MessageAccess.refresh(ctx) }
        }
        ctx.contentResolver.registerContentObserver(android.provider.Settings.Secure.getUriFor("enabled_notification_listeners"), false, observer)
        try {
            MessageProcessor.start(ctx)   // message queue is running whenever the background service is
            MessageAccess.refresh(ctx)
            MessageAccess.state.collectLatest { st ->
                if (st != MessageAccess.State.WAITING) return@collectLatest
                var wait = 5_000L
                while (true) {   // cancelled by collectLatest as soon as the state changes, or when the service stops
                    MessageAccess.requestRebind(ctx)
                    delay(wait)
                    MessageAccess.refresh(ctx)
                    if (MessageAccess.state.value != MessageAccess.State.WAITING) break
                    wait = minOf(wait * 2, 300_000L)
                }
            }
        } finally {
            try { ctx.contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {}
        }
    }
}

/**
 * Keeps phone control alive while the background service runs: warms the app list (so the first "open ..." is fast) and, when
 * NISU AI is switched on in Accessibility but Android has not connected it, reconnects it (see [PhoneControl.reviveAccessibility]).
 * Checks every 20 s; when a reconnect is not possible (no root, no secure-settings permission) it backs off up to 5 minutes.
 */
class PhoneControlWatchdogModule : BackgroundModule {
    override val id = "phone-control-watchdog"

    override suspend fun run(env: BackgroundEnv) {
        val ctx = env.context
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { PhoneControl.warmAppCache(ctx) } catch (_: Throwable) {} }
        var misses = 0
        while (true) {
            delay(if (misses == 0) 20_000L else minOf(20_000L shl minOf(misses, 4), 300_000L))
            val down = NisuAccessibilityService.inst == null && PhoneControl.accEnabledInSettings(ctx)
            if (!down) { misses = 0; continue }
            val ok = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PhoneControl.reviveAccessibility(ctx) } } catch (_: Throwable) { false }
            misses = if (ok) 0 else misses + 1
        }
    }
}

/** Restores the service after a reboot or an app update, but only if the user had turned it on. */
class BackgroundBootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        NisuBackgroundService.restoreIfEnabled(c)
    }
}
