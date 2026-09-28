package me.phie.tawc.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's "Keep awake" toggle. [SessionService] holds a partial
 * wakelock while this is on and it is running; see plans/wakelock.md.
 * Not persisted: the service clears it when it stops.
 */
object SessionAwake {
    private val state = MutableStateFlow(false)

    val awake: StateFlow<Boolean> = state.asStateFlow()

    fun set(on: Boolean) {
        state.value = on
    }

    fun toggle() {
        state.value = !state.value
    }
}
