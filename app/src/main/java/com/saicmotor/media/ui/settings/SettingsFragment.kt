package com.saicmotor.media.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.appcompat.app.AppCompatDelegate
import com.saicmotor.media.R
import com.saicmotor.media.data.Library
import com.saicmotor.media.data.PlaybackSettings
import com.saicmotor.media.data.PlaybackSettings.StartupMode
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.databinding.FragmentSettingsBinding
import com.saicmotor.media.subsonic.SubsonicClient
import com.saicmotor.media.subsonic.SubsonicException
import com.saicmotor.media.ui.common.Navigator
import com.saicmotor.media.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** What to do when the car starts, the server login (set once, so it's near the end), and updates. */
class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val languageRows = mapOf(
            "en" to b.languageEnglish,
            "fr" to b.languageFrench
        )
        fun showLanguage(language: String) = languageRows.forEach { (tag, row) -> row.isSelected = tag == language }
        val appLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.language
            ?: Locale.getDefault().language
        showLanguage(if (appLanguage == "fr") "fr" else "en")
        languageRows.forEach { (language, row) ->
            row.setOnClickListener {
                if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != language) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
                }
            }
        }

        if (savedInstanceState == null) {
            val cfg = SubsonicSettings.load(ctx)
            b.url.setText(cfg.url)
            b.username.setText(cfg.username)
            b.password.setText(cfg.password)
        }
        b.save.setOnClickListener { saveAndConnect() }
        b.password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { saveAndConnect(); true } else false
        }

        val options = mapOf(
            StartupMode.RESUME          to b.startupResume,
            StartupMode.SHUFFLE_LIBRARY to b.startupShuffle,
            StartupMode.NOTHING         to b.startupNothing
        )
        fun showStartup(mode: StartupMode) = options.forEach { (m, row) -> row.isSelected = m == mode }
        showStartup(PlaybackSettings.startupMode(ctx))
        options.forEach { (mode, row) ->
            row.setOnClickListener {
                PlaybackSettings.setStartupMode(ctx, mode)
                showStartup(mode)
            }
        }

        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) { null }
        b.version.text = getString(R.string.settings_version, version ?: "")

        // Updates: only when asked.
        b.checkUpdates.setOnClickListener { Updater.checkAndInstall(ctx) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Updater.status.collect { showUpdate(it) }
            }
        }

        // Show whether the saved server works.  Not set up yet: the server
        // is what's needed, so go straight to it (it's at the bottom).
        if (SubsonicSettings.load(ctx).isValid) {
            testConnection()
        } else if (savedInstanceState == null) {
            b.root.post { _b?.let { it.root.scrollTo(0, it.serverHeading.top) } }
        }
    }

    private fun showUpdate(status: Updater.Status) {
        val ctx = context ?: return
        val busy = status is Updater.Status.Checking || status is Updater.Status.Downloading ||
                   status is Updater.Status.Installing || status is Updater.Status.Confirming
        b.checkUpdates.isEnabled = !busy
        b.updateBusy.visibility = if (busy) View.VISIBLE else View.GONE
        b.updateIcon.visibility = View.GONE
        b.updateStatus.setTextColor(ContextCompat.getColor(ctx, if (busy) R.color.ink_2 else R.color.ink))
        fun icon(res: Int, tint: Int) {
            b.updateIcon.setImageResource(res)
            b.updateIcon.imageTintList = ContextCompat.getColorStateList(ctx, tint)
            b.updateIcon.visibility = View.VISIBLE
        }
        b.updateStatus.text = when (status) {
            Updater.Status.Idle          -> ""
            Updater.Status.Checking      -> getString(R.string.update_checking)
            is Updater.Status.UpToDate   -> { icon(R.drawable.ic_check, R.color.accent_ink)
                                              getString(R.string.update_up_to_date, status.version) }
            is Updater.Status.Downloading -> getString(R.string.update_downloading, status.version, status.percent)
            is Updater.Status.Installing -> getString(R.string.update_installing, status.version)
            Updater.Status.Confirming    -> getString(R.string.update_confirming)
            is Updater.Status.Failed     -> { icon(R.drawable.ic_alert, R.color.ink_2)
                when (status.problem) {
                    Updater.Problem.NETWORK    -> getString(R.string.update_failed_network)
                    Updater.Problem.NO_RELEASE -> getString(R.string.update_failed_no_release)
                    Updater.Problem.NO_APK     -> getString(R.string.update_failed_no_apk)
                    Updater.Problem.BAD_FILE   -> getString(R.string.update_failed_bad_file)
                    Updater.Problem.INSTALL    -> status.detail?.takeIf { it.isNotBlank() }
                        ?.let { getString(R.string.update_failed_install_detail, it) }
                        ?: getString(R.string.update_failed_install)
                }
            }
        }
    }

    private fun saveAndConnect() {
        val ctx = requireContext()
        val firstSetup = !SubsonicSettings.load(ctx).isValid
        hideKeyboard()
        val cfg = SubsonicSettings.Config(
            url      = normaliseUrl(b.url.text.toString()),
            username = b.username.text.toString().trim(),
            password = b.password.text.toString()
        )
        b.url.setText(cfg.url)
        SubsonicSettings.save(ctx, cfg)
        SubsonicClient.invalidateCache()
        Library.clearCache()
        testConnection(goHomeIfConnected = firstSetup)
    }

    /** "music.example.com" → "https://music.example.com"; drops a trailing slash. */
    private fun normaliseUrl(raw: String): String {
        val url = raw.trim().trimEnd('/')
        if (url.isEmpty() || url.contains("://")) return url
        return "https://$url"
    }

    private fun testConnection(goHomeIfConnected: Boolean = false) {
        b.testing.visibility = View.VISIBLE
        b.statusIcon.visibility = View.GONE
        b.status.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_2))
        b.status.setText(R.string.settings_testing)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                val info = Library.load { ping() }
                val name = listOfNotNull(
                    info.type?.replaceFirstChar { it.uppercase() },
                    info.version
                ).joinToString(" ")
                Result.success(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            val ctx = context ?: return@launch
            b.testing.visibility = View.GONE
            b.statusIcon.visibility = View.VISIBLE
            result.onSuccess { name ->
                b.statusIcon.setImageResource(R.drawable.ic_check)
                b.statusIcon.imageTintList = ContextCompat.getColorStateList(ctx, R.color.accent_ink)
                b.status.setTextColor(ContextCompat.getColor(ctx, R.color.ink))
                b.status.text = if (name.isBlank()) getString(R.string.settings_connected_plain)
                                else getString(R.string.settings_connected, name)
                if (goHomeIfConnected) b.root.postDelayed({ (activity as? Navigator)?.openHome() }, 900)
            }.onFailure { e ->
                b.statusIcon.setImageResource(R.drawable.ic_alert)
                b.statusIcon.imageTintList = ContextCompat.getColorStateList(ctx, R.color.ink_2)
                b.status.setTextColor(ContextCompat.getColor(ctx, R.color.ink))
                b.status.text = when ((e as? SubsonicException)?.kind) {
                    SubsonicException.Kind.NOT_CONFIGURED -> getString(R.string.error_not_configured_body)
                    SubsonicException.Kind.AUTH           -> getString(R.string.error_auth_title)
                    SubsonicException.Kind.SERVER         -> e.message
                    else                                  -> getString(R.string.error_network_title)
                }
            }
        }
    }

    private fun hideKeyboard() {
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
