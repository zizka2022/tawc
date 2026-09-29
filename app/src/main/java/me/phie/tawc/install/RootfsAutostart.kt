package me.phie.tawc.install

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * Runs every READY install's [DIR] entries once per app process, when
 * the first activity has started and the startup file refresh is done
 * (notes/autostart.md). Waits for an activity because each entry takes
 * a session hold, and the foreground service can't start from the
 * background.
 */
internal object RootfsAutostart {
    private const val TAG = "tawc"

    /** Inside the rootfs. */
    const val DIR = "/root/.config/tawc/autostart"
    private const val LOG = "/tmp/tawc-autostart.log"

    private var startupDone = false
    private var activityStarted = false
    private var ran = false

    fun onStartupDone(context: Context) = maybeRun(context) { startupDone = true }

    fun onActivityStarted(context: Context) = maybeRun(context) { activityStarted = true }

    private fun maybeRun(context: Context, mark: () -> Unit) {
        synchronized(this) {
            mark()
            if (ran || !startupDone || !activityStarted) return
            ran = true
        }
        val app = context.applicationContext
        thread(name = "tawc-autostart", isDaemon = true) {
            try {
                runAll(app)
            } catch (t: Throwable) {
                Log.w(TAG, "autostart failed", t)
            }
        }
    }

    private fun runAll(context: Context) {
        val store = InstallationStore(context)
        for (inst in store.list().filter { it.state == Installation.State.READY }) {
            val rootfs = store.rootfsDir(inst.id)
            val names = entries(File(rootfs, DIR.removePrefix("/")))
            if (names.isEmpty()) continue
            val method = InstallationMethod.forKey(context, inst.method) ?: continue
            for (name in names) {
                Log.i(TAG, "autostart: $name in ${inst.id}")
                try {
                    UserRootfsSession.startInside(
                        context, method, rootfs.absolutePath,
                        "$DIR/$name </dev/null >>$LOG 2>&1",
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "autostart: $name in ${inst.id} failed: $e")
                }
            }
        }
    }

    /**
     * Entries to run, in name order: executable files, or symlinks (their
     * targets are rootfs paths, so the shell resolves them). Names must be
     * safe unquoted in the command and readable as the notification label.
     */
    internal fun entries(dir: File): List<String> =
        dir.listFiles().orEmpty()
            .filter { SAFE_NAME.matches(it.name) }
            .filter { Files.isSymbolicLink(it.toPath()) || (it.isFile && it.canExecute()) }
            .map { it.name }
            .sorted()

    private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
}
