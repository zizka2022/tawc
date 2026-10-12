package me.phie.tawc.sigsysprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public class PowerSnapshotTest {
    private PowerSnapshot snapshot(int level, int scale, int plugged, int status, int temperature, int thermal) {
        return new PowerSnapshot(true, true, level, scale, plugged, status, temperature, thermal, 0, 0);
    }
    @Test public void chargingTwoBelowFullPassesUnchangedGate() {
        PowerSnapshot value = snapshot(98, 100, 1, 2, 370, 1);
        assertTrue(value.permitsFixture()); assertEquals(0, value.failed);
        assertTrue(value.render().contains("pct=98 plug=1 status=2 temp=370 thermal=1"));
    }
    @Test public void fullFiveBelowHundredIsStillRejectedOnlyByChargePredicate() {
        PowerSnapshot value = snapshot(98, 100, 1, 5, 370, 1);
        assertFalse(value.permitsFixture()); assertEquals(PowerSnapshot.CHARGE, value.failed);
        assertTrue(snapshot(100, 100, 1, 5, 370, 1).permitsFixture());
    }
    @Test public void missingScaleIsVisibleAndDoesNotGainControllerDefault() {
        PowerSnapshot value = snapshot(98, -1, 1, 2, 370, 1);
        assertEquals(-1, value.percent); assertEquals(PowerSnapshot.PERCENT, value.failed);
        assertTrue(value.render().contains("level=98 scale=-1 pct=-1"));
    }
    @Test public void missingIntentAndPowerServiceFailIndependently() {
        PowerSnapshot battery = new PowerSnapshot(false, true, 98, 100, 1, 2, 370, 1, 0, 0);
        PowerSnapshot service = new PowerSnapshot(true, false, 98, 100, 1, 2, 370, 1, 0, 0);
        assertEquals(PowerSnapshot.BATTERY_MISSING, battery.failed);
        assertEquals(PowerSnapshot.SERVICE_MISSING, service.failed);
        assertFalse(battery.permitsFixture()); assertFalse(service.permitsFixture());
    }
    @Test public void exactTemperatureThermalChargeAndPlugBoundariesStayClosed() {
        assertTrue(snapshot(30, 100, 2, 2, 384, 1).permitsFixture());
        assertEquals(PowerSnapshot.THERMAL, snapshot(98, 100, 1, 2, 370, 2).failed);
        assertEquals(PowerSnapshot.TEMPERATURE, snapshot(98, 100, 1, 2, 385, 1).failed);
        assertEquals(PowerSnapshot.PERCENT, snapshot(29, 100, 1, 2, 370, 1).failed);
        assertEquals(PowerSnapshot.PLUG, snapshot(98, 100, 4, 2, 370, 1).failed);
        assertEquals(PowerSnapshot.CHARGE, snapshot(98, 100, 1, 3, 370, 1).failed);
    }
    @Test public void failedReadCannotPermitFixtureAndReportsOnlyBoundedCodes() {
        PowerSnapshot value = new PowerSnapshot(true, true, 98, 100, 1, 2, 370, 1, 10, 13);
        assertEquals(PowerSnapshot.READ_ERROR, value.failed); assertFalse(value.permitsFixture());
        assertTrue(value.render().contains("read_code=10 errno=13"));
        PowerSnapshot invalid = new PowerSnapshot(true, true, 98, 100, 1, 2, 370, 1, 999, 99999);
        assertFalse(invalid.permitsFixture()); assertTrue(invalid.render().contains("read_code=10 errno=0"));
    }
    @Test public void numericSnapshotHasFixedSchemaAndFitsAccessibilityTextBudget() {
        for (int extreme : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            PowerSnapshot value = new PowerSnapshot(false, false, extreme, extreme, extreme, extreme, extreme, extreme, 11, 4095);
            assertTrue(value.render().length() < 200);
            assertTrue(value.render().matches("power bits=[0-9]+ avail=[0-3] level=-?[0-9]+ scale=-?[0-9]+ pct=-?[0-9]+ plug=-?[0-9]+ status=-?[0-9]+ temp=-?[0-9]+ thermal=-?[0-9]+ read_code=[0-9]+ errno=[0-9]+"));
        }
    }
    @Test public void snapshotDecisionsMatchEveryExistingPredicateAcrossBoundaryDomain() {
        for (int level : new int[]{-1,0,29,30,98,100,101,Integer.MAX_VALUE})
        for (int scale : new int[]{-1,0,100})
        for (int plugged : new int[]{0,1,2,4})
        for (int status : new int[]{-1,1,2,3,4,5})
        for (int temperature : new int[]{384,385,1000})
        for (int thermal : new int[]{-1,0,1,2}) {
            PowerSnapshot value = snapshot(level, scale, plugged, status, temperature, thermal);
            assertEquals(ProbeProtocol.powerSafe(value.percent, plugged, status, temperature, thermal), value.permitsFixture());
        }
    }
}
