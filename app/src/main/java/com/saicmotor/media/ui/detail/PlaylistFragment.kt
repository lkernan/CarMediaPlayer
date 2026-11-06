package com.saicmotor.media.ui.detail

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.saicmotor.media.R
import com.saicmotor.media.data.Library
import com.saicmotor.media.data.OfflinePlaylistStore
import com.saicmotor.media.service.OfflineDownloads
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.service.PlaylistUsbSync
import com.saicmotor.media.subsonic.SubsonicPlaylistDetail
import com.saicmotor.media.ui.common.Format
import com.saicmotor.media.ui.common.PageButton
import com.saicmotor.media.ui.common.Playback
import com.saicmotor.media.ui.common.Row
import com.saicmotor.media.ui.common.RowsPageFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A Subsonic playlist and its tracks, playable in server order or shuffled. */
class PlaylistFragment : RowsPageFragment() {

    override val followPlaying = true
    private val playlistId get() = requireArguments().getString(ARG_ID)!!
    private val parentId get() = MediaService.playlistNodeId(playlistId)
    private var detail: SubsonicPlaylistDetail? = null
    private var displayedRows: List<Row> = emptyList()

    override suspend fun load(): List<Row> {
        val saved = OfflinePlaylistStore.get(requireContext(), playlistId)
        val detail = if (saved != null && !networkAvailable()) saved else try {
            Library.load("playlist:$playlistId") { playlist(playlistId) }
        } catch (e: Exception) { saved ?: throw e }
        this.detail = detail
        return buildList {
            add(Row.Header(
                title = detail.playlist.name,
                subtitle = detail.playlist.owner,
                subtitleArtistId = null,
                meta = listOfNotNull(
                    Format.songs(requireContext(), detail.songs.size),
                    Format.length(requireContext(), detail.playlist.durationSec.takeIf { it > 0 }
                        ?: (detail.songs.sumOf { it.durationMs } / 1_000).toInt())
                ).joinToString(" · "),
                coverUrl = Library.coverUrl(detail.playlist.coverArt, 600),
                showCover = detail.playlist.coverArt != null,
                round = false,
                primary = PageButton(PLAY, getString(R.string.play), R.drawable.ic_play),
                secondary = PageButton(SHUFFLE, getString(R.string.shuffle), R.drawable.ic_shuffle),
                tertiary = PageButton(OFFLINE, getString(R.string.offline_button), R.drawable.ic_download)
            ))
            detail.songs.forEachIndexed { index, song ->
                add(Row.Track(song, parentId, song.track.takeIf { it > 0 } ?: index + 1,
                    showCover = false, showArtist = true, index = index))
            }
        }.also { displayedRows = it }
    }

    override fun onButton(id: String) {
        when (id) {
            PLAY -> Playback.playAll(parentId, shuffle = false)
            SHUFFLE -> Playback.playAll(parentId, shuffle = true)
            OFFLINE -> showOfflineOptions()
        }
    }

    private fun showOfflineOptions() {
        val items = mutableListOf(R.string.offline_download_action, R.string.offline_usb_action)
        if (OfflinePlaylistStore.isSaved(requireContext(), playlistId)) items += R.string.offline_remove_action
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.offline_button))
            .setItems(items.map(::getString).toTypedArray()) { _, which ->
                when (items[which]) {
                    R.string.offline_download_action -> downloadOffline()
                    R.string.offline_usb_action -> syncToUsb(PlaylistUsbSync.savedTree(requireContext()))
                    R.string.offline_remove_action -> {
                        OfflineDownloads.removePlaylist(requireContext(), playlistId)
                        Toast.makeText(requireContext(), R.string.offline_removed, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }.show()
    }

    private fun networkAvailable(): Boolean = try {
        val cm = requireContext().getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { true }

    private fun downloadOffline() {
        val playlist = detail ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                OfflineDownloads.savePlaylist(requireContext(), playlist) { done, total ->
                    withContext(Dispatchers.Main) { showProgress(getString(R.string.offline_progress, done, total)) }
                }
                Toast.makeText(requireContext(), R.string.offline_saved, Toast.LENGTH_LONG).show()
                reload()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: getString(R.string.offline_failed), Toast.LENGTH_LONG).show()
                show(displayedRows)
            }
        }
    }

    private fun showProgress(message: String) {
        if (isAdded && view != null) show(listOf(Row.Message(message)) + displayedRows)
    }

    private fun syncToUsb(tree: Uri?) {
        if (tree == null || !PlaylistUsbSync.hasWriteAccess(requireContext(), tree)) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            try { startActivityForResult(intent, REQUEST_USB) }
            catch (_: Exception) { Toast.makeText(requireContext(), R.string.offline_usb_failed, Toast.LENGTH_LONG).show() }
        } else runUsbSync(tree)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_USB || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            PlaylistUsbSync.rememberTree(requireContext(), uri)
            runUsbSync(uri)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.message ?: getString(R.string.offline_usb_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun runUsbSync(tree: Uri) {
        val playlist = detail ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = requireContext().applicationContext
                withContext(Dispatchers.IO) {
                    PlaylistUsbSync.sync(ctx, tree, playlist) { done, total ->
                        list.post { if (isAdded) showProgress(getString(R.string.offline_progress, done, total)) }
                    }
                }
                Toast.makeText(requireContext(), R.string.offline_usb_done, Toast.LENGTH_LONG).show()
                show(displayedRows)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: getString(R.string.offline_usb_failed), Toast.LENGTH_LONG).show()
                show(displayedRows)
            }
        }
    }

    companion object {
        private const val ARG_ID = "playlist_id"
        private const val PLAY = "play"
        private const val SHUFFLE = "shuffle"
        private const val OFFLINE = "offline"
        private const val REQUEST_USB = 2417

        fun newInstance(playlistId: String) = PlaylistFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, playlistId) }
        }
    }
}
