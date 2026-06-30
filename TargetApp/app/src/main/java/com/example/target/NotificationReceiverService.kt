package com.example.target

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationReceiverService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // Avoid sending our own app notifications
        if (packageName == this.packageName) return
        
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isNotEmpty() || text.isNotEmpty()) {
            val broadcastIntent = Intent("com.example.target.NOTIFICATION_EVENT").apply {
                putExtra("package", packageName)
                putExtra("title", title)
                putExtra("text", text)
            }
            sendBroadcast(broadcastIntent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Handle notification removal if needed in future
    }
}
