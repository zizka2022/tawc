package me.phie.tawc.sigsysprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProbeActionsTest {
    @Test public void startupAndStatusReadCannotInvokeFixtureOrMetadataWork() {
        int[] reads = {0};
        ProbeActions actions = new ProbeActions(() -> reads[0]++,
            () -> fail("startup requested package hashing"), () -> fail("startup launched fixed fixture"));
        assertEquals(0, reads[0]); // Constructing listeners performs no work.
        actions.startup(); actions.press(ProbeActions.Action.STATUS);
        assertEquals(2, reads[0]);
    }
    @Test public void onlyExplicitFixtureActionInvokesFixtureCallbackOnce() {
        int[] calls = {0,0,0};
        ProbeActions actions = new ProbeActions(() -> calls[0]++, () -> calls[1]++, () -> calls[2]++);
        actions.startup(); actions.press(ProbeActions.Action.METADATA); actions.press(ProbeActions.Action.FIXTURE);
        assertEquals(1, calls[0]); assertEquals(1, calls[1]); assertEquals(1, calls[2]);
    }
}
