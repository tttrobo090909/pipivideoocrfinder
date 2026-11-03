// com/pipi/videofinder/MainActivity.kt
package com.pipi.videofinder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var analyzer: VideoOcrAnalyzer
    private val selectedVideoUris = mutableListOf<Uri>()
    private val allResults = mutableListOf<VideoSearchResult>()

    private lateinit var btnSelectVideos: View
    private lateinit var btnStartAnalyze: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var etSearch: EditText
    private lateinit var tvResultCount: TextView
    private lateinit var rvResults: RecyclerView
    private lateinit var adapter: SearchResultAdapter

    private val selectVideosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedVideoUris.clear()
            selectedVideoUris.addAll(uris)
            btnStartAnalyze.isEnabled = true
            btnStartAnalyze.text = "⚡ ${uris.size}개 영상 ML Kit 분석 시작"
            Toast.makeText(this, "${uris.size}개 영상이 선택되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        analyzer = VideoOcrAnalyzer(this)

        btnSelectVideos = findViewById(R.id.btnSelectVideos)
        btnStartAnalyze = findViewById(R.id.btnStartAnalyze)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        etSearch = findViewById(R.id.etSearch)
        tvResultCount = findViewById(R.id.tvResultCount)
        rvResults = findViewById(R.id.rvResults)

        rvResults.layoutManager = LinearLayoutManager(this)
        adapter = SearchResultAdapter { result ->
            playVideoAt(result.videoUri, result.timestampSeconds)
        }
        rvResults.adapter = adapter

        btnSelectVideos.setOnClickListener {
            selectVideosLauncher.launch("video/*")
        }

        btnStartAnalyze.setOnClickListener {
            startAnalysis()
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                filterResults(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun startAnalysis() {
        if (selectedVideoUris.isEmpty()) return

        btnStartAnalyze.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvProgress.visibility = View.VISIBLE
        allResults.clear()

        lifecycleScope.launch {
            for ((index, uri) in selectedVideoUris.withIndex()) {
                val fileName = "영상 ${index + 1}"
                val results = analyzer.analyzeVideo(uri, fileName) { pct, statusText ->
                    progressBar.progress = pct
                    tvProgress.text = statusText
                }
                allResults.addAll(results)
            }

            progressBar.visibility = View.GONE
            tvProgress.text = "🎉 ML Kit 고정밀 분석 완료! (총 ${allResults.size}개 장면 검출)"
            btnStartAnalyze.isEnabled = true
            btnStartAnalyze.text = "✅ 분석 완료"
            filterResults(etSearch.text.toString())
        }
    }

    private fun filterResults(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allResults
        } else {
            allResults.filter { it.recognizedText.lowercase().contains(q) }
        }

        tvResultCount.text = "총 ${filtered.size}개 장면 발견"
        adapter.submitList(filtered, q)
    }

    private fun playVideoAt(uri: Uri, seconds: Long) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            putExtra("position", (seconds * 1000).toInt())
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "재생할 수 있는 비디오 플레이어가 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}
