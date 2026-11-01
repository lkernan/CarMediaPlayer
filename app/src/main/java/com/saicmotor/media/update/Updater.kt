package com.saicmotor.media.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.saicmotor.media.data.Storage
import com.saicmotor.media.ui.common.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Check for updates" in Settings: looks at the latest release on GitHub and,
 * if it's newer than this version, downloads its APK and installs it.
 * Only ever runs when that button is pressed.
 *
 * The app shares the system UID and carries the platform signature, so it
 * may install packages itself: the update goes in without a prompt, the app
 * is restarted by the system, and [afterUpdate] brings it back (and the
 * music, if it was playing).  If the head unit wants the install confirmed
 * instead, its installer screen is shown.
 */
object Updater {

    /** Where releases are published: a tag "v<versionName>" with the signed APK attached. */
    const val REPO = "MintiesJZ/CarMediaPlayer"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private const val TAG = "CarMedia.Update"

    sealed class Status {
        object Idle : Status()
        object Checking : Status()
        data class UpToDate(val version: String) : Status()
        data class Downloading(val version: String, val percent: Int) : Status()
        data class Installing(val version: String) : Status()
        /** The head unit's installer is asking for the install to be confirmed. */
        object Confirming : Status()
        data class Failed(val problem: Problem, val detail: String? = null) : Status()
    }

    enum class Problem { NETWORK, NO_RELEASE, NO_APK, BAD_FILE, INSTALL }

    data class Release(val version: String, val apkUrl: String?, val apkSize: Long)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Checks GitHub; if there's a newer version, downloads and installs it. */
    fun checkAndInstall(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { run(app) }
    }

    private fun run(context: Context) {
        _status.value = Status.Checking
        val installed = installedVersionName(context)
        val release = try {
            fetchLatest()
        } catch (e: FileNotFoundException) {
            return fail(Problem.NO_RELEASE)            // 404: nothing published yet
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't reach GitHub", e)
            return fail(Problem.NETWORK)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the latest release", e)
            return fail(Problem.NO_RELEASE)
        }
        Log.i(TAG, "Latest release ${release.version}, installed $installed")
        if (!isNewer(release.version, installed)) {
            _status.value = Status.UpToDate(installed)
            return
        }
        val url = release.apkUrl ?: return fail(Problem.NO_APK)

        val apk = try {
            download(context, url, release)
        } catch (e: IOException) {
            Log.w(TAG, "Download failed", e)
            return fail(Problem.NETWORK)
        }
        if (!isUpgradeOfThisApp(context, apk)) {
            apk.delete()
            return fail(Problem.BAD_FILE)
        }

        _status.value = Status.Installing(release.version)
        try {
            install(context, apk)
        } catch (e: Exception) {
            Log.w(TAG, "Install failed", e)
            clearAfterUpdate(context)
            fail(Problem.INSTALL, e.message)
        }
    }

    private fun fail(problem: Problem, detail: String? = null) {
        _status.value = Status.Failed(problem, detail)
    }

    // ── GitHub ─────────────────────────────────────────────────────────────

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout    = 30_000
            instanceFollowRedirects = true   // release files redirect to GitHub's file host
            setRequestProperty("User-Agent", "CarMediaPlayer-updater")
        }

    private fun fetchLatest(): Release {
        val conn = open(LATEST_URL)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) throw FileNotFoundException(LATEST_URL)
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return parseRelease(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** The latest release from GitHub's JSON: its version (tag without the "v") and the APK attached to it. */
    @VisibleForTesting
    internal fun parseRelease(json: String): Release {
        val o = JSONObject(json)
        val version = o.getString("tag_name").trim().removePrefix("v").removePrefix("V")
        val assets = o.optJSONArray("assets")
        var url: String? = null
        var size = -1L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url  = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                    size = a.optLong("size", -1L)
                    break
                }
            }
        }
        return Release(version, url, size)
    }

    /** "2.0.10" is newer than "2.0.9" (compared number by number). */
    @VisibleForTesting
    internal fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.split('.', '-', ' ').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun download(context: Context, url: String, release: Release): File {
        val dir = File(context.cacheDir, "update")
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "CarMediaPlayer-${release.version}.apk")
        _status.value = Status.Downloading(release.version, 0)
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var shown = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != shown) {
                            shown = pct
                            _status.value = Status.Downloading(release.version, pct)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Download cut short ($done of $total bytes)")
        } catch (e: IOException) {
            file.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** The download is this app, and a later version than the one installed. */
    private fun isUpgradeOfThisApp(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, 0) ?: return false
        val installed = pm.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        @Suppress("DEPRECATION")
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        return info.packageName == context.packageName && newCode > oldCode
    }

    private fun installedVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    // ── Installing ─────────────────────────────────────────────────────────

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The system stops this app to replace it; remember to come back.
            rememberForAfterUpdate(context)
            val intent = Intent(context, UpdateStatusReceiver::class.java)
                .setAction(UpdateStatusReceiver.ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, intent, flags)
            Log.i(TAG, "Installing ${apk.name}")
            session.commit(pending.intentSender)
        }
    }

    /** From [UpdateStatusReceiver]: what the installer said. */
    internal fun onInstallStatus(context: Context, status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                _status.value = Status.Confirming
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Installed")
            else -> {
                Log.w(TAG, "Install failed: $status $message")
                clearAfterUpdate(context)
                fail(Problem.INSTALL, message)
            }
        }
    }

    @VisibleForTesting
    internal fun post(status: Status) { _status.value = status }

    // ── After the update ───────────────────────────────────────────────────

    private const val PREFS          = "update"
    private const val K_AT           = "installing_at"
    private const val K_WAS_PLAYING  = "was_playing"
    private const val AFTER_UPDATE_WINDOW_MS = 15 * 60_000L

    private fun rememberForAfterUpdate(context: Context) {
        val playing = PlayerState.transport.value.state == PlaybackStateCompat.STATE_PLAYING
        Storage.prefs(context, PREFS).edit()
            .putLong(K_AT, System.currentTimeMillis())
            .putBoolean(K_WAS_PLAYING, playing)
            .commit()   // the process is about to be stopped
    }

    private fun clearAfterUpdate(context: Context) {
        Storage.prefs(context, PREFS).edit().clear().apply()
    }

    class AfterUpdate(val wasPlaying: Boolean)

    /**
     * The app has just been replaced.  Non-null if it was this updater that
     * did it (a few minutes ago at most); clears the record either way.
     */
    fun afterUpdate(context: Context): AfterUpdate? {
        val prefs = Storage.prefs(context, PREFS)
        val at = prefs.getLong(K_AT, 0L)
        val wasPlaying = prefs.getBoolean(K_WAS_PLAYING, false)
        prefs.edit().clear().apply()
        File(context.cacheDir, "update").deleteRecursively()
        val age = System.currentTimeMillis() - at
        return if (at > 0 && age in 0..AFTER_UPDATE_WINDOW_MS) AfterUpdate(wasPlaying) else null
    }
}
