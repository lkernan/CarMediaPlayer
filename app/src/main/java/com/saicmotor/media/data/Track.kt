package com.saicmotor.media.data

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val path: String,
    val source: Source
) {
    enum class Source { USB, SUBSONIC }
}
