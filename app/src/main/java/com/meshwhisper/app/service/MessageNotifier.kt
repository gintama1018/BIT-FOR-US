package com.meshwhisper.app.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.meshwhisper.app.MeshApplication
import com.meshwhisper.app.R
import com.meshwhisper.app.ui.MainActivity
import java.io.File

object MessageNotifier {

    private const val GROUP_KEY_MESSAGES = "com.meshwhisper.app.MESSAGES_GROUP"

    @SuppressLint("MissingPermission")
    fun showMessageNotification(
        context: Context,
        senderId: Long,
        senderAlias: String,
        text: String,
        isBroadcast: Boolean,
        showPreview: Boolean,
        avatarUri: String? = null
    ) {
        // Android 13+ Runtime permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Intent opening MainActivity
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_NODE_ID", senderId)
            putExtra("EXTRA_IS_BROADCAST", isBroadcast)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            senderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isBroadcast) "Public Mesh ($senderAlias)" else senderAlias
        val contentText = if (showPreview) {
            text.ifBlank { "New message" }
        } else {
            "New message"
        }

        val builder = NotificationCompat.Builder(context, MeshApplication.MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY_MESSAGES)

        // Large icon if peer avatar exists
        if (!avatarUri.isNullOrBlank()) {
            val file = File(avatarUri)
            if (file.exists() && file.length() > 0) {
                try {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        builder.setLargeIcon(bmp)
                    }
                } catch (_: Exception) {
                }
            }
        }

        val notificationId = if (isBroadcast) 9999 else (senderId and 0x7FFFFFFFL).toInt()
        notificationManager.notify(notificationId, builder.build())
    }

    fun clearNotification(context: Context, senderId: Long) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notificationId = (senderId and 0x7FFFFFFFL).toInt()
        notificationManager?.cancel(notificationId)
    }

    const val CALL_NOTIFICATION_ID = 8888

    @SuppressLint("MissingPermission")
    fun showIncomingCallNotification(
        context: Context,
        callerId: Long,
        callerAlias: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_INCOMING_CALL", true)
            putExtra("EXTRA_NODE_ID", callerId)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            callerId.hashCode() + 1,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)

        val builder = NotificationCompat.Builder(context, MeshApplication.CALLS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setContentTitle("Incoming Voice Call")
            .setContentText("$callerAlias is calling...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .setSound(ringtoneUri)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        notificationManager.notify(CALL_NOTIFICATION_ID, builder.build())
    }

    fun clearCallNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(CALL_NOTIFICATION_ID)
    }
}
