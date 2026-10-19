package com.mybotrun.observer

import android.net.Uri

data class VideoEntry(val uri: Uri, val name: String, val bytes: Long)
data class Sample(val ms: Long, val frameDiff: Double, val bottomDiff: Double, val active: Boolean)
data class Recognition(
    val ms: Long, val klass: String, val confidence: Float,
    val x: Float, val y: Float, val w: Float, val h: Float
)
data class VideoResult(
    val source: VideoEntry,
    var durationMs: Long,
    val samples: MutableList<Sample> = mutableListOf(),
    val detections: MutableList<Recognition> = mutableListOf(),
    val frames: MutableList<Pair<Long, ByteArray>> = mutableListOf(),
    var decodeMode: String = "MediaCodec/MediaExtractor",
    var yoloRun: Boolean = false,
    var complete: Boolean = false,
    var failure: String? = null
)
