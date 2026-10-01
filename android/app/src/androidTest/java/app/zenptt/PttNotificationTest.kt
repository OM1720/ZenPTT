package app.zenptt

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PttNotificationTest {
    @Test
    fun pttErrorIsVisibleInAStatusBarEligibleSilentNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        notifications.createNotificationChannel(
            NotificationChannel(
                LEGACY_ACTIVE_PTT_NOTIFICATION_CHANNEL_ID,
                "Legacy active PTT session",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        createActivePttNotificationChannel(notifications)

        val readyText = "Ready \u00b7 Network: Connected"
        val error = "PTT request timed out. Press again."
        try {
            publishActivePttNotification(
                context,
                notifications,
                ChannelUiState(currentChannel = "ECHO", status = SessionStatus.Ready),
            )
            val readyNotification = awaitActivePttNotification(notifications, readyText)
            assertEquals(
                readyText,
                readyNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            )

            publishActivePttNotification(
                context,
                notifications,
                ChannelUiState(
                    currentChannel = "ECHO",
                    status = SessionStatus.Ready,
                    pttError = error,
                ),
            )
            val notification = awaitActivePttNotification(notifications, error)
            val channel = notifications.getNotificationChannel(ACTIVE_PTT_NOTIFICATION_CHANNEL_ID)

            assertEquals(error, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(R.drawable.ic_yinyang_notification, notification.smallIcon.resId)
            assertEquals(
                listOf("PTT", "Disconnect"),
                notification.actions.map { it.title.toString() },
            )
            assertEquals(notification.contentIntent, notification.actions[0].actionIntent)
            assertTrue(notification.actions[0].actionIntent.isActivity)
            assertTrue(notification.actions[1].actionIntent.isService)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
            assertNull(channel.sound)
            assertFalse(channel.shouldVibrate())
            assertFalse(channel.canShowBadge())
            assertNull(
                notifications.getNotificationChannel(LEGACY_ACTIVE_PTT_NOTIFICATION_CHANNEL_ID),
            )
        } finally {
            notifications.cancel(ACTIVE_PTT_NOTIFICATION_ID)
        }
    }

    private fun awaitActivePttNotification(
        notifications: NotificationManager,
        expectedText: String,
    ): Notification {
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_TIMEOUT_MS
        var lastText: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val notification = notifications.activeNotifications
                .firstOrNull { it.id == ACTIVE_PTT_NOTIFICATION_ID }
                ?.notification
            lastText = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (lastText == expectedText) return requireNotNull(notification)
            SystemClock.sleep(NOTIFICATION_POLL_MS)
        }
        throw AssertionError(
            "Active PTT notification text was not updated: " +
                "expected=$expectedText actual=${lastText ?: "missing"}",
        )
    }

    private companion object {
        const val NOTIFICATION_TIMEOUT_MS = 5_000L
        const val NOTIFICATION_POLL_MS = 25L
    }
}
