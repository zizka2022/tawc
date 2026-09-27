package me.phie.tawc.compositor

import android.app.Activity
import android.util.Log
import java.lang.reflect.Method

/**
 * Samsung DeX keeps the Meta key for its own shortcuts unless the app asks
 * for it through the hidden `SemWindowManager.requestMetaKeyEvent` (the
 * call Termux:X11's `dexMetaKeyCapture` makes). No-op elsewhere.
 */
internal object DexMetaKey {
    private const val TAG = "tawc"

    private val api: Pair<Any, Method>? = try {
        val cls = Class.forName("com.samsung.android.view.SemWindowManager")
        val manager = cls.getMethod("getInstance").invoke(null)
        val request = cls.getDeclaredMethod(
            "requestMetaKeyEvent",
            android.content.ComponentName::class.java,
            Boolean::class.javaPrimitiveType,
        )
        if (manager != null) manager to request else null
    } catch (_: Throwable) {
        null
    }

    fun capture(activity: Activity) {
        val (manager, request) = api ?: return
        try {
            request.invoke(manager, activity.componentName, true)
        } catch (t: Throwable) {
            Log.w(TAG, "requestMetaKeyEvent failed: $t")
        }
    }
}
