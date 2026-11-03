// com/pipi/videofinder/SearchResultAdapter.kt
package com.pipi.videofinder

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SearchResultAdapter(
    private val onClick: (VideoSearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    private var items: List<VideoSearchResult> = emptyList()
    private var currentQuery: String = ""

    fun submitList(newItems: List<VideoSearchResult>, query: String) {
        items = newItems
        currentQuery = query
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], currentQuery, onClick)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
        private val tvTimeBadge: TextView = itemView.findViewById(R.id.tvTimeBadge)
        private val tvVideoName: TextView = itemView.findViewById(R.id.tvVideoName)
        private val tvSnippet: TextView = itemView.findViewById(R.id.tvSnippet)

        fun bind(item: VideoSearchResult, query: String, onClick: (VideoSearchResult) -> Unit) {
            if (item.thumbnailBitmap != null) {
                ivThumb.setImageBitmap(item.thumbnailBitmap)
            } else {
                ivThumb.setBackgroundColor(Color.DKGRAY)
            }

            tvTimeBadge.text = "⏱ ${item.timeFormatted}"
            tvVideoName.text = "🎥 ${item.videoName}"

            val fullText = item.recognizedText
            if (query.isNotEmpty() && fullText.contains(query, ignoreCase = true)) {
                val span = SpannableString(fullText)
                val start = fullText.indexOf(query, ignoreCase = true)
                if (start >= 0) {
                    val end = start + query.length
                    span.setSpan(BackgroundColorSpan(Color.parseColor("#FEF08A")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    span.setSpan(ForegroundColorSpan(Color.parseColor("#854D0E")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tvSnippet.text = span
            } else {
                tvSnippet.text = fullText
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }
}
