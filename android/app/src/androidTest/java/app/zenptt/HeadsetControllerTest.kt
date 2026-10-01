package app.zenptt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import app.zenptt.headset.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class HeadsetControllerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var clock = SystemClock.uptimeMillis()
    private lateinit var controller: HeadsetController
    private lateinit var scope: CoroutineScope
    private var downs = 0
    private var ups = 0
    private var connected = true
    private lateinit var bluetoothReceiver: BroadcastReceiver

    @Test fun lateDisconnectFromAnotherBluetoothConnectionDoesNotAbortSetup() = withController {
        val context = instrumentation.targetContext
        val device = context.getSystemService(BluetoothManager::class.java).adapter
            .getRemoteDevice("00:11:22:33:44:55")
        for (source in listOf(HeadsetSource.Spp, HeadsetSource.Ble)) {
            main {
                controller.configure(HeadsetSettings(false, HeadsetSetup.factory().copy(
                    source = source, device = device.address, autoSelect = false,
                    framing = if (source == HeadsetSource.Spp) Framing.Delimited else Framing.Packet,
                    characteristic = if (source == HeadsetSource.Ble) "0000fff1-0000-1000-8000-00805f9b34fb" else "")), false)
                controller.startSetup()
                val before = controller.setup.value
                // Invoke the registered receiver directly: apps cannot send protected Bluetooth
                // broadcasts. No radio connection is needed to reproduce the late notification.
                bluetoothReceiver.onReceive(context, Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    .putExtra(BluetoothDevice.EXTRA_DEVICE, device))
                assertEquals(before, controller.setup.value)
                bluetoothReceiver.onReceive(context, Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                    .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED))
                assertEquals(before, controller.setup.value)
                assertTrue(controller.debugReport().contains("source=$source ready=false decision=await_source"))
                // Global shutdown remains authoritative even when the selected source is owned.
                bluetoothReceiver.onReceive(context, Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                    .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_TURNING_OFF))
                assertEquals(SetupStep.Error, controller.setup.value?.step)
                assertEquals(0, downs)
                controller.cancelSetup()
            }
        }
    }

    @Test fun mediaDisconnectStillReleasesHardwareInputAndRequiresANewPress() = withController {
        main { controller.configure(HeadsetSettings(true, HeadsetSetup.media(85, ButtonBehavior.Hold)), true) }
        await { controller.status.value == "ready" }
        val id = press()
        main {
            bluetoothReceiver.onReceive(instrumentation.targetContext, Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED))
            assertFalse(controller.engaged)
            assertEquals(1, ups)
            controller.handleKeyEvent(KeyEvent(id, clock + 1, KeyEvent.ACTION_DOWN, 85, 1))
            assertEquals(1, downs)
        }
        release(id)
        release(press())
        assertEquals(2, downs)
        assertEquals(2, ups)
    }

    @Test fun selectionRechecksConnectionAndRejectsTheGlobalBypass() = withController {
        main { controller.startSetup(); controller.next(); controller.selectDevice(null) }
        assertEquals(SetupStep.Devices, controller.setup.value?.step)
        main { connected = false; controller.selectDevice("test") }
        assertEquals(SetupStep.Devices, controller.setup.value?.step)
        assertEquals(0, downs)
    }

    @Test fun completeLearningDoesNotTransmitAndRestoresTheSavedSetupOnCancel() = withController {
        main { controller.configure(HeadsetSettings(true, HeadsetSetup.media(85, ButtonBehavior.Hold)), true) }
        await { controller.status.value == "ready" }
        val oldPress = press()
        main { controller.reset("server_denied") }
        main { controller.handleKeyEvent(KeyEvent(oldPress, clock + 1, KeyEvent.ACTION_DOWN, 85, 1)) }
        release(oldPress)
        assertEquals(1, downs)
        assertEquals(1, ups)

        main { controller.startSetup(false); controller.next(); controller.selectDevice("test") }
        await { controller.setup.value?.step == SetupStep.Quiet }
        assertEquals(HeadsetSource.Media, controller.setup.value?.source)
        advance(3_001)
        await { controller.setup.value?.step == SetupStep.Hold }
        val durations = listOf(4_000L, 6_000L, 4_000L, 5_000L, 4_000L)
        durations.forEachIndexed { index, duration ->
            assertEquals(index + 1, controller.setup.value?.round)
            val id = press()
            advance(duration)
            await { controller.setup.value?.step == SetupStep.Release }
            release(id)
            advance(3_001)
            await { controller.setup.value?.step == if (index == 4) SetupStep.OtherButtons else SetupStep.Hold }
        }
        assertEquals(1, downs)
        assertEquals(HeadsetSource.Media, controller.setup.value?.source)
        assertNull(controller.result())
        advance(3_001)
        await { controller.setup.value?.canNext == true }
        main { controller.next() }
        assertTrue(controller.setup.value!!.holdAvailable)
        main { controller.next() }
        assertNull(controller.result())
        val id = press()
        assertTrue(controller.setup.value!!.testOn)
        advance(2_000)
        release(id)
        assertTrue(controller.setup.value!!.testComplete)
        assertNull(controller.result())
        main { controller.confirm(true) }
        assertNotNull(controller.result())
        assertEquals(1, downs)
        main { controller.saveFailed() }
        assertNotNull(controller.result())
        assertNotNull(controller.setup.value!!.error)
        main { controller.cancelSetup() }
        await { controller.status.value == "ready" }
        main { controller.handleKeyEvent(KeyEvent(id, clock, KeyEvent.ACTION_DOWN, 85, 0)) }
        assertEquals(1, downs)
        release(press())
        assertEquals(2, downs)
        assertEquals(2, ups)
    }

    @Test fun cancellationTimeoutAndCloseReleaseAllResources() = withController {
        main { controller.startSetup(); controller.cancelSetup() }
        assertNull(controller.setup.value)
        main { controller.startSetup(); controller.next(); controller.cancelSetup() }
        assertNull(controller.setup.value)
        main { controller.startSetup(); controller.next(); controller.selectDevice("test") }
        await { controller.setup.value?.step == SetupStep.Quiet }
        advance(121_000)
        await { controller.setup.value?.step == SetupStep.Error }
        assertEquals(0, downs)
        main { controller.retry() }
        assertEquals(SetupStep.Devices, controller.setup.value?.step)
        main { controller.cancelSetup(); controller.close() }
        assertTrue(controller.debugReport().contains("headset.media_session=false"))
        assertTrue(controller.debugReport().contains("headset.receiver_registered=false"))
    }

    private fun withController(test: () -> Unit) {
        main {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val context = object : ContextWrapper(instrumentation.targetContext) {
                override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
                    broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? {
                    if (receiver != null && filter.hasAction(BluetoothAdapter.ACTION_STATE_CHANGED)) bluetoothReceiver = receiver
                    return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
                }
            }
            controller = HeadsetController(context, scope, { downs++ }, { ups++ }, { clock },
                { if (connected) listOf(HeadsetDevice("test", "Connected test input")) else emptyList() })
        }
        try { test() } finally { main { controller.close(); scope.cancel() } }
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun advance(ms: Long) = main { clock += ms }
    private fun press(): Long {
        var id = 0L
        main {
            clock += 10
            id = clock
            assertTrue(controller.handleKeyEvent(KeyEvent(id, id, KeyEvent.ACTION_DOWN, 85, 0)))
        }
        return id
    }
    private fun release(id: Long) = main {
        clock += 10
        controller.handleKeyEvent(KeyEvent(id, clock, KeyEvent.ACTION_UP, 85, 0))
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for headset state: ${controller.setup.value}", predicate())
    }
}
