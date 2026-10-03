package com.storeguard.hub.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.storeguard.hub.data.CctvEventEntity
import com.storeguard.hub.data.CollectionLogEntity
import com.storeguard.hub.data.StoreGuardDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DmssNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != DMSS_PACKAGE) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val now = System.currentTimeMillis()

        val event = CctvEventEntity(
            eventAtMillis = sbn.postTime,
            title = title,
            body = body,
            notificationKey = sbn.key ?: "$title|$body|${sbn.postTime}",
            capturedAtMillis = now
        )

        scope.launch {
            val dao = StoreGuardDatabase.get(applicationContext).dao()
            dao.insertCctvEvent(event)
            dao.insertLog(
                CollectionLogEntity(
                    source = "DMSS",
                    level = "INFO",
                    message = "알림 수집: ${title.ifBlank { "제목 없음" }} / ${body.take(80)}",
                    createdAtMillis = now
                )
            )
        }
    }

    companion object {
        const val DMSS_PACKAGE = "com.mm.android.DMSS"
    }
}
