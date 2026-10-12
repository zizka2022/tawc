package me.phie.tawc.sigsysprobe;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProbeProtocolTest {
    private String record(String phase, String suffix) {
        return "[te] " + phase + " pid=42 tid=42 nr=56 code=1 arch=3221225655" + suffix + "\n";
    }
    @Test public void acceptsUsefulPairedOpenMetadata() throws Exception {
        ProbeProtocol.Counts c = ProbeProtocol.records(record("enter", "") + record("return", " result=ok"));
        assertTrue(ProbeProtocol.fixturePassed(0, "probe-ok\ncleanup=ok\n", c, true));
        assertFalse(ProbeProtocol.fixturePassed(0, "probe-ok\ncleanup=ok\n", c, false));
    }
    @Test public void baselineRequiresNoRecordsAndVerifiedCleanup() throws Exception {
        ProbeProtocol.Counts c = ProbeProtocol.records("diagnostic ignored\n");
        assertTrue(ProbeProtocol.fixturePassed(0, "probe-ok\ncleanup=ok\n", c, false));
        assertFalse(ProbeProtocol.fixturePassed(0, "probe-ok\ncleanup=failed\n", c, false));
        assertFalse(ProbeProtocol.fixturePassed(159, "probe-ok\ncleanup=ok\n", c, false));
        assertFalse(ProbeProtocol.fixturePassed(0, "probe-ok\ncleanup=ok\n", c, true));
    }
    @Test public void refusesPayloadsAndInvalidErrno() {
        for (String suffix : new String[]{" rv=1234", " a=77", " errno=0", " errno=4096", " result=ok extra=1"}) {
            try { ProbeProtocol.records(record("return", suffix)); fail("unsafe record accepted"); }
            catch (IOException expected) { }
        }
    }
    @Test public void refusesZeroOrOverflowingIds() {
        for (String pid : new String[]{"0", "99999999999999999999999999"}) {
            try { ProbeProtocol.records(record("enter", "").replace("pid=42", "pid="+pid)); fail("bad pid accepted"); }
            catch (IOException expected) { }
        }
    }
    @Test public void acceptsOnlyKernelErrnoRange() throws Exception {
        ProbeProtocol.records(record("return", " errno=1"));
        ProbeProtocol.records(record("return", " errno=4095"));
    }
    @Test public void powerAndThermalChecksFailClosed() {
        assertTrue(ProbeProtocol.powerSafe(100, 1, 5, 340, 0));
        assertTrue(ProbeProtocol.powerSafe(50, 2, 2, 380, 1));
        assertFalse(ProbeProtocol.powerSafe(50, 0, 2, 340, 0));
        assertFalse(ProbeProtocol.powerSafe(50, 4, 2, 340, 0));
        assertFalse(ProbeProtocol.powerSafe(50, 1, 3, 340, 0));
        assertFalse(ProbeProtocol.powerSafe(29, 1, 2, 340, 0));
        assertFalse(ProbeProtocol.powerSafe(100, 1, 5, 385, 0));
        assertFalse(ProbeProtocol.powerSafe(100, 1, 5, 340, 2));
    }
}
