package me.phie.tawc.sigsysprobe;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProbeProtocol {
    static final String BASELINE = "343db96e9ac6eab32224a58e440bfea92524d6dac7c3c201897d70b982b0d112";
    static final String TRACE = "5a65fb93d44677310c48f3130a1c3fb002e0afa0b7a5863435e473c63f08012f";
    static final String CERTIFICATE = "1d023213c6fa11f51455508f595ddbd80a906d761e85dc9f21738c017d911afd";
    static final String RUNTIME_SOURCE = "5bbafd8490d4687d5f845bdab4212c99a5150cb4";
    static final long LOG_CAP = 1048576;
    private static final Pattern RECORD = Pattern.compile(
        "\\[te] (enter|return) pid=([0-9]+) tid=([0-9]+) nr=([0-9]+) code=1 arch=3221225655(.*)");
    private static final Pattern ERROR = Pattern.compile(" errno=([0-9]{1,4})");
    static final class Counts { int enters, returns, openEnters, openReturns; }

    static boolean powerSafe(int percent, int plugged, int status, int temperature, int thermal) {
        return percent >= 30 && (plugged == 1 || plugged == 2) &&
            (status == 2 || percent == 100) && temperature < 385 && thermal < 2;
    }
    static Counts records(String text) throws IOException {
        Counts counts = new Counts();
        for (String line : text.split("\n", -1)) {
            if (!line.startsWith("[te]")) continue;
            if (line.length() >= 192) throw new IOException("record_format");
            Matcher match = RECORD.matcher(line);
            if (!match.matches()) throw new IOException("record_format");
            try {
                if (Long.parseLong(match.group(2)) <= 0 || Long.parseLong(match.group(3)) <= 0)
                    throw new IOException("record_format");
                long nr = Long.parseLong(match.group(4));
                boolean enter = match.group(1).equals("enter");
                String suffix = match.group(5);
                Matcher error = ERROR.matcher(suffix);
                if (enter ? !suffix.isEmpty() :
                    !(suffix.equals(" result=ok") || error.matches() &&
                      Integer.parseInt(error.group(1)) >= 1 && Integer.parseInt(error.group(1)) <= 4095))
                    throw new IOException("record_format");
                if (enter) { counts.enters++; if (nr == 56) counts.openEnters++; }
                else { counts.returns++; if (nr == 56) counts.openReturns++; }
            } catch (NumberFormatException ignored) { throw new IOException("record_format"); }
        }
        return counts;
    }
    static boolean fixturePassed(int exit, String output, Counts counts, boolean trace) {
        return exit == 0 && output.equals("probe-ok\ncleanup=ok\n") &&
            (trace ? counts.openEnters > 0 && counts.openReturns > 0 :
                counts.enters == 0 && counts.returns == 0);
    }
}
