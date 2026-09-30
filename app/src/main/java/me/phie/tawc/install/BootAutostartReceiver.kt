package me.phie.tawc.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.phie.tawc.session.Reason
import me.phie.tawc.session.SessionHolds

/**
 * Runs [RootfsAutostart] at boot for installs that opted in with
 * [RootfsAutostart.AT_BOOT]. BOOT_COMPLETED is one of the moments Android
 * lets a background app start a foreground service, so the hold is taken
 * right here; the entries then take their own.
 */
class BootAutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!RootfsAutostart.anyAtBoot(context)) return
        val hold = SessionHolds.acquire(Reason.Command("autostart"))
        RootfsAutostart.onBoot(context, hold)
    }
}
