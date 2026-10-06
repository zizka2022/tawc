package me.phie.tawc.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.distro.DistroRegistry
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * Read-only launcher inventory for other apps that hold `LAUNCH_APPS`
 * (a runtime permission the user grants per app). See notes/launcher.md
 * "Launch API".
 *
 *   installations                         id, label, state
 *   installations/<id>/apps               id, name, comment, terminal, hidden
 *   installations/<id>/apps/<desktop>/icon   PNG (openFile, "r")
 *   call("resolve", extras installId + desktopId) → status, message, name
 *
 * Launching is the exported `LaunchApp` alias of [ShortcutLaunchActivity];
 * an Activity start lets TAWC open windows from the foreground. Exec
 * lines and file paths never leave the app.
 */
class AppsProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val s = uri.pathSegments
        val store = InstallationStore(ctx)
        return when {
            s == listOf(INSTALLATIONS) -> MatrixCursor(INSTALL_COLUMNS).apply {
                for (inst in store.list()) {
                    addRow(arrayOf(inst.id, DistroRegistry.displayLabel(inst), inst.state.name.lowercase()))
                }
            }
            s.size == 3 && s[0] == INSTALLATIONS && s[2] == APPS -> MatrixCursor(APP_COLUMNS).apply {
                val inst = readyInstall(store, s[1]) ?: return@apply
                val hidden = inst.hiddenDesktopIds.toSet()
                for (e in entries(store, inst)) {
                    addRow(arrayOf(e.id, e.name, e.comment, if (e.terminal) 1 else 0, if (e.id in hidden) 1 else 0))
                }
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only")
        val s = uri.pathSegments
        if (s.size != 5 || s[0] != INSTALLATIONS || s[2] != APPS || s[4] != ICON) throw FileNotFoundException(uri.toString())
        val ctx = context ?: throw FileNotFoundException("no context")
        val store = InstallationStore(ctx)
        val inst = readyInstall(store, s[1]) ?: throw FileNotFoundException("no installation")
        val path = entries(store, inst).firstOrNull { it.id == s[3] }?.iconPath
        // Re-encode instead of handing out the file: a rootfs symlink
        // must not turn this into a read of arbitrary app-private files.
        val bmp = path?.takeIf { it.isNotEmpty() }?.let { IconLoader.decode(it, ICON_PX) }
            ?: throw FileNotFoundException("no icon")
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "apps-icon", isDaemon = true) {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use {
                    scaled(bmp).compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }.onFailure { Log.w(TAG, "icon: $it") }
        }
        return read
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        // call() is not covered by the provider's android:permission.
        if (ctx.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("requires $PERMISSION")
        }
        if (method != RESOLVE) return null
        val installId = extras?.getString(ShortcutLaunchActivity.EXTRA_INSTALL_ID) ?: ""
        val desktopId = extras?.getString(ShortcutLaunchActivity.EXTRA_DESKTOP_ID) ?: ""
        val r = EntryResolver.resolve(ctx, installId, desktopId)
        return Bundle().apply {
            putString("status", r.status.key)
            putString("message", EntryResolver.message(ctx, r, installId))
            r.entry?.let { putString("name", it.name) }
        }
    }

    private fun readyInstall(store: InstallationStore, id: String): Installation? =
        if (Installation.isValidId(id)) store.load(id)?.takeIf { it.state == Installation.State.READY } else null

    /** A dock of icons asks once per entry; one scan serves the burst. */
    private fun entries(store: InstallationStore, inst: Installation): List<LauncherEntry> = synchronized(scans) {
        val now = SystemClock.elapsedRealtime()
        scans[inst.id]?.takeIf { now - it.first < SCAN_TTL_MS }?.second
            ?: LauncherEntry.scan(store.rootfsDir(inst.id).absolutePath).also { scans[inst.id] = now to it }
    }

    private fun scaled(bmp: Bitmap): Bitmap {
        val longer = maxOf(bmp.width, bmp.height)
        if (longer <= ICON_PX) return bmp
        val f = ICON_PX.toFloat() / longer
        return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val TAG = "tawc-launcher"
        const val PERMISSION = "me.phie.tawc.permission.LAUNCH_APPS"
        const val INSTALLATIONS = "installations"
        const val APPS = "apps"
        const val ICON = "icon"
        const val RESOLVE = "resolve"
        private const val ICON_PX = 192
        private const val SCAN_TTL_MS = 5_000L
        private val INSTALL_COLUMNS = arrayOf("id", "label", "state")
        private val APP_COLUMNS = arrayOf("id", "name", "comment", "terminal", "hidden")
        private val scans = HashMap<String, Pair<Long, List<LauncherEntry>>>()
    }
}
