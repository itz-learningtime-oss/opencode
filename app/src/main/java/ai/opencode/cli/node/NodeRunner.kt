package ai.opencode.cli.node

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.opencode.cli.R
import ai.opencode.cli.settings.OpenCodeSettings

class NodeRunner : Service(), NodeExecutor.Listener {

    inner class LocalBinder : Binder() {
        fun executor(): NodeExecutor = executor
    }

    private val binder = LocalBinder()
    private lateinit var executor: NodeExecutor
    @Volatile
    private var client: NodeExecutor.Listener? = null

    override fun onCreate() {
        super.onCreate()
        executor = NodeExecutor(this, OpenCodeSettings(this))
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("OpenCode CLI running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!executor.isRunning()) {
            executor.start(this)
        }
        return START_STICKY
    }

    fun attach(listener: NodeExecutor.Listener) {
        client = listener
    }

    fun detach() {
        client = null
    }

    override fun onOutput(data: ByteArray) {
        client?.onOutput(data)
    }

    override fun onExit(code: Int) {
        client?.onExit(code)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStatus(message: String) {
        client?.onStatus(message)
    }

    override fun onDestroy() {
        executor.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenCode",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "opencode_cli"
        const val NOTIFICATION_ID = 42
    }
}
