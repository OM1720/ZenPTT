package app.zenptt

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundServiceManifestTest {
    @Test
    @Suppress("DEPRECATION")
    fun serviceHasRequiredTypesAndPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val service = packageManager.getServiceInfo(
            ComponentName(context, PttForegroundService::class.java),
            PackageManager.GET_META_DATA,
        )
        val expectedTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        val application = packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(R.mipmap.ic_launcher, application.icon)

        assertFalse(service.exported)
        assertEquals(expectedTypes, service.foregroundServiceType and expectedTypes)

        val requested = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()
        listOf(
            Manifest.permission.INTERNET,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.REQUEST_INSTALL_PACKAGES,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        ).forEach { permission -> assertTrue(permission in requested) }

        val fileProvider = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA,
        ).providers.orEmpty().single { it.authority == "${context.packageName}.fileprovider" }
        assertEquals("androidx.core.content.FileProvider", fileProvider.name)
        assertFalse(fileProvider.exported)
        assertTrue(fileProvider.grantUriPermissions)
        assertEquals(
            R.xml.update_file_paths,
            fileProvider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"),
        )
    }
}
