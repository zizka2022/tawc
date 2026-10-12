package me.phie.tawc.sigsysprobe;

/** Data-only snapshot: fixed labels, signed 32-bit raw values, no identifiers. */
final class PowerSnapshot {
    static final int BATTERY_MISSING = 1, SERVICE_MISSING = 2, PERCENT = 4,
        PLUG = 8, CHARGE = 16, TEMPERATURE = 32, THERMAL = 64, READ_ERROR = 128;
    final boolean batteryPresent, servicePresent;
    final int level, scale, plugged, status, temperature, thermal, readCode, errno, percent, failed;
    PowerSnapshot(boolean batteryPresent, boolean servicePresent, int level, int scale,
        int plugged, int status, int temperature, int thermal, int readCode, int errno) {
        this.batteryPresent = batteryPresent; this.servicePresent = servicePresent;
        this.level = level; this.scale = scale; this.plugged = plugged; this.status = status;
        this.temperature = temperature; this.thermal = thermal;
        this.readCode = readCode == 0 ? 0 : readCode >= 1 && readCode <= 11 ? readCode : 10;
        this.errno = errno >= 1 && errno <= 4095 ? errno : 0;
        // Preserve percent and every other gate; accept Android FULL alongside CHARGING.
        percent = scale > 0 && level >= 0 ? level * 100 / scale : -1;
        int bits = (batteryPresent ? 0 : BATTERY_MISSING) | (servicePresent ? 0 : SERVICE_MISSING);
        if (percent < 30) bits |= PERCENT;
        if (plugged != 1 && plugged != 2) bits |= PLUG;
        if (status != 2 && status != 5 && percent != 100) bits |= CHARGE;
        if (temperature >= 385) bits |= TEMPERATURE;
        if (thermal >= 2) bits |= THERMAL;
        if (readCode != 0) bits |= READ_ERROR;
        failed = bits;
    }
    boolean permitsFixture() { return failed == 0; }
    String render() {
        return "power bits=" + failed + " avail=" + ((batteryPresent ? 1 : 0) | (servicePresent ? 2 : 0)) +
            " level=" + level + " scale=" + scale + " pct=" + percent + " plug=" + plugged +
            " status=" + status + " temp=" + temperature + " thermal=" + thermal +
            " read_code=" + readCode + " errno=" + errno;
    }
}
