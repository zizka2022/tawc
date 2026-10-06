package me.phie.tawc.launcher

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.phie.tawc.R

/**
 * Invisible trampoline behind every pinned home-screen shortcut
 * ([EntryShortcuts]) and, via the exported `LaunchApp` alias, behind
 * launches from other apps that hold `LAUNCH_APPS` (notes/launcher.md
 * "Launch API"). The intent carries only (installId, desktopId, label);
 * [EntryResolver] re-resolves the entry with a fresh rootfs scan at tap
 * time — the same walk the launcher does on open — so a pin keeps
 * working across `.desktop` edits and never stores a command.
 *
 * Stale requests (distro uninstalled or mid-(un)install, entry gone)
 * turn into a [LaunchErrorActivity] dialog instead of a crash. A hidden
 * entry still launches: hiding declutters the in-app list, and an
 * existing pin is explicit user intent. Dispatch goes through
 * [EntryLauncher], so terminal entries behave exactly like an in-app
 * launch.
 */
class ShortcutLaunchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installId = intent?.getStringExtra(EXTRA_INSTALL_ID) ?: ""
        val desktopId = intent?.getStringExtra(EXTRA_DESKTOP_ID) ?: ""
        val label = intent?.getStringExtra(EXTRA_LABEL).takeUnless { it.isNullOrEmpty() } ?: desktopId
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { EntryResolver.resolve(applicationContext, installId, desktopId) }
            if (r.status == EntryResolver.Status.OK) {
                EntryLauncher.launch(applicationContext, r.inst!!, r.entry!!)
                finish()
            } else {
                fail(label, EntryResolver.message(this@ShortcutLaunchActivity, r, installId))
            }
        }
    }

    /** Show the error dialog while this (translucent) trampoline still
     *  holds the visible window, then drop it. */
    private fun fail(label: String, message: String) {
        LaunchErrorActivity.start(
            this,
            getString(R.string.launcher_launch_failed_title, label),
            message,
        )
        finish()
    }

    companion object {
        const val EXTRA_INSTALL_ID = "installId"
        const val EXTRA_DESKTOP_ID = "desktopId"
        const val EXTRA_LABEL = "label"
    }
}
