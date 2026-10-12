package me.phie.tawc.sigsysprobe;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrivateFilesTest {
    @Test public void hashStopsBeforeOpeningFileWhenCancelled() throws Exception {
        try { PrivateFiles.hash(new File("missing-fixture"), 1024, Long.MAX_VALUE, () -> true); fail("cancellation bypass"); }
        catch (IOException expected) { assertEquals(ProbeReport.Category.CANCELLED, ProbeReport.classify(expected)); }
    }
    @Test public void retainedRunCheckReadsNamesOnlyAndDoesNotDelete() throws Exception {
        File parent = Files.createTempDirectory("probe-retained-test-").toFile();
        File run = new File(parent, "run-fixture");
        try {
            assertEquals(0, PrivateFiles.pendingRuns(parent)); assertTrue(run.mkdir());
            assertEquals(1, PrivateFiles.pendingRuns(parent)); assertTrue(run.exists());
        } finally { run.delete(); parent.delete(); }
    }
    @Test public void boundedReaderRefusesOversizedBytes() throws Exception {
        assertEquals(4, PrivateFiles.bytes(new ByteArrayInputStream(new byte[4]), 4).length);
        try { PrivateFiles.bytes(new ByteArrayInputStream(new byte[5]), 4); fail("budget bypass"); }
        catch (IOException expected) { }
    }
    @Test public void cleanupDoesNotFollowLinkOutsideRun() throws Exception {
        File parent = Files.createTempDirectory("probe-test-parent-").toFile();
        File run = Files.createTempDirectory(parent.toPath(), "run-").toFile();
        File outside = Files.createTempDirectory("probe-test-outside-").toFile();
        File keep = new File(outside, "keep");
        Files.write(keep.toPath(), new byte[]{42});
        Files.createSymbolicLink(new File(run, "link").toPath(), outside.toPath());
        try {
            PrivateFiles.removeRun(parent, run);
            assertFalse(run.exists());
            assertTrue(keep.exists());
        } finally { Files.deleteIfExists(keep.toPath()); outside.delete(); parent.delete(); }
    }
    @Test public void cleanupRefusesSiblingWithSimilarPrefix() throws Exception {
        File parent = Files.createTempDirectory("probe-test-scope-").toFile();
        File outside = new File(parent.getPath() + "-outside");
        assertTrue(outside.mkdir());
        try {
            try { PrivateFiles.removeRun(parent, outside); fail("scope bypass"); }
            catch (IOException expected) { }
            assertTrue(outside.exists());
        } finally { outside.delete(); parent.delete(); }
    }
}
