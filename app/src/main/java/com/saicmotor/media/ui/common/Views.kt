package com.saicmotor.media.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.saicmotor.media.R
import com.saicmotor.media.data.Library
import com.saicmotor.media.databinding.ViewLoadStateBinding
import com.saicmotor.media.subsonic.SubsonicException

/** A cover image that is as tall as it is wide, unless given an exact height. */
class SquareImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ShapeableImageView(context, attrs, defStyle) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(measuredWidth, measuredWidth)
        }
    }
}

/**
 * Holds the now-playing panel: as wide as it is tall (so the cover fills it),
 * but never more than [MAX_FRACTION] of the screen width.
 */
class SquarePanelLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val width  = minOf(height, (MeasureSpec.getSize(widthMeasureSpec) * MAX_FRACTION).toInt())
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    companion object {
        const val MAX_FRACTION = 0.42f
    }
}

/**
 * Covers a page while it loads, and explains what to do when it can't:
 * not set up yet, server unreachable, login refused, or nothing there.
 */
class LoadStateView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val b = ViewLoadStateBinding.inflate(LayoutInflater.from(context), this)

    fun showLoading() {
        visibility = View.VISIBLE
        b.progress.visibility = View.VISIBLE
        b.message.visibility  = View.GONE
    }

    fun showContent() {
        visibility = View.GONE
    }

    fun showEmpty(text: CharSequence) {
        visibility = View.VISIBLE
        b.progress.visibility = View.GONE
        b.message.visibility  = View.VISIBLE
        b.icon.visibility     = View.GONE
        b.title.visibility    = View.GONE
        b.body.text           = text
        b.action.visibility   = View.GONE
    }

    /** [retry] reloads the page; [openSettings] goes to Settings. */
    fun showError(e: Throwable, retry: () -> Unit, openSettings: () -> Unit) {
        val kind = (e as? SubsonicException)?.kind ?: SubsonicException.Kind.NETWORK
        val (title, body, action) = when (kind) {
            SubsonicException.Kind.NOT_CONFIGURED -> Triple(
                R.string.error_not_configured_title, context.getString(R.string.error_not_configured_body), R.string.open_settings)
            SubsonicException.Kind.NETWORK -> Triple(
                R.string.error_network_title,
                listOfNotNull(Library.serverName, context.getString(R.string.error_network_body)).joinToString("\n"),
                R.string.try_again)
            SubsonicException.Kind.AUTH -> Triple(
                R.string.error_auth_title, context.getString(R.string.error_auth_body), R.string.open_settings)
            SubsonicException.Kind.SERVER -> Triple(
                R.string.error_server_title, e.message.orEmpty(), R.string.try_again)
        }
        visibility = View.VISIBLE
        b.progress.visibility = View.GONE
        b.message.visibility  = View.VISIBLE
        b.icon.visibility     = View.VISIBLE
        b.title.visibility    = View.VISIBLE
        b.title.setText(title)
        b.body.text = body
        b.action.visibility = View.VISIBLE
        b.action.setText(action)
        b.action.setOnClickListener { if (action == R.string.open_settings) openSettings() else retry() }
    }
}

// ── Images ────────────────────────────────────────────────────────────────────

fun ImageView.loadCover(url: String?, @DrawableRes placeholder: Int = R.drawable.cover_placeholder) {
    load(url) {
        placeholder(placeholder)
        error(placeholder)
        fallback(placeholder)
        crossfade(180)
        coverCacheKey(url)?.let { memoryCacheKey(it); diskCacheKey(it) }
    }
}

/**
 * Cover URLs carry a login token that changes each time the app starts.
 * Caching by the URL without it means covers survive restarts.
 */
fun coverCacheKey(url: String?): String? {
    if (url == null || !url.contains("/rest/getCoverArt")) return url
    val base  = url.substringBefore('?')
    val query = url.substringAfter('?', "").split('&')
        .filterNot { it.substringBefore('=') in AUTH_PARAMS }
        .joinToString("&")
    return "$base?$query"
}

private val AUTH_PARAMS = setOf("u", "t", "s", "v", "c", "f", "p")

// ── Formatting ────────────────────────────────────────────────────────────────

object Format {
    /** 3:07, 1:02:45 */
    fun clock(ms: Long): String {
        if (ms < 0) return "–:––"
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
               else "%d:%02d".format(s / 60, s % 60)
    }

    /** "48 min", "1 hr 12 min" */
    fun length(context: Context, seconds: Int): String? {
        if (seconds <= 0) return null
        val minutes = (seconds + 30) / 60
        return if (minutes >= 60) context.getString(R.string.hours_minutes, minutes / 60, minutes % 60)
               else context.getString(R.string.minutes, minutes.coerceAtLeast(1))
    }

    fun songs(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.songs, count, count)

    fun albums(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.albums, count, count)
}

/** Navigation between pages, implemented by MainActivity. */
interface Navigator {
    fun openAlbum(albumId: String)
    fun openArtist(artistId: String)
    fun openPlaylist(playlistId: String)
    fun openGenre(name: String)
    fun openSettings()
    fun openHome()
}
