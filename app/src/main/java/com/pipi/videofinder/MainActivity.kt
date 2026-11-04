package com.pipi.videofinder

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var analyzer: VideoOcrAnalyzer
    private lateinit var dbHelper: TagDbHelper

    private val allVideoList = mutableListOf<VideoItem>()
    private lateinit var galleryAdapter: GalleryAdapter

    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnFile: Button
    private lateinit var btnConvert: Button
    private lateinit var btnSearch: Button

    private lateinit var layoutSearchBox: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnCloseSearch: ImageButton
    private lateinit var rvGallery: RecyclerView

    private var isAnalyzing = false

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
            loadVideosToPending(uris)
        }
    }

    // 3. 삼성 갤러리 인텐트 런처
    private val samsungGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = mutableListOf<Uri>()
        val data = result.data
        if (data != null) {
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } else if (data.data != null) {
                uris.add(data.data!!)
            }
        }
        if (uris.isNotEmpty()) {
            loadVideosToPending(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        analyzer = VideoOcrAnalyzer(this)
        dbHelper = TagDbHelper(this)

        initViews()
        setupListeners()

        // 앱 시작 시 이전에 분석해 둔 전체 태그 DB 목록 복원
        loadExistingCachedVideos()
    }

    private fun initViews() {
        tvProgress = findViewById(R.id.tvProgress)
        progressBar = findViewById(R.id.progressBar)
        btnFile = findViewById(R.id.btnFile)
        btnConvert = findViewById(R.id.btnConvert)
        btnSearch = findViewById(R.id.btnSearch)

        layoutSearchBox = findViewById(R.id.layoutSearchBox)
        etSearch = findViewById(R.id.etSearch)
        btnCloseSearch = findViewById(R.id.btnCloseSearch)
        rvGallery = findViewById(R.id.rvGallery)

        galleryAdapter = GalleryAdapter(
            context = this,
            onItemClicked = { videoItem -> showVideoDetailDialog(videoItem) }
        )

        // 삼성 갤러리 스타일의 3열 그리드 레이아웃 설정
        val gridLayoutManager = GridLayoutManager(this, 3)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (galleryAdapter.isHeader(position)) 3 else 1
            }
        }

        rvGallery.layoutManager = gridLayoutManager
        rvGallery.adapter = galleryAdapter
    }

    private fun setupListeners() {
        // [파일] 버튼: 폴더 전체 또는 삼성 갤러리 선택
        btnFile.setOnClickListener {
            showFileSourceDialog()
        }

        // [변환] 버튼: 대기 영상들의 초반 10초 OCR 분석 실행
        btnConvert.setOnClickListener {
            startConversionProcess()
        }

        // [검색] 버튼: 검색창 토글
        btnSearch.setOnClickListener {
            toggleSearchBox()
        }

        // 검색창 닫기 버튼
        btnCloseSearch.setOnClickListener {
            closeSearchBox()
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
     * [파일] 버튼 클릭 시 폴더 선택과 삼성 갤러리 선택 제공
     */
    private fun showFileSourceDialog() {
        val options = arrayOf("📁 폴더 전체 불러오기 (한 번에 일괄 등록)", "🎬 삼성 갤러리에서 동영상 선택")
        AlertDialog.Builder(this)
            .setTitle("동영상 불러오기")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> selectFolderLauncher.launch(null)
                    1 -> openSamsungGalleryPicker()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openSamsungGalleryPicker() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            setPackage("com.sec.android.gallery3d")
        }
        try {
            samsungGalleryLauncher.launch(intent)
        } catch (e: Exception) {
            selectVideosLauncher.launch("video/*")
        }
    }

    private fun toggleSearchBox() {
        if (layoutSearchBox.visibility == View.VISIBLE) {
            closeSearchBox()
        } else {
            layoutSearchBox.visibility = View.VISIBLE
            etSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeSearchBox() {
        etSearch.setText("")
        layoutSearchBox.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun openSearchWithText(tagText: String) {
        layoutSearchBox.visibility = View.VISIBLE
        etSearch.setText(tagText)
        etSearch.setSelection(tagText.length)
        etSearch.requestFocus()
    }

    /**
     * 앱 시작 시 이전에 분석하여 저장해 둔 태그 DB 목록을 즉시 복원합니다.
     */
    private fun loadExistingCachedVideos() {
        val cached = dbHelper.getAllCachedVideos()
        if (cached.isNotEmpty()) {
            allVideoList.clear()
            allVideoList.addAll(cached)
            tvProgress.text = "[완료 ${cached.size}/${cached.size} (100%)]"
            filterGallery(etSearch.text.toString())
        }
    }

    /**
     * 폴더 URI로부터 하위의 모든 비디오 파일을 대기 목록으로 탐색하여 등록합니다.
     * 바로 분석하지 않고 대기 상태로 등록합니다.
     */
    private fun scanFolderUri(treeUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val docFile = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
            val foundUris = mutableListOf<Pair<Uri, String>>()

            docFile?.listFiles()?.forEach { file ->
                if (file.isFile && isVideoFile(file)) {
                    val name = file.name ?: "영상_${System.currentTimeMillis()}"
                    foundUris.add(Pair(file.uri, name))
                }
            }

            withContext(Dispatchers.Main) {
                if (foundUris.isEmpty()) {
                    Toast.makeText(this@MainActivity, "선택한 폴더에 동영상이 없습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    addVideosToGallery(foundUris)
                }
            }
        }
    }

    private fun loadVideosToPending(uris: List<Uri>) {
        val list = uris.map { uri ->
            Pair(uri, queryFileName(uri))
        }
        addVideosToGallery(list)
    }

    /**
     * 선택된 비디오들을 목록에 등록합니다 (바로 분석하지 않고 대기 상태로 등록!)
     */
    private fun addVideosToGallery(videos: List<Pair<Uri, String>>) {
        for ((uri, name) in videos) {
            val durationMs = queryVideoDuration(uri)
            val cached = dbHelper.getCachedVideo(name, durationMs)
            if (cached != null) {
                allVideoList.removeAll { it.name == name }
                allVideoList.add(cached)
            } else {
                allVideoList.removeAll { it.name == name }
                allVideoList.add(
                    VideoItem(
                        uriString = uri.toString(),
                        name = name,
                        durationMs = durationMs,
                        tags = listOf("#분석대기"),
                        scenes = emptyList(),
                        isCached = false
                    )
                )
            }
        }

        updateProgressHeader()
        filterGallery(etSearch.text.toString())

        Toast.makeText(this, "${videos.size}개 영상 등록 완료! '변환' 버튼을 누르면 분석이 시작됩니다.", Toast.LENGTH_SHORT).show()
    }

    private fun updateProgressHeader() {
        val total = allVideoList.size
        if (total == 0) {
            tvProgress.text = ""
            return
        }
        val analyzed = allVideoList.count { it.isCached }
        val pct = (analyzed * 100) / total
        tvProgress.text = "[$analyzed/$total ($pct%)]"
    }

    /**
     * [변환] 버튼 클릭 시: 미분석 영상을 순차적으로 초반 10초 OCR 분석 수행
     */
    private fun startConversionProcess() {
        if (isAnalyzing) {
            Toast.makeText(this, "이미 변환이 진행 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (allVideoList.isEmpty()) {
            Toast.makeText(this, "먼저 '파일' 버튼을 눌러 영상을 불러와 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val unanalyzed = allVideoList.filter { !it.isCached }
        val targetList = if (unanalyzed.isNotEmpty()) {
            unanalyzed
        } else {
            // 모든 영상이 이미 분석 완료된 상태에서 변환을 눌렀을 경우 전체 재분석 확인
            AlertDialog.Builder(this)
                .setTitle("전체 재변환")
                .setMessage("모든 영상이 이미 분석되어 있습니다. 전체 영상을 다시 한글로 변환(재분석)할까요?")
                .setPositiveButton("재변환") { _, _ ->
                    dbHelper.clearAll()
                    runBatchAnalysis(allVideoList.map { it.copy(isCached = false) })
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }

        runBatchAnalysis(targetList)
    }

    private fun runBatchAnalysis(targetList: List<VideoItem>) {
        lifecycleScope.launch {
            isAnalyzing = true
            progressBar.visibility = View.VISIBLE
            progressBar.max = 100

            val total = allVideoList.size

            for (item in targetList) {
                val uri = Uri.parse(item.uriString)
                val analyzedItem = analyzer.analyzeVideo(uri, item.name) { _, _ -> }

                // DB 저장
                dbHelper.saveVideo(analyzedItem)

                // 메모리 리스트 업데이트
                val targetIndex = allVideoList.indexOfFirst { it.name == item.name }
                if (targetIndex != -1) {
                    allVideoList[targetIndex] = analyzedItem
                } else {
                    allVideoList.add(analyzedItem)
                }

                // 최상단 진척도 표시: [진행파일/전체파일 (진척률%)]
                val currentAnalyzed = allVideoList.count { it.isCached }
                val overallPct = (currentAnalyzed * 100) / total
                tvProgress.text = "[$currentAnalyzed/$total ($overallPct%)]"
                progressBar.progress = overallPct

                filterGallery(etSearch.text.toString())
            }

            progressBar.visibility = View.GONE
            isAnalyzing = false
            tvProgress.text = "[완료 $total/$total (100%)]"
            Toast.makeText(this@MainActivity, "전체 영상 변환이 완료되었습니다! 🎉", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 검색어 필터링 및 삼성 갤러리 스타일 날짜별 기본 정렬 (최신순)
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

        // 날짜순(최신순) 기본 정렬
        val sortedVideos = filtered.sortedByDescending { getVideoSortTimestamp(it.name) }

        // 날짜별 그룹화
        val groupedMap = linkedMapOf<String, MutableList<VideoItem>>()
        for (video in sortedVideos) {
            val dateGroupTitle = extractDateGroupTitle(video.name)
            val list = groupedMap.getOrPut(dateGroupTitle) { mutableListOf() }
            list.add(video)
        }

        val gridItems = mutableListOf<GalleryGridItem>()
        for ((dateTitle, videos) in groupedMap) {
            gridItems.add(GalleryGridItem.Header(dateTitle))
            for (v in videos) {
                gridItems.add(GalleryGridItem.Video(v))
            }
        }

        galleryAdapter.submitItems(gridItems)
    }

    /**
     * 파일명에서 정렬용 타임스탬프 추출 (예: 20261031)
     */
    private fun getVideoSortTimestamp(fileName: String): Long {
        val regex = Regex("20\\d{2}(\\d{2})(\\d{2})")
        val match = regex.find(fileName)
        return match?.value?.toLongOrNull() ?: 0L
    }

    /**
     * 파일명에서 날짜 그룹 제목(예: 10월 31일)을 추출합니다.
     */
    private fun extractDateGroupTitle(fileName: String): String {
        val regex = Regex("20\\d{2}(\\d{2})(\\d{2})")
        val match = regex.find(fileName)
        if (match != null) {
            val month = match.groupValues[1].toIntOrNull() ?: 0
            val day = match.groupValues[2].toIntOrNull() ?: 0
            if (month in 1..12 && day in 1..31) {
                return "${month}월 ${day}일"
            }
        }
        return "기타 동영상"
    }

    /**
     * 썸네일 클릭 시: 상단에서 바로 동영상 자동 재생 + 하단에 파일명, 태그, 삼성 동영상 재생 버튼 표시
     */
    private fun showVideoDetailDialog(videoItem: VideoItem) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_video_detail)

        val videoView = dialog.findViewById<VideoView>(R.id.detailVideoView)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvDetailTitle)
        val tvDateInfo = dialog.findViewById<TextView>(R.id.tvDetailDateInfo)
        val layoutScenes = dialog.findViewById<LinearLayout>(R.id.layoutDetailScenes)
        val tvTags = dialog.findViewById<TextView>(R.id.tvDetailTags)
        val btnPlaySamsung = dialog.findViewById<Button>(R.id.btnDetailPlaySamsung)
        val btnClose = dialog.findViewById<Button>(R.id.btnDetailClose)

        tvTitle.text = videoItem.name

        val sec = videoItem.durationMs / 1000
        val durStr = String.format("%02d:%02d", sec / 60, sec % 60)
        val dateTitle = extractDateGroupTitle(videoItem.name)
        val statusStr = if (videoItem.isCached) "OCR 완료" else "대기 중"
        tvDateInfo.text = "$dateTitle · 재생시간 $durStr · $statusStr"

        tvTags.text = if (videoItem.tags.isNotEmpty()) videoItem.tags.joinToString(" ") else "#분석대기"

        // 태그 터치 시 해당 태그로 검색창 열기
        tvTags.setOnClickListener {
            if (videoItem.tags.isNotEmpty()) {
                videoView.stopPlayback()
                dialog.dismiss()
                openSearchWithText(videoItem.tags.first().removePrefix("#"))
            }
        }

        // 미디어 컨트롤러 설정 및 즉시 재생 시작
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        val uri = Uri.parse(videoItem.uriString)
        videoView.setVideoURI(uri)

        // 자막 검출 장면 바로가기 버튼 생성
        layoutScenes.removeAllViews()
        for (scene in videoItem.scenes) {
            val chip = Button(this).apply {
                val s = String.format("%02d:%02d", scene.timestampSeconds / 60, scene.timestampSeconds % 60)
                val preview = if (scene.text.length > 8) scene.text.take(8) + "..." else scene.text
                text = "▶ $s ($preview)"
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
            layoutScenes.addView(chip)
        }

        videoView.setOnPreparedListener {
            videoView.start()
        }

        // 삼성 동영상 플레이어로 바로 열기 버튼
        btnPlaySamsung.setOnClickListener {
            videoView.stopPlayback()
            dialog.dismiss()
            openSamsungVideoPlayer(videoItem)
        }

        btnClose.setOnClickListener {
            videoView.stopPlayback()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            videoView.stopPlayback()
        }

        dialog.show()
    }

    /**
     * 삼성 동영상 앱으로 열기
     */
    private fun openSamsungVideoPlayer(videoItem: VideoItem) {
        val uri = Uri.parse(videoItem.uriString)

        val samsungIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            setPackage("com.sec.android.gallery3d")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        try {
            startActivity(samsungIntent)
        } catch (e: Exception) {
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

    private fun isVideoFile(file: DocumentFile): Boolean {
        val type = file.type ?: ""
        val name = file.name?.lowercase() ?: ""
        return type.startsWith("video/") ||
                name.endsWith(".mp4") || name.endsWith(".mkv") ||
                name.endsWith(".mov") || name.endsWith(".avi") ||
                name.endsWith(".webm")
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
