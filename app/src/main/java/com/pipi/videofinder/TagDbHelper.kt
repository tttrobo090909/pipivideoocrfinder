package com.pipi.videofinder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class TagDbHelper(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("video_ocr_tags_db", Context.MODE_PRIVATE)

    /**
     * 파일명과 영상 길이(ms)를 기준으로 이전에 분석된 태그 DB가 있는지 조회합니다.
     */
    fun getCachedVideo(fileName: String, durationMs: Long): VideoItem? {
        val key = buildKey(fileName, durationMs)
        val jsonStr = prefs.getString(key, null) ?: return null
        return parseVideoJson(jsonStr)
    }

    /**
     * 분석 완료된 비디오 정보를 태그 DB에 영구 저장합니다.
     */
    fun saveVideo(item: VideoItem) {
        val key = buildKey(item.name, item.durationMs)
        val json = JSONObject().apply {
            put("uriString", item.uriString)
            put("name", item.name)
            put("durationMs", item.durationMs)

            val tagsArr = JSONArray()
            item.tags.forEach { tagsArr.put(it) }
            put("tags", tagsArr)

            val scenesArr = JSONArray()
            item.scenes.forEach { scene ->
                val sceneObj = JSONObject().apply {
                    put("timestampSeconds", scene.timestampSeconds)
                    put("text", scene.text)
                }
                scenesArr.put(sceneObj)
            }
            put("scenes", scenesArr)
        }

        prefs.edit().putString(key, json.toString()).apply()

        // 인덱스 목록에도 등록
        val indexSet = prefs.getStringSet("all_video_keys", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        indexSet.add(key)
        prefs.edit().putStringSet("all_video_keys", indexSet).apply()
    }

    /**
     * 저장된 모든 비디오 태그 DB 목록을 불러옵니다.
     */
    fun getAllCachedVideos(): List<VideoItem> {
        val indexSet = prefs.getStringSet("all_video_keys", emptySet()) ?: emptySet()
        val list = mutableListOf<VideoItem>()
        for (key in indexSet) {
            val jsonStr = prefs.getString(key, null) ?: continue
            val item = parseVideoJson(jsonStr)
            if (item != null) {
                list.add(item.copy(isCached = true))
            }
        }
        return list
    }

    private fun buildKey(fileName: String, durationMs: Long): String {
        return "vid_${fileName}_$durationMs"
    }

    private fun parseVideoJson(jsonStr: String): VideoItem? {
        return try {
            val obj = JSONObject(jsonStr)
            val uriString = obj.optString("uriString", "")
            val name = obj.optString("name", "")
            val durationMs = obj.optLong("durationMs", 0L)

            val tagsList = mutableListOf<String>()
            val tagsArr = obj.optJSONArray("tags")
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    tagsList.add(tagsArr.getString(i))
                }
            }

            val scenesList = mutableListOf<SceneItem>()
            val scenesArr = obj.optJSONArray("scenes")
            if (scenesArr != null) {
                for (i in 0 until scenesArr.length()) {
                    val sceneObj = scenesArr.getJSONObject(i)
                    scenesList.add(
                        SceneItem(
                            timestampSeconds = sceneObj.optLong("timestampSeconds", 0L),
                            text = sceneObj.optString("text", "")
                        )
                    )
                }
            }

            VideoItem(
                uriString = uriString,
                name = name,
                durationMs = durationMs,
                tags = tagsList,
                scenes = scenesList,
                isCached = true
            )
        } catch (e: Exception) {
            null
        }
    }
}
