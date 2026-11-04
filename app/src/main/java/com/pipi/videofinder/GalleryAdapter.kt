package com.pipi.videofinder

import android.content.Context
import android.graphics.BitmapFactory
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
    private val onItemClicked: (VideoItem) -> Unit
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

        fun bind(item: VideoItem) {
            tvDuration.text = formatDuration(item.durationMs)

            // 1. 메모리에 비트맵이 이미 있으면 즉시 표시
            if (item.thumbnailBitmap != null) {
                ivThumbnail.setImageBitmap(item.thumbnailBitmap)
            } else {
                // 2. 앱 전용 디스크에 저장된 썸네일 파일 확인 (앱 껐다 켜도 1ms 즉시 로드)
                val thumbFile = ThumbnailHelper.getThumbnailFile(context, item.name, item.durationMs)
                if (thumbFile.exists()) {
                    val bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                    if (bmp != null) {
                        item.thumbnailBitmap = bmp
                        ivThumbnail.setImageBitmap(bmp)
                    } else {
                        loadThumbnailAsync(item)
                    }
                } else {
                    loadThumbnailAsync(item)
                }
            }

            itemView.setOnClickListener {
                onItemClicked(item)
            }
        }

        private fun loadThumbnailAsync(item: VideoItem) {
            ivThumbnail.setImageDrawable(null)
            Thread {
                val bmp = ThumbnailHelper.loadOrExtractThumbnail(
                    context,
                    Uri.parse(item.uriString),
                    item.name,
                    item.durationMs
                )
                if (bmp != null) {
                    item.thumbnailBitmap = bmp
                    itemView.post { ivThumbnail.setImageBitmap(bmp) }
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
