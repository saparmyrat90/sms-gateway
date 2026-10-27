package com.gateway.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var settings: SettingsStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground before anything else, or Android kills the app.
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(Gateway.store.status.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // intent == null: process was killed and the system restarted us (START_STICKY).
        if (intent == null && !settings.load().enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loopJob == null) startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        val s = settings.load()
        val api = try {
            OkHttpGatewayApi(s.serverUrl, s.gatewayKey)
        } catch (e: ApiException) {
            Gateway.store.setError(e.message)
            settings.setEnabled(false)
            stopSelf()
            return
        }

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsgateway:loop")
            .apply { acquire() } // held until the service stops, by design

        val loop = GatewayLoop(
            api = api,
            sender = AndroidSmsSender(this),
            store = Gateway.store,
            // Re-read settings every iteration so slot / cap changes apply without a restart.
            config = {
                val cur = settings.load()
                LoopConfig(
                    idleDelayMs = cur.pollIntervalSec.coerceAtLeast(1) * 1_000L,
                    maxPerMinute = cur.maxPerMinute.coerceAtLeast(1),
                    simSlot = cur.simSlot,
                )
            },
        )
        loopJob = scope.launch { loop.run() }
        scope.launch {
            Gateway.store.status.collect { st ->
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(st))
            }
        }
    }

    private fun buildNotification(st: GatewayStatus): Notification {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        val last = st.lastSmsAt?.let { "last SMS ${time.format(Date(it))}" } ?: "no SMS yet"
        val text = st.lastError?.let { "$last · error: $it" } ?: last
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (st.running) "SMS gateway running" else "SMS gateway starting")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Gateway status", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        Gateway.store.setRunning(false)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gateway_status"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
        }

        fun stop(context: Context) {
            SettingsStore(context).setEnabled(false)
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
}
