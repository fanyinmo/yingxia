package com.local.douyinsaver

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.util.UUID

/** Explicit public-share opt-in. Uses an isolated engine and never saves to the user's gallery. */
@RunWith(AndroidJUnit4::class)
class DynamicAlbumPublicSampleTest {
    @Test fun inspectPublicAlbumWithoutSaving() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Real network requests require run_public_album=true",
            arguments.getString("run_public_album") == "true")
        val share = ShareLinks.extract(requireNotNull(arguments.getString("shareUrl")))
        val id = requireNotNull(arguments.getString("videoId"))
        require(id.matches(Regex("[0-9]{10,25}")))
        val context = instrumentation.targetContext
        val prefix = "dynamic_public_${UUID.randomUUID()}_"
        val singleton = SaverEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        lateinit var engine: SaverEngine
        instrumentation.runOnMainSync {
            engine = SaverEngine::class.java.getDeclaredConstructor(Application::class.java, String::class.java)
                .apply { isAccessible = true }.newInstance(context.applicationContext as Application, prefix)
            singleton.set(null, engine)
        }
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                lateinit var model: SaverViewModel
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[SaverViewModel::class.java]
                    model.acceptShare(share)
                    model.resolve()
                }
                val deadline = SystemClock.elapsedRealtime() + 90_000L
                var stage = TaskStage.IDLE
                var message = ""
                var diagnostics = ""
                var video: ParsedVideo? = null
                while (true) {
                    instrumentation.runOnMainSync {
                        stage = model.stage
                        message = model.message
                        diagnostics = model.diagnostics
                        video = model.video
                    }
                    if (stage == TaskStage.READY || stage == TaskStage.FAILED || stage == TaskStage.CANCELLED ||
                        SystemClock.elapsedRealtime() >= deadline) break
                    SystemClock.sleep(200)
                }
                fun host(value: String) = runCatching { URI(value).host }.getOrNull().orEmpty()
                val report = JSONObject().put("requestedId", id).put("stage", stage.name)
                    .put("message", DiagnosticText.clean(message, 600))
                    .put("diagnostics", DiagnosticText.clean(diagnostics, 16_000))
                video?.let { parsed ->
                    report.put("parsedId", parsed.id).put("album", parsed.isAlbum)
                        .put("duration", parsed.durationSeconds).put("mediaHost", host(parsed.mediaUrl))
                        .put("bgm", parsed.bgmUrl.isNotBlank()).put("images", JSONArray(parsed.images.map { image ->
                            JSONObject().put("kind", image.kind.name).put("mimeType", image.mimeType)
                                .put("width", image.width).put("height", image.height).put("host", host(image.url))
                                .put("cleanSources", image.mediaSources.count { it.mode == WatermarkMode.CLEAN })
                                .put("motion", image.motion?.let { motion ->
                                    JSONObject().put("host", host(motion.url)).put("duration", motion.durationSeconds)
                                        .put("width", motion.width).put("height", motion.height)
                                        .put("cleanSources", motion.mediaSources.count { it.mode == WatermarkMode.CLEAN })
                                } ?: JSONObject.NULL)
                        }))
                }
                File(context.cacheDir, "dynamic-public-sample-$id.json").writeText(report.toString(2))
                Log.i("DynamicPublicAlbum", "result id=$id stage=$stage images=${video?.images?.size ?: 0}")
                assertEquals("Public album parsing failed: $message; $diagnostics", TaskStage.READY, stage)
                assertEquals(id, checkNotNull(video).id)
                val expectedAlbum = arguments.getString("expected_album") != "false"
                assertEquals(expectedAlbum, checkNotNull(video).isAlbum)
                if (expectedAlbum) assertTrue(checkNotNull(video).images.isNotEmpty())
                else assertTrue(checkNotNull(video).mediaUrl.isNotBlank())
                assertTrue(WatermarkSources.available(checkNotNull(video), WatermarkMode.CLEAN))
                assertTrue("Read-only test unexpectedly wrote download records", model.history.isEmpty())
                instrumentation.runOnMainSync { model.cancel() }
            }
        } finally {
            instrumentation.runOnMainSync {
                (SaverEngine::class.java.getDeclaredField("scope").apply { isAccessible = true }
                    .get(engine) as CoroutineScope).cancel()
                singleton.set(null, previous)
            }
            for (name in listOf("downloads", "download_tasks", "download_options", "parse_diagnostics")) {
                context.deleteSharedPreferences(prefix + name)
            }
        }
    }
}
