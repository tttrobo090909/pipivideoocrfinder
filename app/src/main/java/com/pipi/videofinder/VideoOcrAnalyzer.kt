// com/pipi/videofinder/VideoOcrAnalyzer.kt
package com.pipi.videofinder

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class VideoSearchResult(
    val videoName: String,
    val videoUri: Uri,
    val timestampSeconds: Long,
    val timeFormatted: String,
    val recognizedText: String,
    val thumbnailBitmap: Bitmap?
)

class VideoOcrAnalyzer(private val context: Context) {

    // 오프라인 온디바이스 구글 ML Kit 한글 텍스트 인식기
    private val recognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )

    suspend fun analyzeVideo(
        videoUri: Uri,
        videoName: String,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): List<VideoSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<VideoSearchResult>()
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, videoUri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 10000L
            val durationSec = durationMs / 1000

            // 1. robo 님의 요청 반영: 영상 전체와 무관하게 처음 10초 이내만 분석!
            val scanLimitSec = Math.min(10L, durationSec)

            // 2. 10초 이내에서 2초 간격 (1초, 3초, 5초, 7초, 9초)
            val timePoints = mutableListOf<Long>()
            for (t in 1..scanLimitSec step 2) {
                timePoints.add(t)
            }
            if (timePoints.isEmpty()) timePoints.add(1L)

            for ((index, sec) in timePoints.withIndex()) {
                val progress = ((index + 1) * 100) / timePoints.size
                onProgress(progress, "$videoName ($sec 초) 한글 분석 중...")

                val timeUs = sec * 1_000_000L
                val frameBitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue

                // 3. 구글 ML Kit 온디바이스 한글 인식 (오프라인)
                val inputImage = InputImage.fromBitmap(frameBitmap, 0)
                try {
                    val visionText = recognizer.process(inputImage).await()
                    val text = visionText.text.trim()

                    if (text.isNotEmpty()) {
                        val min = sec / 60
                        val s = sec % 60
                        val formatted = String.format("%02d:%02d", min, s)

                        results.add(
                            VideoSearchResult(
                                videoName = videoName,
                                videoUri = videoUri,
                                timestampSeconds = sec,
                                timeFormatted = formatted,
                                recognizedText = text,
                                thumbnailBitmap = frameBitmap
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) { }
        }

        results
    }
}
