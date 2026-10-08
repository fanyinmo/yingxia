package com.local.douyinsaver

import androidx.test.platform.app.InstrumentationRegistry

/** Runtime target identity; compile-time constants may be stale in incremental test builds. */
internal object InstalledTestTarget {
    val versionName: String
        get() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }
}
