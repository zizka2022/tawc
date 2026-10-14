package me.phie.tawc.session

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Kills guests left behind by an earlier app process, once per app start.
 *
 * Guests live in the cgroup of the app process that spawned them
 * (`/apps/uid_<uid>/pid_<app pid>`), and Android tears an app down by
 * killing the cgroups of the processes it still tracks. If an app process
 * dies without its group being killed, its guests keep running in a group
 * nothing tracks any more: force-stop, APK updates and later app restarts
 * all miss them. They keep the old APK's tawcroot and hold singletons
 * (PipeWire, autostart supervisors) that make the new process's copies
 * skip their start.
 *
 * So: any same-uid process whose group leader is not a live app process
 * of ours is an orphan. TERM, then KILL, re-scanning for forks.
 * Kernels without per-process app cgroups give no leader; nothing is
 * killed there. Chroot guests run as root and are not seen (debug-only).
 */
internal object OrphanGuestReaper {
    private const val TAG = "tawc"
    private const val TERM_GRACE_MS = 2000L
    private const val MAX_SWEEPS = 5
    private const val SWEEP_DELAY_MS = 250L

    private val LEADER = Regex("""/uid_\d+/pid_(\d+)$""")

    private val done = CountDownLatch(1)

    /** Startup thread, before anything is spawned into a rootfs. */
    fun reap(context: Context) {
        try {
            val keep = liveAppPids(context)
            val killed = killOrphans(keep)
            if (killed > 0) Log.i(TAG, "Killed $killed guest processes left by an earlier app process")
        } catch (t: Throwable) {
            Log.w(TAG, "orphan guest sweep failed", t)
        } finally {
            done.countDown()
        }
    }

    /** For spawns that must not race the sweep (single-instance daemons). */
    fun awaitDone(timeoutMs: Long) {
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** The pid in the deepest `pid_<n>` cgroup path component, if any. */
    internal fun groupLeader(cgroup: String): Int? {
        for (line in cgroup.lineSequence()) {
            val path = line.split(':', limit = 3).getOrNull(2) ?: continue
            LEADER.find(path.trim())?.let { return it.groupValues[1].toIntOrNull() }
        }
        return null
    }

    private fun liveAppPids(context: Context): Set<Int> {
        val me = Process.myPid()
        val am = context.getSystemService(ActivityManager::class.java)
        val uid = Process.myUid()
        val pids = am?.runningAppProcesses.orEmpty().filter { it.uid == uid }.map { it.pid }
        return pids.toSet() + me
    }

    private fun scan(keep: Set<Int>): List<Int> {
        val me = Process.myPid()
        val uid = Process.myUid()
        val out = mutableListOf<Int>()
        for (name in File("/proc").list() ?: return out) {
            val pid = name.toIntOrNull() ?: continue
            if (pid == me) continue
            try {
                if (Os.stat("/proc/$pid").st_uid != uid) continue
                val leader = groupLeader(File("/proc/$pid/cgroup").readText()) ?: continue
                if (leader !in keep) out += pid
            } catch (_: Throwable) {
                // Gone mid-scan, or not ours to read.
            }
        }
        return out
    }

    private fun killOrphans(keep: Set<Int>): Int {
        val killed = HashSet<Int>()
        for (sweep in 1..MAX_SWEEPS) {
            val orphans = scan(keep)
            if (orphans.isEmpty()) break
            killed += orphans
            if (sweep == 1) {
                orphans.forEach { signal(it, OsConstants.SIGTERM) }
                waitGone(orphans, TERM_GRACE_MS)
                orphans.forEach { signal(it, OsConstants.SIGKILL) }
            } else {
                orphans.forEach { signal(it, OsConstants.SIGKILL) }
            }
            Thread.sleep(SWEEP_DELAY_MS)
        }
        return killed.size
    }

    private fun signal(pid: Int, sig: Int) {
        try { Os.kill(pid, sig) } catch (_: Throwable) {}
    }

    private fun waitGone(pids: List<Int>, budgetMs: Long) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (pids.any { File("/proc/$it").exists() } && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }
}
