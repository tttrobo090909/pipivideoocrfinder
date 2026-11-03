package com.pipi.videofinder

import android.graphics.Bitmap

data class SceneItem(
    val timestampSeconds: Long,
    val text: String
)

data class VideoItem(
    val uriString: String,
    val name: String,
    val durationMs: Long,
    val tags: List<String>,
    val scenes: List<SceneItem>,
    val isCached: Boolean = false,
    @Transient var thumbnailBitmap: Bitmap? = null
)
