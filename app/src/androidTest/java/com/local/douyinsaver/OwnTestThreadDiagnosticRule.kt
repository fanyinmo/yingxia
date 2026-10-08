package com.local.douyinsaver

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only diagnostics of this test process; never signals or reads another app. */
class OwnTestThreadDiagnosticRule : TestWatcher() {
    private var completed: CountDownLatch? = null

    override fun starting(description: Description) {
        val done = CountDownLatch(1)
        completed = done
        Thread({
            if (!done.await(30, TimeUnit.SECONDS)) runCatching {
                val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "own_thread_diagnostic_${UUID.randomUUID()}")
                check(directory.mkdir())
                val report = File(directory, "threads.txt")
                report.writeText(buildString {
                    appendLine(description.displayName)
                    Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, trace) ->
                        appendLine("${thread.name}: ${thread.state}")
                        trace.forEach { appendLine("  $it") }
                    }
                })
                println("own_test_thread_report=${report.absolutePath}")
            }
        }, "Own test diagnostic").apply { isDaemon = true; start() }
    }

    override fun finished(description: Description) { completed?.countDown() }
}
