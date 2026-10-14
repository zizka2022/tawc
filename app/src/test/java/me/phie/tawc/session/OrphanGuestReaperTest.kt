package me.phie.tawc.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [OrphanGuestReaper.groupLeader] on real `/proc/<pid>/cgroup` shapes. */
class OrphanGuestReaperTest {

    @Test
    fun v2AppGroup() {
        assertEquals(8811, OrphanGuestReaper.groupLeader("0::/apps/uid_10798/pid_8811\n"))
    }

    @Test
    fun mixedV1AndV2() {
        val text = """
            5:freezer:/
            4:cpuset:/moderate
            3:cpuacct:/
            2:cpu:/background
            1:blkio:/low
            0::/apps/uid_10798/pid_14875
        """.trimIndent()
        assertEquals(14875, OrphanGuestReaper.groupLeader(text))
    }

    @Test
    fun aospUidPidLayout() {
        assertEquals(4321, OrphanGuestReaper.groupLeader("0::/uid_10123/pid_4321"))
    }

    @Test
    fun noPerProcessGroup() {
        assertNull(OrphanGuestReaper.groupLeader("0::/\n1:cpu:/background\n"))
        assertNull(OrphanGuestReaper.groupLeader("0::/apps/uid_10798\n"))
        assertNull(OrphanGuestReaper.groupLeader(""))
    }
}
