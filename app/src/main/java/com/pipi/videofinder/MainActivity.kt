package com.pipi.videofinder

import android.app.Dialog
import android.content.Intent
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var analyzer: VideoOcrAnalyzer
    private lateinit var dbHelper: TagDbHelper

    private val allVideoList = mutableListOf<VideoItem>()
    private lateinit var galleryAdapter: GalleryAdapter

    private lateinit var btnSelectFolder: Button
    private lateinit var btnSelectVideos: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var layoutTagChips: LinearLayout
    private lateinit var tvResultCount: TextView
    private lateinit var rvGallery: RecyclerView

    // 1. 폴더 선택 런처 (지정 폴더 내 모든 비디오 탐색)
    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // 무시
            }
            scanFolderUri(treeUri)
        }
    }

    // 2. 동영상 직접 선택 런처 (다중 비디오 파일 선택)
    private val selectVideosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            processVideoUris(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        analyzer = VideoOcrAnalyzer(this)
        dbHelper = TagDbHelper(this)

        initViews()
        setupListeners()

        // 앱 시작 시 이전에 분석해 둔 전체 태그 DB 목록 자동 복원
        loadExistingCachedVideos()
    }

    private fun initViews() {
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        btnSelectVideos = findViewById(R.id.btnSelectVideos)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        etSearch = findViewById(R.id.etSearch)
        layoutTagChips = findViewById(R.id.layoutTagChips)
        tvResultCount = findViewById(R.id.tvResultCount)
        rvGallery = findViewById(R.id.rvGallery)

        galleryAdapter = GalleryAdapter(
            context = this,
            onPlayInApp = { videoItem -> showInAppPlayerDialog(videoItem) },
            onPlaySamsung = { videoItem -> openSamsungVideoPlayer(videoItem) },
            onTagClicked = { tagText ->
                etSearch.setText(tagText)
                etSearch.setSelection(tagText.length)
            }
        )

        rvGallery.layoutManager = LinearLayoutManager(this)
        rvGallery.adapter = galleryAdapter
    }

    private fun setupListeners() {
        btnSelectFolder.setOnClickListener {
            selectFolderLauncher.launch(null)
        }

        btnSelectVideos.setOnClickListener {
            selectVideosLauncher.launch("video/*")
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterGallery(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    /**
     * 앱 실행 시 기존에 분석하여 저장해 둔 태그 DB 목록을 즉시 갤러리에 불러옵니다.
     */
    private fun loadExistingCachedVideos() {
        val cached = dbHelper.getAllCachedVideos()
        if (cached.isNotEmpty()) {
            allVideoList.clear()
            allVideoList.addAll(cached)
            tvStatus.text = "💡 기존 태그 DB에서 ${cached.size}개 영상의 태그를 복원했습니다."
            filterGallery(etSearch.text.toString())
            refreshTagChips()
        }
    }

    /**
     * 폴더 URI로부터 하위의 모든 비디오 파일을 탐색합니다.
     */
    private fun scanFolderUri(treeUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                tvStatus.text = "📁 폴더 내 동영상 파일을 검색하는 중..."
            }

            val docFile = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
            val foundUris = mutableListOf<Pair<Uri, String>>()

            docFile?.listFiles()?.forEach { file ->
                if (file.isFile && isVideoFile(file)) {
                    val name = file.name ?: "영상_${System.currentTimeMillis()}"
                    foundUris.add(Pair(file.uri, name))
                }
            }

            withContext(Dispatchers.Main) {
                progressBar.isIndeterminate = false
                if (foundUris.isEmpty()) {
                    progressBar.visibility = View.GONE
                    tvStatus.text = "⚠️ 선택한 폴더에 동영상 파일이 없습니다."
                    Toast.makeText(this@MainActivity, "폴더에 동영상이 없습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    processVideoList(foundUris)
                }
            }
        }
    }

    private fun isVideoFile(file: DocumentFile): Boolean {
        val type = file.type ?: ""
        val name = file.name?.lowercase() ?: ""
        return type.startsWith("video/") ||
                name.endsWith(".mp4") || name.endsWith(".mkv") ||
                name.endsWith(".mov") || name.endsWith(".avi") ||
                name.endsWith(".webm")
    }

    private fun processVideoUris(uris: List<Uri>) {
        val list = uris.map { uri ->
            Pair(uri, queryFileName(uri))
        }
        processVideoList(list)
    }

    /**
     * 선택된 비디오 목록에 대해:
     * 1) 이미 분석된 태그 DB가 있는지 0.01초 만에 확인하여 즉시 재사용 (중복 분석 방지)
     * 2) 새로운 비디오만 초반 10초 OCR 분석 수행
     */
    private fun processVideoList(videos: List<Pair<Uri, String>>) {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            var cachedLoadedCount = 0
            val newVideosToAnalyze = mutableListOf<Triple<Uri, String, Long>>()

            // 1단계: 기존 태그 DB 캐시 확인
            for ((uri, name) in videos) {
                val durationMs = queryVideoDuration(uri)
                val cached = dbHelper.getCachedVideo(name, durationMs)
                if (cached != null) {
                    cachedLoadedCount++
                    // 기존 목록에 중복되지 않도록 교체 또는 추가
                    allVideoList.removeAll { it.name == name }
                    allVideoList.add(0, cached)
                } else {
                    newVideosToAnalyze.add(Triple(uri, name, durationMs))
                }
            }

            filterGallery(etSearch.text.toString())
            refreshTagChips()

            if (newVideosToAnalyze.isEmpty()) {
                progressBar.visibility = View.GONE
                tvStatus.text = "✅ 선택한 ${videos.size}개 영상 모두 기존 태그 DB에서 즉시 로드되었습니다! (새로 분석할 필요 없음)"
                Toast.makeText(this@MainActivity, "${cachedLoadedCount}개 영상 기존 태그 즉시 적용 완료", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 2단계: 신규 영상에 대해서만 초반 10초 OCR 분석 수행
            tvStatus.text = "💡 ${videos.size}개 중 ${cachedLoadedCount}개 기존 데이터 적용 완료! (신규 ${newVideosToAnalyze.size}개 초반 10초 분석 시작...)"

            for ((idx, triple) in newVideosToAnalyze.withIndex()) {
                val (uri, name, _) = triple
                val videoItem = analyzer.analyzeVideo(uri, name) { pct, statusText ->
                    progressBar.progress = pct
                    tvStatus.text = "[${idx + 1}/${newVideosToAnalyze.size}] $statusText"
                }

                // 분석 완료 즉시 DB에 영구 저장
                dbHelper.saveVideo(videoItem)

                allVideoList.removeAll { it.name == name }
                allVideoList.add(0, videoItem)

                filterGallery(etSearch.text.toString())
                refreshTagChips()
            }

            progressBar.visibility = View.GONE
            tvStatus.text = "🎉 분석 완료! 총 ${allVideoList.size}개 영상의 태그 갤러리가 준비되었습니다."
            Toast.makeText(this@MainActivity, "모든 영상 태그 분석 완료!", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 파일명과 #태그를 동시에 실시간 검색합니다.
     */
    private fun filterGallery(query: String) {
        val q = query.trim().lowercase().removePrefix("#")
        val filtered = if (q.isEmpty()) {
            allVideoList
        } else {
            allVideoList.filter { item ->
                item.name.lowercase().contains(q) ||
                        item.tags.any { tag -> tag.lowercase().removePrefix("#").contains(q) } ||
                        item.scenes.any { scene -> scene.text.lowercase().contains(q) }
            }
        }

        tvResultCount.text = "갤러리 영상 목록 (총 ${filtered.size}개)"
        galleryAdapter.submitList(filtered)
    }

    /**
     * 검출된 태그들을 상단 가로 스크롤 칩으로 동적 생성합니다.
     */
    private fun refreshTagChips() {
        layoutTagChips.removeAllViews()
        val allTags = allVideoList.flatMap { it.tags }.distinct().take(15)

        for (tag in allTags) {
            val btn = Button(this).apply {
                text = tag
                textSize = 12f
                setTextColor(resources.getColor(R.color.tag_text, null))
                setBackgroundColor(resources.getColor(R.color.tag_bg, null))
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dpToPx(36)
                ).apply {
                    marginEnd = dpToPx(8)
                }
                layoutParams = params
                setOnClickListener {
                    val searchWord = tag.removePrefix("#")
                    etSearch.setText(searchWord)
                    etSearch.setSelection(searchWord.length)
                }
            }
            layoutTagChips.addView(btn)
        }
    }

    /**
     * 3 & 5. 앱 내 즉시 재생 다이얼로그 (글자 검출 시간대 바로가기 포함)
     */
    private fun showInAppPlayerDialog(videoItem: VideoItem) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_in_app_player)

        val tvTitle = dialog.findViewById<TextView>(R.id.tvPlayerTitle)
        val videoView = dialog.findViewById<VideoView>(R.id.videoView)
        val layoutSceneChips = dialog.findViewById<LinearLayout>(R.id.layoutSceneChips)
        val btnClose = dialog.findViewById<Button>(R.id.btnClosePlayer)

        tvTitle.text = videoItem.name

        // 미디어 컨트롤러 붙이기
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        val uri = Uri.parse(videoItem.uriString)
        videoView.setVideoURI(uri)

        // 검출 장면 바로가기 버튼들 생성
        layoutSceneChips.removeAllViews()
        for (scene in videoItem.scenes) {
            val chip = Button(this).apply {
                val secStr = String.format("%02d:%02d", scene.timestampSeconds / 60, scene.timestampSeconds % 60)
                val previewText = if (scene.text.length > 8) scene.text.take(8) + "..." else scene.text
                text = "▶ $secStr ($previewText)"
                textSize = 11f
                setTextColor(resources.getColor(R.color.on_surface, null))
                setBackgroundColor(resources.getColor(R.color.surface_variant, null))
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dpToPx(34)
                ).apply { marginEnd = dpToPx(6) }
                layoutParams = params

                setOnClickListener {
                    videoView.seekTo((scene.timestampSeconds * 1000).toInt())
                    videoView.start()
                }
            }
            layoutSceneChips.addView(chip)
        }

        videoView.setOnPreparedListener {
            videoView.start()
        }

        btnClose.setOnClickListener {
            videoView.stopPlayback()
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * 5. 삼성 동영상 앱으로 열기
     */
    private fun openSamsungVideoPlayer(videoItem: VideoItem) {
        val uri = Uri.parse(videoItem.uriString)

        // 1차 시도: 삼성 기본 비디오 플레이어 패키지 지정 실행
        val samsungIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            setPackage("com.samsung.android.video")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        try {
            startActivity(samsungIntent)
        } catch (e: Exception) {
            // 삼성 플레이어가 없거나 실패 시 기본 안드로이드 비디오 플레이어로 폴백
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            try {
                startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Toast.makeText(this, "재생할 수 있는 동영상 앱이 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun queryFileName(uri: Uri): String {
        var name = "동영상_${System.currentTimeMillis()}.mp4"
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }

    private fun queryVideoDuration(uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
