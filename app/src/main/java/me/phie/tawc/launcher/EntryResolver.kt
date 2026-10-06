package me.phie.tawc.launcher

import android.content.Context
import me.phie.tawc.R
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationMethod
import me.phie.tawc.install.InstallationStore

/**
 * Resolves `(installId, desktopId)` to a launchable entry with a fresh
 * rootfs scan. Shared by every launch request that comes from outside
 * the in-app launcher — pinned shortcuts ([ShortcutLaunchActivity]) and
 * other apps ([AppsProvider], the `LaunchApp` alias) — so they all get
 * the same checks and never carry a command.
 */
object EntryResolver {

    enum class Status(val key: String) {
        OK("ok"),
        NO_INSTALL("no_install"),
        NOT_READY("not_ready"),
        METHOD_UNAVAILABLE("method_unavailable"),
        GONE("gone"),
    }

    class Result(
        val status: Status,
        val inst: Installation? = null,
        val entry: LauncherEntry? = null,
    )

    /** Blocking (rootfs scan); call off the main thread. */
    fun resolve(context: Context, installId: String, desktopId: String): Result {
        val store = InstallationStore(context)
        // Caller-supplied ids are the least-trusted input; reject a
        // malformed one before it reaches File(baseDir, id).
        val inst = (if (Installation.isValidId(installId)) store.load(installId) else null)
            ?: return Result(Status.NO_INSTALL)
        val pre = precheck(inst, InstallationMethod.forKey(context, inst.method) != null)
        if (pre != Status.OK) return Result(pre, inst)
        val entry = LauncherEntry.scan(store.rootfsDir(inst.id).absolutePath)
            .firstOrNull { it.id == desktopId }
            ?: return Result(Status.GONE, inst)
        return Result(Status.OK, inst, entry)
    }

    /** The install-level checks, pure for tests. */
    internal fun precheck(inst: Installation?, methodAvailable: Boolean): Status = when {
        inst == null -> Status.NO_INSTALL
        inst.state != Installation.State.READY -> Status.NOT_READY
        !methodAvailable -> Status.METHOD_UNAVAILABLE
        else -> Status.OK
    }

    fun message(context: Context, r: Result, installId: String): String = when (r.status) {
        Status.OK -> ""
        Status.NO_INSTALL -> context.getString(R.string.launcher_installation_not_found, installId)
        Status.NOT_READY -> context.getString(
            R.string.shortcut_install_not_ready, r.inst?.state?.name?.lowercase() ?: "?",
        )
        Status.METHOD_UNAVAILABLE -> context.getString(R.string.launcher_method_unavailable, r.inst?.method ?: "?")
        Status.GONE -> context.getString(R.string.shortcut_entry_gone)
    }
}
