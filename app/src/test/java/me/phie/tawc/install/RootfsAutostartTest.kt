package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RootfsAutostartTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun File.exe(name: String) = File(this, name).apply {
        writeText("#!/bin/sh\n")
        setExecutable(true)
    }

    @Test
    fun executablesAndSymlinksInNameOrder() {
        val dir = tmp.newFolder("autostart")
        dir.exe("20-b")
        dir.exe("10-a.sh")
        Files.createSymbolicLink(File(dir, "30-link").toPath(), File("/usr/local/bin/x").toPath())

        assertEquals(listOf("10-a.sh", "20-b", "30-link"), RootfsAutostart.entries(dir))
    }

    @Test
    fun skipsNonExecutablesDirsHiddenAndUnsafeNames() {
        val dir = tmp.newFolder("autostart")
        File(dir, "plain").writeText("x")
        File(dir, "sub").mkdir()
        dir.exe(".hidden")
        dir.exe("has space")
        dir.exe("semi;colon")
        dir.exe("ok")

        assertEquals(listOf("ok"), RootfsAutostart.entries(dir))
    }

    @Test
    fun missingDirIsEmpty() {
        assertEquals(emptyList<String>(), RootfsAutostart.entries(File(tmp.root, "nope")))
    }
}
