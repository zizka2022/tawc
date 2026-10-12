package me.phie.tawc.sigsysprobe;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.Os;
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
        result = new TextView(this); result.setText("No runtime activated. Installation/launch needs separate approval.");
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
            catch (Exception ignored) { summary = "Unavailable or check failed. No fallback or permission change."; }
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
        if (cancelled) throw new IOException("cancelled");
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
        if (battery == null || power == null) throw new IOException("power_unavailable");
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percent = scale > 0 && level >= 0 ? level * 100 / scale : -1;
        if (!ProbeProtocol.powerSafe(percent, battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1), battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 1000),
            power.getCurrentThermalStatus())) throw new IOException("power_gate");
    }
    private JSONObject provenance() throws Exception {
        try (InputStream input = getAssets().open("provenance.json")) {
            JSONObject data = new JSONObject(new String(PrivateFiles.bytes(input, 16384), StandardCharsets.UTF_8));
            if (!data.getString("runtime_source_commit").equals(ProbeProtocol.RUNTIME_SOURCE) ||
                !data.getString("baseline_sha256").equals(ProbeProtocol.BASELINE) ||
                !data.getString("trace_sha256").equals(ProbeProtocol.TRACE)) throw new IOException("provenance");
            for (String name : new String[]{"libpocket_baseline.so", "libpocket_trace.so", "libpocket_runner.so"}) {
                String field = name.equals("libpocket_baseline.so") ? "baseline_sha256" :
                    name.equals("libpocket_trace.so") ? "trace_sha256" : "runner_sha256";
                if (!PrivateFiles.hash(new File(getApplicationInfo().nativeLibraryDir, name), 2*1024*1024)
                    .equals(data.getString(field))) throw new IOException("provenance");
            }
            return data;
        }
    }
    private String runFixture() throws Exception {
        powerCheck(); JSONObject data = provenance();
        File parent = new File(getFilesDir(), "probe-runs");
        if (!parent.exists() && !parent.mkdir()) throw new IOException("private_directory");
        Os.chmod(parent.getPath(), 0700);
        File[] previous = parent.listFiles();
        if (previous == null || previous.length != 0) return "Earlier cleanup is unverified; no new test started. Remove only this test app to recover.";
        File directory = Files.createTempDirectory(parent.toPath(), "run-").toFile();
        Os.chmod(directory.getPath(), 0700);
        boolean cleanup = true;
        try {
            File root = new File(directory, "rootfs");
            if (!root.mkdir()) throw new IOException("private_directory");
            Os.chmod(root.getPath(), 0700);
            File program = new File(root, "probe");
            try (InputStream input = getAssets().open("probe-fixture"); FileOutputStream output = new FileOutputStream(program)) {
                byte[] bytes = PrivateFiles.bytes(input, 65536);
                output.write(bytes);
            }
            Os.chmod(program.getPath(), 0700);
            if (!PrivateFiles.hash(program, 65536).equals(data.getString("fixture_sha256"))) throw new IOException("provenance");
            File marker = new File(root, "marker");
            try (FileOutputStream output = new FileOutputStream(marker)) { output.write("probe marker\n".getBytes(StandardCharsets.UTF_8)); }
            Os.chmod(marker.getPath(), 0600);
            StringBuilder summary = new StringBuilder("Fixed fixture; 128 MiB address space, CPU 2 s, wall 10 s, logs 1 MiB.\n");
            for (boolean trace : new boolean[]{false, true}) {
                powerCheck();
                File stdout = new File(directory, trace ? "trace.stdout" : "baseline.stdout");
                File stderr = new File(directory, trace ? "trace.stderr" : "baseline.stderr");
                for (File file : new File[]{stdout, stderr}) { if (!file.createNewFile()) throw new IOException("private_file"); Os.chmod(file.getPath(), 0600); }
                ProcessBuilder builder = new ProcessBuilder(
                    new File(getApplicationInfo().nativeLibraryDir, "libpocket_runner.so").getPath(),
                    new File(getApplicationInfo().nativeLibraryDir, trace ? "libpocket_trace.so" : "libpocket_baseline.so").getPath(), root.getPath());
                builder.environment().clear(); builder.directory(root);
                builder.redirectInput(new File("/dev/null")).redirectOutput(stdout).redirectError(stderr);
                cleanup = false;
                Process process = builder.start(); child.set(process);
                boolean ended = false;
                try {
                    long deadline = SystemClock.elapsedRealtime() + 13000;
                    long lastPower = 0;
                    while (!(ended = process.waitFor(100, TimeUnit.MILLISECONDS))) {
                        if (SystemClock.elapsedRealtime() > deadline) throw new IOException("deadline");
                        if (SystemClock.elapsedRealtime() - lastPower >= 1000) { powerCheck(); lastPower = SystemClock.elapsedRealtime(); }
                    }
                } finally {
                    if (!ended) ended = terminate(process);
                    if (ended) child.compareAndSet(process, null);
                    process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
                    if (ended) cleanup = PrivateFiles.read(stdout, ProbeProtocol.LOG_CAP).endsWith("cleanup=ok\n");
                }
                String output = PrivateFiles.read(stdout, ProbeProtocol.LOG_CAP);
                ProbeProtocol.Counts counts = ProbeProtocol.records(PrivateFiles.read(stderr, ProbeProtocol.LOG_CAP));
                boolean passed = ProbeProtocol.fixturePassed(process.exitValue(), output, counts, trace);
                summary.append(trace ? "trace" : "baseline").append(": ").append(passed ? "pass" : "fail")
                    .append("; exit=").append(process.exitValue()).append("; enter=").append(counts.enters)
                    .append("; return=").append(counts.returns).append("; cleanup=").append(cleanup).append('\n');
                if (!passed || !cleanup) break;
            }
            return summary + "This tests only this signed fixture in the companion app. Firefox and current TAWC equivalence remain untested.";
        } finally {
            if (cleanup) PrivateFiles.removeRun(parent, directory);
        }
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
