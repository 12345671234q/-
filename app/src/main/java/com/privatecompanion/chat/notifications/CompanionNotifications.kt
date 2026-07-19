package com.privatecompanion.chat.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.privatecompanion.chat.MainActivity
import com.privatecompanion.chat.R

object CompanionNotifications {
    const val CHANNEL_ID = "companion_messages"
    const val ACTION_NEW_MESSAGE = "com.privatecompanion.chat.ACTION_NEW_PROACTIVE_MESSAGE"
    const val EXTRA_OPEN_CHAT = "open_chat"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "主动消息",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "学习监督、行程查岗和其他主动关心消息"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!runtimeGranted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun showMessage(context: Context, senderName: String, content: String, messageId: Long) {
        createChannel(context)
        if (!canNotify(context)) return
        context.sendBroadcast(
            Intent(ACTION_NEW_MESSAGE).setPackage(context.packageName),
        )

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CHAT, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            messageId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val localUser = Person.Builder()
            .setName("我")
            .setKey("local_user")
            .build()
        val sender = Person.Builder()
            .setName(senderName)
            .setKey("companion_sender")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(senderName)
            .setContentText(content)
            .setStyle(
                NotificationCompat.MessagingStyle(localUser)
                    .setConversationTitle(senderName)
                    .addMessage(content, System.currentTimeMillis(), sender),
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(messageId.hashCode(), notification)
    }
}
