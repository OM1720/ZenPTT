package app.zenptt

import app.zenptt.headset.*

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ForegroundServiceLifecycleTest {
    private lateinit var context: Context
    private lateinit var savedPreferences: Map<String, Any?>

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        val preferences = context.getSharedPreferences(PREFERENCES_NAME_FOR_TEST, Context.MODE_PRIVATE)
        savedPreferences = preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        grantRequiredPermissions(instrumentation, context)
        stopAndAwaitService(context)
        assertTrue(preferences.edit().clear().commit())
        SharedPreferencesConnectionPreferences(context).apply {
            save(testServerAddress())
            saveLastChannel(ECHO_CHANNEL)
            savePowerSaveTimeoutMinutes(10)
            saveHeadsetSettings(HeadsetSettings())
        }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent 224").close()
        stopAndAwaitService(context)
        restorePreferences(
            context.getSharedPreferences(PREFERENCES_NAME_FOR_TEST, Context.MODE_PRIVATE),
            savedPreferences,
        )
    }

    @Test
    fun websocketAndNotificationSurviveActivityDestructionUntilDisconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            withBoundService(context) { service ->
                service.setHeadsetSettings(HeadsetSettings())
                assertTrue(service.applySettings(testServerAddress(), "10"))
                assertTrue(service.prepareConnection(echo = true))
                PttForegroundService.connect(context, service.state.value, echo = true)
                waitUntil(
                    condition = {
                        service.state.value.currentChannel == "ECHO" &&
                            service.state.value.status == SessionStatus.Ready &&
                            activePttNotificationText(notifications) ==
                            "Ready \u00b7 Network: Connected"
                    },
                    details = {
                        "state=${service.state.value}, " +
                            "notification=${activePttNotificationText(notifications)}"
                    },
                )
                val startsBeforeDuplicate = debugLong(service, "service.start_commands")
                PttForegroundService.connect(context, service.state.value, echo = true)
                waitUntil(
                    condition = {
                        debugLong(service, "service.start_commands") > startsBeforeDuplicate &&
                            activePttNotificationText(notifications) == "Ready \u00b7 Network: Connected"
                    },
                    details = {
                        "state=${service.state.value}, starts=${debugLong(service, "service.start_commands")}, " +
                            "notification=${activePttNotificationText(notifications)}"
                    },
                )
                service.setHeadsetSettings(HeadsetSettings(enabled = true))
                waitUntil {
                    val report = service.debugReport("test", "test")
                    report.contains("headset.enabled=true") &&
                        report.contains("headset.active=true") &&
                        report.contains("headset.receiver_registered=true") &&
                        report.contains("headset.media_session=true")
                }
                assertEquals(SessionStatus.Ready, service.state.value.status)
                service.setHeadsetSettings(HeadsetSettings())
                waitUntil {
                    val report = service.debugReport("test", "test")
                    report.contains("headset.active=false") &&
                        report.contains("headset.receiver_registered=false") &&
                        report.contains("headset.media_session=false")
                }
                service.setHeadsetSettings(HeadsetSettings(enabled = true))
                waitUntil {
                    val report = service.debugReport("test", "test")
                    report.contains("headset.receiver_registered=true") &&
                        report.contains("headset.media_session=true")
                }
                val reportBeforeDuplicateEnable = service.debugReport("test", "test")
                service.setHeadsetSettings(HeadsetSettings(enabled = true))
                SystemClock.sleep(POLL_INTERVAL_MS)
                val reportAfterDuplicateEnable = service.debugReport("test", "test")
                assertEquals(
                    reportBeforeDuplicateEnable.countOccurrences("bluetooth_receiver registered"),
                    reportAfterDuplicateEnable.countOccurrences("bluetooth_receiver registered"),
                )
                assertEquals(
                    reportBeforeDuplicateEnable.countOccurrences("media_session created"),
                    reportAfterDuplicateEnable.countOccurrences("media_session created"),
                )
                service.setHeadsetSettings(HeadsetSettings())
                waitUntil {
                    val report = service.debugReport("test", "test")
                    report.contains("headset.receiver_registered=false") &&
                        report.contains("headset.media_session=false")
                }
            }

            instrumentation.uiAutomation.executeShellCommand("input keyevent 26").close()
            SystemClock.sleep(SCREEN_OFF_HEARTBEAT_MS)

            scenario.close()

            withBoundService(context) { service ->
                waitUntil {
                    service.state.value.currentChannel == "ECHO" &&
                        service.state.value.status == SessionStatus.Ready &&
                        activePttNotificationText(notifications) == "Ready \u00b7 Network: Connected"
                }
                assertEquals("ECHO", service.state.value.currentChannel)
                assertEquals(SessionStatus.Ready, service.state.value.status)
                assertEquals(
                    "Ready \u00b7 Network: Connected",
                    activePttNotificationText(notifications),
                )
                assertTrue(
                    service.debugReport("test", "test")
                        .contains("service.foreground=true"),
                )

                val disconnect = notifications.activeNotifications
                    .first { it.id == ACTIVE_PTT_NOTIFICATION_ID }
                    .notification
                    .actions
                    .first { it.title.toString() == "Disconnect" }
                disconnect.actionIntent.send()
                waitUntil {
                    val report = service.debugReport("test", "test")
                    service.state.value.currentChannel == null &&
                        notifications.activeNotifications.isEmpty() &&
                        report.contains("service.foreground=false") &&
                        report.contains("service.ptt_wake_lock=held=false") &&
                        report.contains("headset.session_active=false") &&
                        report.contains("headset.receiver_registered=false") &&
                        report.contains("headset.media_session=false")
                }
            }
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
            scenario.close()
            stopAndAwaitService(context)
        }
    }

    @Test
    fun pttStartedSessionSurvivesActivityStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            var createdAt = ""
            withBoundService(context) { service ->
                waitUntil { debugLong(service, "service.start_commands") > 0 }
                PttForegroundService.disconnect(context)
                waitUntil {
                    service.state.value.currentChannel == null &&
                        !service.debugReport("test", "test").contains("service.foreground=true")
                }

                val stoppedStartCommands = debugLong(service, "service.start_commands")
                instrumentation.runOnMainSync {
                    service.setHeadsetSettings(HeadsetSettings())
                    assertTrue(service.applySettings(testServerAddress(), "10"))
                    service.setChannelCode(PTT_STARTED_CHANNEL)
                }
                val wakeLockAcquisitions = wakeLockAcquisitions(service)
                // Match UI dispatch so service callbacks cannot interleave the PTT start.
                instrumentation.runOnMainSync { service.pttDown() }
                waitUntil(
                    condition = {
                        service.state.value.currentChannel == PTT_STARTED_CHANNEL &&
                            service.state.value.status == SessionStatus.Transmitting &&
                            debugLong(service, "service.start_commands") > stoppedStartCommands &&
                            debugValue(service, "service.ptt_wake_lock").startsWith("held=true") &&
                            wakeLockAcquisitions(service) > wakeLockAcquisitions
                    },
                    details = {
                        "state=${service.state.value}, " +
                            "starts=${debugLong(service, "service.start_commands")}/$stoppedStartCommands, " +
                            "wakeLock=${debugValue(service, "service.ptt_wake_lock")}/$wakeLockAcquisitions"
                    },
                )
                instrumentation.runOnMainSync { service.pttUp() }
                waitUntil {
                    service.state.value.status == SessionStatus.Ready &&
                        debugValue(service, "service.ptt_wake_lock").startsWith("held=false") &&
                        activePttNotificationText(notifications) == "Ready \u00b7 Network: Connected"
                }

                createdAt = debugValue(service, "service.created_at_ms")
                assertTrue(service.debugReport("test", "test").contains("service.foreground=true"))
                assertTrue(activePttNotificationText(notifications)?.contains("Connected") == true)
            }

            scenario.moveToState(Lifecycle.State.CREATED)

            withBoundService(context) { service ->
                waitUntil {
                    service.state.value.currentChannel == PTT_STARTED_CHANNEL &&
                        service.state.value.status == SessionStatus.Ready &&
                        activePttNotificationText(notifications) == "Ready \u00b7 Network: Connected"
                }
                assertEquals(createdAt, debugValue(service, "service.created_at_ms"))
                assertTrue(service.debugReport("test", "test").contains("service.foreground=true"))
                assertTrue(activePttNotificationText(notifications)?.contains("Connected") == true)

                instrumentation.runOnMainSync { service.pttDown() }
                waitUntil {
                    debugValue(service, "service.ptt_wake_lock").startsWith("held=true")
                }
                PttForegroundService.disconnect(context)
                waitUntil {
                    service.state.value.currentChannel == null &&
                        debugValue(service, "service.ptt_wake_lock").startsWith("held=false") &&
                        notifications.activeNotifications.isEmpty()
                }
            }
        } finally {
            scenario.close()
            stopAndAwaitService(context)
        }
    }

    private fun debugLong(service: PttForegroundService, key: String): Long =
        debugValue(service, key).toLong()

    private fun debugValue(service: PttForegroundService, key: String): String =
        service.debugReport("test", "test")
            .lineSequence()
            .first { it.startsWith("$key=") }
            .substringAfter('=')

    private fun wakeLockAcquisitions(service: PttForegroundService): Long =
        debugValue(service, "service.ptt_wake_lock")
            .substringAfter("acquires=")
            .substringBefore(',')
            .toLong()

    private fun stopAndAwaitService(context: Context) {
        PttForegroundService.disconnect(context)
        withBoundService(context) { service ->
            service.pttUp()
            PttForegroundService.disconnect(context)
            waitUntil {
                service.state.value.currentChannel == null &&
                    !service.debugReport("test", "test").contains("service.foreground=true") &&
                    debugValue(service, "service.ptt_wake_lock").startsWith("held=false") &&
                    service.debugReport("test", "test").contains("headset.session_active=false") &&
                    service.debugReport("test", "test").contains("headset.receiver_registered=false") &&
                    service.debugReport("test", "test").contains("headset.media_session=false")
            }
            runBlocking { service.awaitAudioCleanup() }
        }
        context.stopService(Intent(context, PttForegroundService::class.java))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun restorePreferences(preferences: SharedPreferences, values: Map<String, Any?>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported preference type for $key: ${value.javaClass.name}")
            }
        }
        assertTrue("SharedPreferences were not restored", editor.commit())
        assertEquals(values, preferences.all)
    }

    private fun String.countOccurrences(value: String): Int =
        lineSequence().count { value in it }

    private fun grantRequiredPermissions(
        instrumentation: android.app.Instrumentation,
        context: Context,
    ) {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissions.forEach { permission ->
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
    }

    private fun withBoundService(
        context: Context,
        block: (PttForegroundService) -> Unit,
    ) {
        val connected = CountDownLatch(1)
        var service: PttForegroundService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as PttForegroundService.LocalBinder).service
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(
            context.bindService(
                Intent(context, PttForegroundService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        try {
            assertTrue(connected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            block(requireNotNull(service))
        } finally {
            context.unbindService(connection)
        }
    }

    private fun activePttNotificationText(notifications: NotificationManager): String? =
        notifications.activeNotifications
            .firstOrNull { it.id == ACTIVE_PTT_NOTIFICATION_ID }
            ?.notification
            ?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()

    private fun waitUntil(condition: () -> Boolean) {
        waitUntil(condition, details = { "" })
    }

    private fun waitUntil(condition: () -> Boolean, details: () -> String) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError(
                    "Timed out waiting for foreground service state. ${details()}",
                )
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val PREFERENCES_NAME_FOR_TEST = "zenptt"
        const val TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 50L
        const val SCREEN_OFF_HEARTBEAT_MS = 12_000L
        const val PTT_STARTED_CHANNEL = "PTTBGTEST"
    }
}
