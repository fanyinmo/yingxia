package com.local.douyinsaver

import android.content.Context
import android.media.metrics.LogSessionId
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultDecoderFactory
import kotlin.math.floor

/** Keeps Media3's decoder workarounds while checking each video's actual size first. */
@OptIn(UnstableApi::class)
internal class AlbumDecoderFactory(private val context: Context) : Codec.DecoderFactory {
    private val audioDecoderFactory = DefaultDecoderFactory.Builder(context).build()

    override fun createForAudioDecoding(format: Format, logSessionId: LogSessionId?): Codec =
        audioDecoderFactory.createForAudioDecoding(format, logSessionId)

    override fun createForVideoDecoding(
        format: Format,
        outputSurface: Surface,
        requestSdrToneMapping: Boolean,
        logSessionId: LogSessionId?,
    ): Codec {
        // In Media3 1.8, a covering PerformancePoint can bypass the native minimum size check.
        // Filter before its support ranking; a successfully initialized but empty decoder cannot
        // trigger initialization fallback. Never change the input geometry to fit a decoder.
        val selector = MediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling).filter { info ->
                val capabilities = info.capabilities?.videoCapabilities
                val alignedSize = if (format.width > 0 && format.height > 0)
                    info.alignVideoSizeV21(format.width, format.height) else null
                // Preserve Media3's alignment check for cropped display dimensions, but check the
                // original dimensions against native ranges first so alignment cannot hide a minimum.
                val supported = if (format.width <= 0 || format.height <= 0) true else
                    capabilities != null && capabilities.supportedWidths.contains(format.width) &&
                        capabilities.supportedHeights.contains(format.height) && alignedSize != null &&
                        capabilities.isSizeSupported(alignedSize.x, alignedSize.y) &&
                        (format.frameRate < 1f || !format.frameRate.isFinite() ||
                            capabilities.areSizeAndRateSupported(alignedSize.x, alignedSize.y,
                                floor(format.frameRate.toDouble())))
                if (!supported) {
                    Log.d(TAG, "rejected-size codec=${info.name} size=${format.width}x${format.height}")
                }
                supported
            }
        }
        val factory = DefaultDecoderFactory.Builder(context)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
            .setListener { codecName, _ ->
                Log.d(TAG, "decoder=$codecName size=${format.width}x${format.height}")
            }
            .build()
        return factory.createForVideoDecoding(format, outputSurface, requestSdrToneMapping, logSessionId)
    }

    private companion object {
        const val TAG = "AlbumDecoder"
    }
}
