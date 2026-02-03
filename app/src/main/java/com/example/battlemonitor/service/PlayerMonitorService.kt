package com.example.battlemonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.battlemonitor.R
import com.example.battlemonitor.MainActivity
import com.example.battlemonitor.data.PlayerRepository
import com.example.battlemonitor.data.PlayerStorage
import com.example.battlemonitor.monitor.PlayerMonitorEngine
import com.example.battlemonitor.model.WatchedPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerMonitorService : Service() {

    companion object {
        private const val STATUS_CHANNEL_ID = "player_status"
        private const val SERVICE_CHANNEL_ID = "player_monitor_service"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val REFRESH_DELAY_MS = 10_000L
        private const val DEFAULT_GROUP = "DEFAULT"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = PlayerRepository()
    private val engine = PlayerMonitorEngine(repository)
    private lateinit var storage: PlayerStorage
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private val groupOfflineState = mutableMapOf<String, Boolean>()
    private val groupMembershipSignatures = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        storage = PlayerStorage(applicationContext)
        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, buildForegroundNotification())
        startMonitoringLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoringLoop() {
        scope.launch {
            while (isActive) {
                val players = storage.load().toMutableList()
                if (players.isNotEmpty()) {
                    val result = engine.scan(players)
                    result.statusChanges
                        .filter { it.first.notificationsEnabled != false }
                        .forEach { (player, isOnline) ->
                            if (canPostNotifications()) {
                                sendStatusNotification(player, isOnline)
                            }
                        }
                    val groupNotifications = storage.loadGroupNotifications()
                    updateGroupOfflineNotifications(players, groupNotifications)
                    if (result.changed) {
                        storage.save(players)
                    }
                }
                delay(REFRESH_DELAY_MS)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Monitor graczy działa w tle")
            .setContentText("Śledzenie online/offline w tle")
            .setContentIntent(buildContentIntent())
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val statusChannel = NotificationChannel(
                STATUS_CHANNEL_ID,
                "Zmiany online/offline",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Powiadomienia o zmianach statusu graczy"
            }
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Monitoring w tle",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Powiadomienie o aktywnym monitoringu"
            }

            val manager =
                getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(statusChannel)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun sendStatusNotification(player: WatchedPlayer, isOnline: Boolean) {
        val displayName = player.resolvedName.ifBlank { player.key }
        val title = if (isOnline) "Gracz online" else "Gracz offline"
        val message = if (isOnline) {
            "$displayName jest online"
        } else {
            "$displayName jest offline"
        }

        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(buildContentIntent())
            .setAutoCancel(true)
            .build()

        notificationManager.notify(displayName.hashCode(), notification)
    }

    private fun updateGroupOfflineNotifications(
        players: List<WatchedPlayer>,
        groupNotifications: Map<String, Boolean>
    ) {
        val grouped = players.groupBy { groupKey(it.group) }
        val activeKeys = grouped.keys.filter { it.isNotBlank() }.toSet()
        groupOfflineState.keys.retainAll(activeKeys)
        groupMembershipSignatures.keys.retainAll(activeKeys)

        grouped.forEach { (key, members) ->
            if (key.isBlank()) {
                return@forEach
            }
            val signature = buildGroupSignature(members)
            val previousSignature = groupMembershipSignatures[key]
            val membershipChanged = previousSignature != null && previousSignature != signature
            val isFirstSeen = previousSignature == null
            groupMembershipSignatures[key] = signature
            val enabled = groupNotifications[key] ?: true
            if (!enabled) {
                groupOfflineState[key] = false
                return@forEach
            }
            val allOffline = members.all { !it.online }
            val wasOffline = groupOfflineState[key] ?: false
            if ((membershipChanged || isFirstSeen)) {
                groupOfflineState[key] = allOffline
                return@forEach
            }
            if (!wasOffline && allOffline && canPostNotifications()) {
                val groupName = members.firstOrNull()?.group ?: DEFAULT_GROUP
                sendGroupOfflineNotification(groupName)
            }
            groupOfflineState[key] = allOffline
        }
    }

    private fun sendGroupOfflineNotification(groupName: String) {
        val title = "Grupa offline"
        val message = "Wszyscy gracze w grupie $groupName są offline"
        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(buildContentIntent())
            .setAutoCancel(true)
            .build()
        notificationManager.notify("group_$groupName".hashCode(), notification)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun groupKey(name: String?): String {
        val normalized = name?.trim().orEmpty()
        return normalized.lowercase()
    }

    private fun buildGroupSignature(members: List<WatchedPlayer>): String {
        return members
            .map { it.resolvedId?.trim().orEmpty().ifBlank { it.key } }
            .map { it.trim().lowercase() }
            .sorted()
            .joinToString(separator = "|")
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }
}
