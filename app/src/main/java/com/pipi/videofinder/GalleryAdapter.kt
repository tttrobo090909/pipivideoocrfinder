package com.pipi.videofinder

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class GalleryAdapter(
    private val context: Context,
    private val onItemClicked: (VideoItem) -> Unit,
    private val onTagClicked: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_VIDEO = 1
    }

    private val items = mutableListOf<GalleryGridItem>()

    fun submitItems(newItems: List<GalleryGridItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun isHeader(position: Int): Boolean {
        return position in items.indices && items[position] is GalleryGridItem.Header
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is GalleryGridItem.Header -> TYPE_HEADER
            is GalleryGridItem.Video -> TYPE_VIDEO
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_gallery_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_gallery_video_grid, parent, false)
            VideoViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is GalleryGridItem.Header -> (holder as HeaderViewHolder).bind(item)
            is GalleryGridItem.Video -> (holder as VideoViewHolder).bind(item.videoItem)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvHeaderDate: TextView = itemView.findViewById(R.id.tvHeaderDate)
        fun bind(header: GalleryGridItem.Header) {
            tvHeaderDate.text = header.dateTitle
        }
    }

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivGridThumbnail)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvGridDuration)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvGridStatus)
        private val tvTags: TextView = itemView.findViewById(R.id.tvGridTags)

        fun bind(item: VideoItem) {
            tvDuration.text = formatDuration(item.durationMs)

            // 썸네일 로드
            if (item.thumbnailBitmap != null) {
                ivThumbnail.setImageBitmap(item.thumbnailBitmap)
            } else {
                loadThumbnailAsync(item)
            }

            // 분석 상태 뱃지
            if (item.isCached) {
                tvStatus.text = "✓"
                tvStatus.setTextColor(0xFF81C784.toInt())
            } else {
                tvStatus.text = "대기"
                tvStatus.setTextColor(0xFFFFB74D.toInt())
            }

            // 태그 요약 (상위 1~2개 표시)
            if (item.tags.isNotEmpty()) {
                tvTags.text = item.tags.joinToString(" ")
            } else {
                tvTags.text = "#분석대기"
            }

            itemView.setOnClickListener {
                onItemClicked(item)
            }

            tvTags.setOnClickListener {
                if (item.tags.isNotEmpty()) {
                    onTagClicked(item.tags.first().removePrefix("#"))
                }
            }
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
