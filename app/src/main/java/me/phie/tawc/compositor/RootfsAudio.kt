package me.phie.tawc.compositor

import android.content.Context
import android.util.Log
import me.phie.tawc.install.AudioInstallProvider
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationMethod
import me.phie.tawc.install.InstallationStore
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the rootfs audio stack ([AudioInstallProvider.SESSION_PATH]) for
 * the compositor's lifetime, so [AudioBridge]'s FIFO has a writer without
 * the user starting PipeWire. One rootfs at a time — two PipeWires would
 * interleave on the one FIFO: the install last used for a command, else
 * the first ready one with PipeWire installed.
 */
internal object RootfsAudio {
    private const val TAG = "tawc"

    @Volatile private var lastRootfs: String? = null
    private var proc: Process? = null
    /** Off when [stop] beat the spawn thread. */
    private var wanted = false

    /** Called for every user command; the next compositor start follows it. */
    fun noteUsed(rootfs: String) {
        lastRootfs = rootfs
    }

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) { wanted = true }
        thread(name = "tawc-audio-session", isDaemon = true) {
            synchronized(this) {
                if (!wanted || proc?.isAlive == true) return@thread
                proc = try {
                    spawn(app)
                } catch (e: Exception) {
                    Log.w(TAG, "audio: session failed to start: $e")
                    null
                }
            }
        }
    }

    /** SIGTERM: the script stops its daemons. */
    fun stop() {
        synchronized(this) {
            wanted = false
            proc?.destroy()
            proc = null
        }
    }

    private fun spawn(context: Context): Process? {
        val store = InstallationStore(context)
        val ready = store.list().filter { it.state == Installation.State.READY }
        fun hasPipewire(i: Installation) =
            File(store.rootfsDir(i.id), "usr/bin/pipewire").exists()
        val inst = ready.firstOrNull {
            store.rootfsDir(it.id).absolutePath == lastRootfs && hasPipewire(it)
        } ?: ready.firstOrNull(::hasPipewire) ?: return null
        val method = InstallationMethod.forKey(context, inst.method) ?: return null
        val rootfs = store.rootfsDir(inst.id).absolutePath
        Log.i(TAG, "audio: starting session in ${inst.id}")
        return method.startInside(
            rootfs,
            "exec ${AudioInstallProvider.SESSION_PATH} </dev/null >/tmp/tawc-audio.log 2>&1",
            null,
        )
    }
}
