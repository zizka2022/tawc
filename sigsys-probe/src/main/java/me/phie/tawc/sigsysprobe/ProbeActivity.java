package me.phie.tawc.sigsysprobe;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.Os;
import android.system.ErrnoException;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public final class ProbeActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicReference<Process> child = new AtomicReference<>();
    private volatile boolean cancelled;
    private boolean busy;
    private TextView result;
    private Button metadata, run;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Ignore all intent extras/data; only an explicit local button starts work.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding * 2, padding, padding);
        TextView explanation = new TextView(this);
        explanation.setText("Pocket SIGSYS Probe\n\nSeparate test app. It preserves TAWC/Ubuntu/Android.\n" +
            "No browser, network, existing profiles or command input.\n" +
            "The fixed test needs charging and safe temperature. Leaving this app stops it.\n" +
            "Its child uses this app's UID and Android launch environment, not TAWC's UID.\n" +
            "A passing marker test proves tracing for this fixture, not Firefox or Omarchy compatibility.");
        content.addView(explanation);
        metadata = new Button(this); metadata.setText("Read installed TAWC metadata");
        metadata.setOnClickListener(view -> task(false)); content.addView(metadata);
        run = new Button(this); run.setText("Run private syscall fixture");
        run.setOnClickListener(view -> task(true)); content.addView(run);
        Button cancel = new Button(this); cancel.setText("Stop this test");
        cancel.setOnClickListener(view -> stop()); content.addView(cancel);
        result = new TextView(this);
        int pending = PrivateFiles.pendingRuns(new File(getFilesDir(), "probe-runs"));
        long installedVersion = -1;
        try { installedVersion = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (Exception ignored) { }
        result.setText("probe_version=" + installedVersion + " pending_runs=" + pending +
            " previous_cleanup=unverified\nNo runtime activated. Installation/launch needs separate approval.");
        result.setTextIsSelectable(true); content.addView(result);
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll);
    }
    private void task(boolean fixture) {
        if (busy) return;
        busy = true; cancelled = false; metadata.setEnabled(false); run.setEnabled(false);
        result.setText(fixture ? "Checking provenance and power…" : "Reading public package metadata…");
        worker.execute(() -> {
            String summary;
            try { summary = fixture ? runFixture() : PackageMetadata.read(this, () -> cancelled); }
            catch (Exception error) {
                ProbeReport report = new ProbeReport(); report.stage = ProbeReport.Stage.METADATA;
                report.fail(error, numericErrno(error)); summary = report.render();
            }
            String displayed = summary;
            runOnUiThread(() -> {
                busy = false;
                if (!isDestroyed()) { result.setText(displayed); metadata.setEnabled(true); run.setEnabled(true); }
            });
        });
    }
    private void stop() {
        cancelled = true;
        Process process = child.get();
        if (process != null) process.destroy(); // Supervisor handles SIGTERM and kills its owned group.
    }
    @Override protected void onStop() { stop(); super.onStop(); }
    @Override protected void onDestroy() { stop(); worker.shutdownNow(); super.onDestroy(); }

    private void powerCheck() throws IOException {
        if (cancelled) throw new ProbeReport.Failure(ProbeReport.Category.CANCELLED);
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
        if (battery == null || power == null) throw new ProbeReport.Failure(ProbeReport.Category.POWER);
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percent = scale > 0 && level >= 0 ? level * 100 / scale : -1;
        if (!ProbeProtocol.powerSafe(percent, battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1), battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 1000),
            power.getCurrentThermalStatus())) throw new ProbeReport.Failure(ProbeReport.Category.POWER);
    }
    private JSONObject provenance() throws Exception {
        try (InputStream input = getAssets().open("provenance.json")) {
            JSONObject data = new JSONObject(new String(PrivateFiles.bytes(input, 16384), StandardCharsets.UTF_8));
            if (!data.getString("runtime_source_commit").equals(ProbeProtocol.RUNTIME_SOURCE) ||
                !data.getString("baseline_sha256").equals(ProbeProtocol.BASELINE) ||
                !data.getString("trace_sha256").equals(ProbeProtocol.TRACE)) throw new ProbeReport.Failure(ProbeReport.Category.PROVENANCE);
            for (String name : new String[]{"libpocket_baseline.so", "libpocket_trace.so", "libpocket_runner.so"}) {
                String field = name.equals("libpocket_baseline.so") ? "baseline_sha256" :
                    name.equals("libpocket_trace.so") ? "trace_sha256" : "runner_sha256";
                if (!PrivateFiles.hash(new File(getApplicationInfo().nativeLibraryDir, name), 2*1024*1024)
                    .equals(data.getString(field))) throw new ProbeReport.Failure(ProbeReport.Category.PROVENANCE);
            }
            return data;
        }
    }
    private static int numericErrno(Exception error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
            if (cause instanceof ErrnoException) {
                int value = ((ErrnoException)cause).errno;
                return value >= 1 && value <= 4095 ? value : 0;
            }
        }
        return 0; // No parsing of exception strings, paths or command lines.
    }
    private String runFixture() {
        ProbeReport report = new ProbeReport();
        File parent = new File(getFilesDir(), "probe-runs"), directory = null;
        StringBuilder summary = new StringBuilder();
        try {
            report.stage = ProbeReport.Stage.POWER; powerCheck();
            report.stage = ProbeReport.Stage.PROVENANCE; JSONObject data = provenance();
            report.stage = ProbeReport.Stage.RUN_DIRECTORY;
            if (!parent.exists() && !parent.mkdir()) throw new ProbeReport.Failure(ProbeReport.Category.FILES);
            Os.chmod(parent.getPath(), 0700);
            int previous = PrivateFiles.pendingRuns(parent);
            if (previous != 0) {
                report.stage = ProbeReport.Stage.PRIOR_RUNS;
                report.files = previous < 0 ? ProbeReport.FilesState.UNKNOWN : ProbeReport.FilesState.RETAINED;
                report.child = ProbeReport.Child.UNKNOWN; report.group = ProbeReport.Group.UNVERIFIED;
                throw new ProbeReport.Failure(ProbeReport.Category.FILES);
            }
            directory = Files.createTempDirectory(parent.toPath(), "run-").toFile();
            report.files = ProbeReport.FilesState.RETAINED;
            Os.chmod(directory.getPath(), 0700); // Inside the protected cleanup scope.
            File root = new File(directory, "rootfs");
            if (!root.mkdir()) throw new ProbeReport.Failure(ProbeReport.Category.FILES);
            Os.chmod(root.getPath(), 0700);
            report.stage = ProbeReport.Stage.FIXTURE;
            File program = new File(root, "probe");
            try (InputStream input = getAssets().open("probe-fixture"); FileOutputStream output = new FileOutputStream(program)) {
                output.write(PrivateFiles.bytes(input, 65536));
            }
            Os.chmod(program.getPath(), 0700);
            if (!PrivateFiles.hash(program, 65536).equals(data.getString("fixture_sha256")))
                throw new ProbeReport.Failure(ProbeReport.Category.PROVENANCE);
            report.stage = ProbeReport.Stage.MARKER;
            File marker = new File(root, "marker");
            try (FileOutputStream output = new FileOutputStream(marker)) { output.write("probe marker\n".getBytes(StandardCharsets.UTF_8)); }
            Os.chmod(marker.getPath(), 0600);
            for (boolean trace : new boolean[]{false, true}) {
                report.beginPhase(trace); report.stage = ProbeReport.Stage.POWER; powerCheck();
                if (!runMode(report, directory, root, trace, summary)) break;
            }
        } catch (Exception error) { report.fail(error, numericErrno(error)); }
        finally {
            if (directory != null && report.canRemoveFiles()) {
                try {
                    if (!report.failed()) report.stage = ProbeReport.Stage.FILES_CLEANUP;
                    PrivateFiles.removeRun(parent, directory);
                    report.files = ProbeReport.FilesState.REMOVED;
                } catch (Exception cleanupError) { report.cleanupFailed(cleanupError); }
            }
        }
        return report.render() + "\n" + summary +
            "Fixed signed marker only. Android/TAWC process equivalence, Firefox and Omarchy remain untested.";
    }
    private boolean runMode(ProbeReport report, File directory, File root, boolean trace, StringBuilder summary) throws Exception {
        report.stage = ProbeReport.Stage.OUTPUT;
        File stdout = new File(directory, trace ? "trace.stdout" : "baseline.stdout");
        File stderr = new File(directory, trace ? "trace.stderr" : "baseline.stderr");
        for (File file : new File[]{stdout, stderr}) {
            if (!file.createNewFile()) throw new ProbeReport.Failure(ProbeReport.Category.FILES);
            Os.chmod(file.getPath(), 0600);
        }
        report.stage = ProbeReport.Stage.START;
        ProcessBuilder builder = new ProcessBuilder(
            new File(getApplicationInfo().nativeLibraryDir, "libpocket_runner.so").getPath(),
            new File(getApplicationInfo().nativeLibraryDir, trace ? "libpocket_trace.so" : "libpocket_baseline.so").getPath(), root.getPath());
        builder.environment().clear(); builder.directory(root);
        builder.redirectInput(new File("/dev/null")).redirectOutput(stdout).redirectError(stderr);
        if (cancelled) throw new ProbeReport.Failure(ProbeReport.Category.CANCELLED);
        report.beforeStart();
        Process process = builder.start(); child.set(process); report.started();
        boolean ended = false;
        try {
            report.stage = ProbeReport.Stage.WAIT;
            long deadline = SystemClock.elapsedRealtime() + 13000, lastPower = 0;
            while (!(ended = process.waitFor(100, TimeUnit.MILLISECONDS))) {
                if (SystemClock.elapsedRealtime() > deadline) throw new ProbeReport.Failure(ProbeReport.Category.DEADLINE);
                if (SystemClock.elapsedRealtime() - lastPower >= 1000) { powerCheck(); lastPower = SystemClock.elapsedRealtime(); }
            }
            if (cancelled) throw new ProbeReport.Failure(ProbeReport.Category.CANCELLED);
        } catch (Exception error) { report.fail(error, numericErrno(error)); }
        finally {
            if (!ended) ended = terminate(process);
            if (ended) { report.ended(process.exitValue()); child.compareAndSet(process, null); }
            if (!report.failed()) report.stage = ProbeReport.Stage.STREAMS;
            for (java.io.Closeable stream : new java.io.Closeable[]{process.getInputStream(), process.getErrorStream(), process.getOutputStream()}) {
                try { stream.close(); } catch (Exception closeError) { report.cleanupFailed(closeError); }
            }
            if (ended) {
                if (!report.failed()) report.stage = ProbeReport.Stage.GROUP_CHECK;
                try {
                    if (PrivateFiles.read(stdout, ProbeProtocol.LOG_CAP).endsWith("cleanup=ok\n")) report.verifiedGroup();
                } catch (Exception readError) { report.fail(readError, numericErrno(readError)); }
            }
        }
        if (report.failed()) return false;
        if (!ended) throw new ProbeReport.Failure(ProbeReport.Category.DEADLINE);
        report.stage = ProbeReport.Stage.TRACE_PARSE;
        String output = PrivateFiles.read(stdout, ProbeProtocol.LOG_CAP);
        ProbeProtocol.Counts counts;
        String stderrText = PrivateFiles.read(stderr, ProbeProtocol.LOG_CAP);
        try { counts = ProbeProtocol.records(stderrText); }
        catch (IOException formatError) { throw new ProbeReport.Failure(ProbeReport.Category.FORMAT); }
        report.stage = ProbeReport.Stage.VERIFY;
        boolean passed = ProbeProtocol.fixturePassed(report.exit, output, counts, trace);
        summary.append(trace ? "trace" : "baseline").append(": ").append(passed ? "pass" : "fail")
            .append("; exit=").append(report.exit).append("; enter=").append(counts.enters)
            .append("; return=").append(counts.returns).append("; cleanup=").append(report.group == ProbeReport.Group.VERIFIED).append('\n');
        if (!passed || report.group != ProbeReport.Group.VERIFIED) throw new ProbeReport.Failure(ProbeReport.Category.RESULT);
        return true;
    }
    private boolean terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            process.destroy();
            if (process.waitFor(3, TimeUnit.SECONDS)) return true;
            process.destroyForcibly();
            return process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) { process.destroy(); return false; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
