package app.zenptt

import androidx.test.platform.app.InstrumentationRegistry

internal fun testServerAddress(): String =
    InstrumentationRegistry.getArguments().getString("serverAddress")
        ?: "ws://10.0.2.2:8080"
