package com.saicmotor.media.ui.adapter

import android.support.v4.media.MediaBrowserCompat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.saicmotor.media.databinding.ItemTrackBinding

class TrackAdapter(
    private val onTrackClick: (MediaBrowserCompat.MediaItem) -> Unit
) : ListAdapter<MediaBrowserCompat.MediaItem, TrackAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaBrowserCompat.MediaItem) {
            binding.trackTitle.text  = item.description.title
            binding.trackArtist.text = item.description.subtitle
            binding.root.setOnClickListener { onTrackClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTrackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaBrowserCompat.MediaItem>() {
            override fun areItemsTheSame(
                a: MediaBrowserCompat.MediaItem, b: MediaBrowserCompat.MediaItem
            ) = a.mediaId == b.mediaId

            override fun areContentsTheSame(
                a: MediaBrowserCompat.MediaItem, b: MediaBrowserCompat.MediaItem
            ) = a.mediaId == b.mediaId &&
                a.description.title    == b.description.title &&
                a.description.subtitle == b.description.subtitle
        }
    }
}
