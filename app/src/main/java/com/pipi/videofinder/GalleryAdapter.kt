package com.pipi.videofinder

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class GalleryAdapter(
    private val context: Context,
    private val onPlayInApp: (VideoItem) -> Unit,
    private val onPlaySamsung: (VideoItem) -> Unit,
    private val onTagClicked: (String) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

    private val items = mutableListOf<VideoItem>()

    fun submitList(newItems: List<VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video_gallery, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvBadge)
        private val tvTags: TextView = itemView.findViewById(R.id.tvTags)
        private val btnPlayInApp: Button = itemView.findViewById(R.id.btnPlayInApp)
        private val btnPlaySamsung: Button = itemView.findViewById(R.id.btnPlaySamsung)

        fun bind(item: VideoItem) {
            tvFileName.text = item.name
            tvDuration.text = formatDuration(item.durationMs)

            // 썸네일 표시
            if (item.thumbnailBitmap != null) {
                ivThumbnail.setImageBitmap(item.thumbnailBitmap)
            } else {
                // 비동기로 썸네일 로드 시도
                loadThumbnailAsync(item)
            }

            // 기존 분석 캐시 여부 배지
            if (item.isCached) {
                tvBadge.text = "✅ 기존 분석 완료 (${item.tags.size}개 태그 보존)"
                tvBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.badge_cached))
                tvBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_cached_text))
            } else {
                tvBadge.text = "⚡ 신규 분석 완료 (${item.tags.size}개 태그 생성)"
                tvBadge.setBackgroundColor(ContextCompat.getColor(context, R.color.badge_new))
                tvBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_new_text))
            }

            // 태그 표시
            if (item.tags.isNotEmpty()) {
                tvTags.text = item.tags.joinToString(" ")
                tvTags.visibility = View.VISIBLE
            } else {
                tvTags.text = "#태그없음"
                tvTags.visibility = View.VISIBLE
            }

            // 태그 클릭 시 검색창 자동 입력
            tvTags.setOnClickListener {
                if (item.tags.isNotEmpty()) {
                    onTagClicked(item.tags.first().removePrefix("#"))
                }
            }

            // 버튼 동작 연결
            btnPlayInApp.setOnClickListener { onPlayInApp(item) }
            btnPlaySamsung.setOnClickListener { onPlaySamsung(item) }
        }

        private fun loadThumbnailAsync(item: VideoItem) {
            ivThumbnail.setImageDrawable(null)
            Thread {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, Uri.parse(item.uriString))
                    val bmp = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    if (bmp != null) {
                        item.thumbnailBitmap = bmp
                        itemView.post { ivThumbnail.setImageBitmap(bmp) }
                    }
                } catch (e: Exception) {
                    // 무시
                }
            }.start()
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}
