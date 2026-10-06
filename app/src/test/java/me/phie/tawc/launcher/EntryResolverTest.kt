package me.phie.tawc.launcher

import me.phie.tawc.install.Installation
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryResolverTest {

    private fun inst(state: Installation.State) = Installation(
        id = "arch", distro = "archlinuxarm", arch = "arm64-v8a", method = "tawcroot",
        installedAtMillis = 0L, sourceUrl = "", state = state,
    )

    @Test
    fun readyInstallPasses() {
        assertEquals(EntryResolver.Status.OK, EntryResolver.precheck(inst(Installation.State.READY), true))
    }

    @Test
    fun missingInstallIsNoInstall() {
        assertEquals(EntryResolver.Status.NO_INSTALL, EntryResolver.precheck(null, true))
    }

    @Test
    fun busyOrBrokenInstallIsNotReady() {
        for (s in Installation.State.entries - Installation.State.READY) {
            assertEquals(s.name, EntryResolver.Status.NOT_READY, EntryResolver.precheck(inst(s), true))
        }
    }

    @Test
    fun missingMethodIsReported() {
        assertEquals(
            EntryResolver.Status.METHOD_UNAVAILABLE,
            EntryResolver.precheck(inst(Installation.State.READY), false),
        )
    }
}
