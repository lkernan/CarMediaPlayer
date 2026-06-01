package com.saicmotor.media.ui.adapter

import android.support.v4.media.MediaBrowserCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.saicmotor.media.R
import com.saicmotor.media.databinding.ItemBrowseBinding

class BrowseAdapter(
    private val onBrowsable: (MediaBrowserCompat.MediaItem) -> Unit,
    private val onPlayable:  (MediaBrowserCompat.MediaItem) -> Unit,
) : ListAdapter<MediaBrowserCompat.MediaItem, BrowseAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemBrowseBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaBrowserCompat.MediaItem) {
            binding.itemTitle.text    = item.description.title
            val subtitle = item.description.subtitle
            if (!subtitle.isNullOrBlank()) {
                binding.itemSubtitle.text       = subtitle
                binding.itemSubtitle.visibility = View.VISIBLE
            } else {
                binding.itemSubtitle.visibility = View.GONE
            }
            binding.itemChevron.visibility =
                if (item.isBrowsable) View.VISIBLE else View.GONE

            // Load thumbnail — iconUri is set for albums and tracks; null for artists
            binding.itemThumb.load(item.description.iconUri) {
                placeholder(R.drawable.ic_album_art_placeholder)
                error(R.drawable.ic_album_art_placeholder)
            }

            binding.root.setOnClickListener {
                if (item.isBrowsable) onBrowsable(item) else onPlayable(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBrowseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaBrowserCompat.MediaItem>() {
            override fun areItemsTheSame(a: MediaBrowserCompat.MediaItem, b: MediaBrowserCompat.MediaItem) =
                a.mediaId == b.mediaId
            override fun areContentsTheSame(a: MediaBrowserCompat.MediaItem, b: MediaBrowserCompat.MediaItem) =
                a.mediaId == b.mediaId
                && a.description.title    == b.description.title
                && a.description.subtitle == b.description.subtitle
                && a.description.iconUri  == b.description.iconUri
        }
    }
}
