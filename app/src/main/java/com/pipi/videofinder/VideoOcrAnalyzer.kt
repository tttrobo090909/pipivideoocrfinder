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

class VideoOcrAnalyzer(private val context: Context) {

    // Google ML Kit Korean 온디바이스 텍스트 인식 엔진 (오프라인)
    private val recognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )

    /**
     * 동영상 초반 10초 이내(0~10초)에서 핵심 프레임을 추출해 Google ML Kit OCR로 글자를 읽고,
     * 오타를 걸러낸 정제된 #태그 목록과 장면 정보를 반환합니다.
     */
    suspend fun analyzeVideo(
        uri: Uri,
        fileName: String,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): VideoItem = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var thumbnailBitmap: Bitmap? = null
        var durationMs = 0L
        val detectedScenes = mutableListOf<SceneItem>()
        val allRawTexts = mutableListOf<String>()

        try {
            retriever.setDataSource(context, uri)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durationStr?.toLongOrNull() ?: 0L

            // 1초 시점 썸네일 추출 (갤러리 카드 표시용)
            thumbnailBitmap = try {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                null
            }

            // 분석 구간: 영상 초반 0~10초 이내 (2초 간격 5개 핵심 프레임: 0s, 2s, 4s, 6s, 8s, 10s)
            val maxSecondsToScan = minOf(10L, durationMs / 1000L)
            val timestampsToScan = if (maxSecondsToScan <= 0L) {
                listOf(0L)
            } else {
                (0L..maxSecondsToScan step 2L).toList()
            }

            for ((stepIndex, sec) in timestampsToScan.withIndex()) {
                val progressPercent = ((stepIndex + 1) * 100) / timestampsToScan.size
                withContext(Dispatchers.Main) {
                    onProgress(progressPercent, "$fileName: 초반 10초 구간 분석 중 (${sec}초 시점)...")
                }

                val frameBitmap = try {
                    retriever.getFrameAtTime(sec * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) {
                    null
                } ?: continue

                // Google ML Kit 온디바이스 OCR 실행
                val inputImage = InputImage.fromBitmap(frameBitmap, 0)
                val visionText = recognizer.process(inputImage).await()
                val text = visionText.text.trim()

                if (text.isNotBlank()) {
                    allRawTexts.add(text)
                    detectedScenes.add(SceneItem(timestampSeconds = sec, text = text))
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // 무시
            }
        }

        // 오타와 노이즈를 필터링하여 의미 있는 #태그 목록 생성
        val cleanTags = TagFilter.extractCleanTags(allRawTexts)

        VideoItem(
            uriString = uri.toString(),
            name = fileName,
            durationMs = durationMs,
            tags = cleanTags,
            scenes = detectedScenes,
            isCached = false,
            thumbnailBitmap = thumbnailBitmap
        )
    }
}
