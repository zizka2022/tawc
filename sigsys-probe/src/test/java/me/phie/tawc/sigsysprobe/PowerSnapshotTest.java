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
    @Test public void fullFiveBelowHundredPassesForAcAndUsb() {
        PowerSnapshot value = snapshot(98, 100, 1, 5, 370, 1);
        assertTrue(value.permitsFixture()); assertEquals(0, value.failed);
        assertTrue(snapshot(98, 100, 2, 5, 370, 1).permitsFixture());
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
    @Test public void snapshotDecisionsMatchReferenceGateAcrossBoundaryDomain() {
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
    @Test public void unpluggedWirelessDockAndCombinedFullRemainRejected() {
        for (int plugged : new int[]{0,3,4,8,-1})
        for (int level : new int[]{98,100}) {
            PowerSnapshot value = snapshot(level, 100, plugged, 5, 296, 0);
            assertEquals(PowerSnapshot.PLUG, value.failed); assertFalse(value.permitsFixture());
            assertFalse(ProbeProtocol.powerSafe(level, plugged, 5, 296, 0));
        }
    }
    @Test public void fullDoesNotBypassBatteryTemperatureThermalServicesOrReadErrors() {
        assertEquals(PowerSnapshot.PERCENT, snapshot(29, 100, 1, 5, 296, 0).failed);
        assertEquals(PowerSnapshot.PERCENT, snapshot(98, -1, 1, 5, 296, 0).failed);
        assertEquals(PowerSnapshot.TEMPERATURE, snapshot(98, 100, 1, 5, 385, 0).failed);
        assertEquals(PowerSnapshot.THERMAL, snapshot(98, 100, 1, 5, 296, 2).failed);
        PowerSnapshot absent = new PowerSnapshot(false, false, 98, 100, 1, 5, 296, 0, 0, 0);
        assertEquals(PowerSnapshot.BATTERY_MISSING | PowerSnapshot.SERVICE_MISSING, absent.failed);
        assertFalse(absent.permitsFixture());
        PowerSnapshot error = new PowerSnapshot(true, true, 98, 100, 1, 5, 296, 0, 10, 13);
        assertEquals(PowerSnapshot.READ_ERROR, error.failed); assertFalse(error.permitsFixture());
    }
    @Test public void belowHundredUnknownDischargingAndNotChargingRemainRejected() {
        for (int plugged : new int[]{1,2})
        for (int status : new int[]{-1,0,1,3,4,6,Integer.MAX_VALUE}) {
            PowerSnapshot value = snapshot(98, 100, plugged, status, 296, 0);
            assertEquals(PowerSnapshot.CHARGE, value.failed); assertFalse(value.permitsFixture());
            assertFalse(ProbeProtocol.powerSafe(98, plugged, status, 296, 0));
            // Retain the already-existing percent100 behavior; no new unknown fallback.
            assertTrue(snapshot(100, 100, plugged, status, 296, 0).permitsFixture());
        }
    }
    @Test public void fullIsTheOnlyChangedFailureBitAcrossBoundaryDomain() {
        for (int level : new int[]{-1,0,29,30,98,100,101,Integer.MAX_VALUE})
        for (int scale : new int[]{-1,0,100})
        for (int plugged : new int[]{0,1,2,3,4,8})
        for (int status : new int[]{-1,0,1,2,3,4,5,6})
        for (int temperature : new int[]{384,385,1000})
        for (int thermal : new int[]{-1,0,1,2}) {
            PowerSnapshot value = snapshot(level, scale, plugged, status, temperature, thermal);
            int original = 0;
            if (value.percent < 30) original |= PowerSnapshot.PERCENT;
            if (plugged != 1 && plugged != 2) original |= PowerSnapshot.PLUG;
            if (status != 2 && value.percent != 100) original |= PowerSnapshot.CHARGE;
            if (temperature >= 385) original |= PowerSnapshot.TEMPERATURE;
            if (thermal >= 2) original |= PowerSnapshot.THERMAL;
            int expected = status == 5 ? original & ~PowerSnapshot.CHARGE : original;
            assertEquals(expected, value.failed);
        }
    }
}
