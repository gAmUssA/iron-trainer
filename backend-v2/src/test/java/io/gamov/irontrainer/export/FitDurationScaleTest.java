package io.gamov.irontrainer.export;

import com.garmin.fit.Decode;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.WorkoutStepMesg;
import com.garmin.fit.WorkoutStepMesgListener;
import io.gamov.irontrainer.plan.PlannedWorkout;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression guard for bean sqib. backend-v2's FitExport must encode step
 * durations on the FIT ms scale (seconds via the SDK's scaled setter →
 * duration_value in ms), so the official SDK and a Garmin device read them as
 * real seconds. This pins that it does NOT reproduce the Python fit-tool
 * exporter's raw-seconds bug (FitInteropTest documents that: a 600 s step
 * stored as raw 600 is read as 0.6 s). */
class FitDurationScaleTest {

    @Test
    void encodesDurationsOnTheFitMsScale() throws Exception {
        PlannedWorkout w = new PlannedWorkout();
        w.sport = "Bike";
        w.title = "Scale check";
        w.structureJson = "[{\"type\":\"steady\",\"duration_s\":600,"
                + "\"target\":{\"type\":\"power\",\"unit\":\"W\",\"low\":200,\"high\":220}}]";

        byte[] fit = new FitExport().workoutFit(w);

        List<WorkoutStepMesg> steps = new ArrayList<>();
        MesgBroadcaster broadcaster = new MesgBroadcaster(new Decode());
        broadcaster.addListener((WorkoutStepMesgListener) steps::add);
        broadcaster.run(new ByteArrayInputStream(fit));

        assertEquals(1, steps.size());
        // 600 s → raw duration_value = 600 * 1000 ms, so a device reads 600 s.
        // The Python bug stored 600 here (read as 0.6 s) — this must be 600000.
        assertEquals(600_000, steps.get(0).getDurationValue().intValue());
    }
}
