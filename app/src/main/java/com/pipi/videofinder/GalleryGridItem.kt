package com.pipi.videofinder

sealed class GalleryGridItem {
    data class Header(val dateTitle: String) : GalleryGridItem()
    data class Video(val videoItem: VideoItem) : GalleryGridItem()
}
