package me.phie.tawc.sigsysprobe;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProbeReportTest {
    @Test public void neverReadsExceptionText() {
        IOException error = new IOException() {
            @Override public String getMessage() { throw new AssertionError("exception text read"); }
            @Override public String toString() { throw new AssertionError("exception rendered"); }
        };
        ProbeReport report = new ProbeReport(); report.stage = ProbeReport.Stage.PROVENANCE;
        report.fail(error, 13);
        assertTrue(report.render().contains("stage=provenance"));
        assertTrue(report.render().contains("cat=io code=1 errno=13"));
    }
    @Test public void failedStartCannotClaimNoChildOrSafeCleanup() {
        ProbeReport report = new ProbeReport(); report.files = ProbeReport.FilesState.RETAINED;
        report.stage = ProbeReport.Stage.START; report.beforeStart(); report.fail(new IOException(), 0);
        assertFalse(report.canRemoveFiles());
        assertTrue(report.render().contains("child=unknown group=unverified files=retained"));
    }
    @Test public void prelaunchFailureMayRemoveOnlyGeneratedFiles() {
        ProbeReport report = new ProbeReport(); report.stage = ProbeReport.Stage.FIXTURE;
        report.files = ProbeReport.FilesState.RETAINED; report.fail(new IOException(), 0);
        assertTrue(report.canRemoveFiles()); report.files = ProbeReport.FilesState.REMOVED;
        assertTrue(report.render().contains("child=none group=none files=removed"));
    }
    @Test public void cancelledChildNeedsExitAndOwnedGroupEvidence() {
        ProbeReport report = new ProbeReport(); report.beginPhase(false); report.beforeStart(); report.started();
        report.stage = ProbeReport.Stage.WAIT; report.fail(new InterruptedException(), 0);
        assertFalse(report.canRemoveFiles()); report.ended(90); assertFalse(report.canRemoveFiles());
        report.verifiedGroup(); assertTrue(report.canRemoveFiles()); report.files = ProbeReport.FilesState.REMOVED;
        assertTrue(report.render().contains("cat=cancelled code=3"));
        assertTrue(report.render().contains("exit=90 child=exited group=verified files=removed"));
    }
    @Test public void exitedChildWithoutGroupMarkerRetainsFilesAndBlocksNextPhase() {
        ProbeReport report = new ProbeReport(); report.beforeStart(); report.started(); report.ended(159);
        assertFalse(report.canRemoveFiles());
        try { report.beginPhase(true); fail("uncertain group permitted next phase"); }
        catch (IllegalStateException expected) { }
    }
    @Test public void cleanupFailurePreservesPrimaryFailureAndReportsRetention() {
        ProbeReport report = new ProbeReport(); report.stage = ProbeReport.Stage.START;
        report.fail(new SecurityException(), 0); report.cleanupFailed(new IOException());
        assertTrue(report.render().contains("stage=start")); assertTrue(report.render().contains("cat=security"));
        assertTrue(report.render().contains("files=retained cleanup_code=1")); assertFalse(report.canRemoveFiles());
    }
    @Test public void boundedCodesAndAllStagesRemainFullyReadable() {
        for (ProbeReport.Stage stage : ProbeReport.Stage.values()) {
            ProbeReport report = new ProbeReport(); report.stage = stage; report.beginPhase(true);
            report.fail(new Exception(), 4096); report.cleanupFailed(new IOException());
            assertTrue(report.render().length() < 200); assertTrue(report.render().contains("errno=0"));
            assertTrue(report.render().contains("cat=other code=10"));
        }
    }
}
