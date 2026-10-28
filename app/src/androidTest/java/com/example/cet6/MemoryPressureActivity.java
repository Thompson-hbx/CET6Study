package com.example.cet6;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone test-APK component: deliberately has no target-app or Kotlin runtime dependency. */
@TargetApi(30)
public class MemoryPressureActivity extends Activity {
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String runId = getIntent().getStringExtra("run-id");
        boolean emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone");
        if (!emulator || Build.VERSION.SDK_INT < 30 || runId == null ||
                !"original-draft-lmk-check".equals(getIntent().getStringExtra("consent"))) {
            finish();
            return;
        }
        try { UUID.fromString(runId); }
        catch (IllegalArgumentException invalidId) { finish(); return; }
        TextView message = new TextView(this);
        message.setText("Emulator-only draft recovery check.\nMemory allocation deadline: 35 seconds.");
        setContentView(message);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        int maxMib = getIntent().getIntExtra("max-mib", 512);
        if (maxMib < 16 || maxMib > 512) { finish(); return; }
        // Leave room for system services; the first larger probe stalled the disposable VM.
        long budget = Math.min(maxMib * 1024L * 1024, memory.totalMem / 4);
        AtomicFile marker = new AtomicFile(new File(getFilesDir(), "low-memory-pressure-" + runId + ".json"));
        long deadline = SystemClock.elapsedRealtime() + 35_000;
        new Thread(() -> applyPressure(runId, budget, deadline, marker), "cet6-emulator-memory-probe").start();
    }

    private void applyPressure(String runId, long budget, long deadline, AtomicFile marker) {
        List<SharedMemory> regions = new ArrayList<>();
        List<ByteBuffer> mappings = new ArrayList<>();
        long allocated = 0;
        String phase = "starting";
        try {
            report(marker, runId, budget, allocated, phase);
            Random random = new Random(runId.hashCode());
            byte[] scratch = new byte[64 * 1024];
            while (running.get() && SystemClock.elapsedRealtime() < deadline) {
                if (allocated + 16 * 1024 * 1024 <= budget) {
                    SharedMemory region = SharedMemory.create("cet6-emulator-probe", 16 * 1024 * 1024);
                    ByteBuffer mapping;
                    try { mapping = region.mapReadWrite(); }
                    catch (ErrnoException failure) { region.close(); throw failure; }
                    regions.add(region);
                    mappings.add(mapping);
                    // Touch every page; zero-filled pages understate pressure because they compress well.
                    while (mapping.hasRemaining()) {
                        random.nextBytes(scratch);
                        mapping.put(scratch);
                    }
                    allocated += mapping.capacity();
                    phase = "allocating";
                    if (regions.size() % 4 == 0) report(marker, runId, budget, allocated, phase);
                } else {
                    if (!"holding".equals(phase)) {
                        phase = "holding";
                        report(marker, runId, budget, allocated, phase);
                    }
                    SystemClock.sleep(100);
                }
            }
            phase = running.get() ? "deadline" : "cancelled";
        } catch (OutOfMemoryError | ErrnoException failure) {
            phase = "allocation-failed";
            Log.e("CET6MemoryProbe", "Pressure allocation failed", failure);
        } catch (IOException | JSONException failure) {
            phase = "metadata-failed";
            Log.e("CET6MemoryProbe", "Pressure metadata failed", failure);
        } finally {
            for (ByteBuffer mapping : mappings) SharedMemory.unmap(mapping);
            for (SharedMemory region : regions) region.close();
            try { report(marker, runId, budget, allocated, phase); }
            catch (IOException | JSONException failure) { Log.e("CET6MemoryProbe", "Final metadata failed", failure); }
            runOnUiThread(this::finish);
        }
    }

    private static void report(AtomicFile marker, String runId, long budget, long allocated, String phase)
            throws IOException, JSONException {
        JSONObject data = new JSONObject().put("runId", runId).put("phase", phase)
                .put("pid", android.os.Process.myPid()).put("allocatedBytes", allocated).put("budgetBytes", budget)
                .put("elapsed", SystemClock.elapsedRealtime());
        FileOutputStream output = marker.startWrite();
        try {
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            marker.finishWrite(output);
        } catch (IOException failure) { marker.failWrite(output); throw failure; }
    }

    @Override public void onDestroy() {
        running.set(false);
        super.onDestroy();
    }
}
