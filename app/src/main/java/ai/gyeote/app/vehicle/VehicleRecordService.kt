package ai.gyeote.app.vehicle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import ai.gyeote.app.AppController
import ai.gyeote.app.MainActivity
import ai.gyeote.app.R

/** One controller for the process. Only application context and an attached screen callback are retained. */
internal object VehicleRecordRuntime {
    data class Config(val forceFake: Boolean = false, val publicTelltalesOnly: Boolean = false, val testProfile: String? = null)
    private var session: Session? = null

    fun obtain(context: Context, config: Config): Session {
        val existing = session
        if (existing != null && existing.config == config) return existing
        existing?.close()
        return Session(context.applicationContext, config).also { session = it }
    }

    fun current(): Session? = session

    class Session(private val context: Context, val config: Config) {
        private var closed = false
        private var recording = false
        private val lifecycle: RecordLifecycle = RecordLifecycle(
            startService = { context.startForegroundService(Intent(context, VehicleRecordService::class.java)) },
            stopService = {
                controller.stopRecording()
                context.stopService(Intent(context, VehicleRecordService::class.java))
            },
        )
        // SDK callbacks during construction must not read the controller before it is assigned.
        private var initialized = false
        val controller: AppController = AppController(context, config.forceFake, config.publicTelltalesOnly, config.testProfile) {
            if (initialized && !closed) {
                lifecycle.updateMode(controllerMode())
                lifecycle.changed()
            }
        }
        init { initialized = true }
        private fun controllerMode() = controller.handoverSettings.mode

        fun attachScreen(callback: () -> Unit): Subscription {
            check(!closed)
            controller.start()
            val screen = lifecycle.attach(controllerMode(), callback)
            vehicleLog("recordScreen attached")
            return Subscription {
                screen.cancel()
                if (!lifecycle.screenAttached) {
                    controller.stop()
                    vehicleLog("recordScreen detached")
                    releaseIfIdle()
                }
            }
        }

        fun startRecording(): Boolean {
            if (closed || !controllerMode().cardOn) return false
            recording = true
            controller.startRecording()
            return true
        }

        fun serviceStopped() {
            if (closed) return
            recording = false
            lifecycle.serviceStopped()
            controller.stopRecording()
            releaseIfIdle()
        }

        private fun releaseIfIdle() {
            if (!recording && !lifecycle.serviceRequested && !lifecycle.screenAttached) {
                close()
                if (session === this) session = null
            }
        }

        fun close() {
            if (closed) return
            closed = true
            context.stopService(Intent(context, VehicleRecordService::class.java))
            controller.close()
        }
    }
}

/** User-opened recording only: no boot receiver, no sticky restart, no background Activity launch. */
class VehicleRecordService : Service() {
    private var owner: VehicleRecordRuntime.Session? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "차 상태 기록", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val next = VehicleRecordRuntime.current()
        if (next == null || !next.controller.handoverSettings.mode.cardOn) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gyeote)
            .setContentTitle("Passer")
            .setContentText("Passer가 차 상태를 기록하고 있어요")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification)
        // Debug configuration switches can replace the owner while the same Service instance is alive.
        if (owner !== next) owner?.serviceStopped()
        owner = next
        if (!next.startRecording()) stopSelf(startId)
        vehicleLog("recordService foreground")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        owner?.serviceStopped()
        owner = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        vehicleLog("recordService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "vehicle_record"
        const val NOTIFICATION = 8
    }
}
