package io.gamov.irontrainer;

import com.garmin.fit.Decode;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.WorkoutStepMesg;
import com.garmin.fit.WorkoutStepMesgListener;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spike item: the official Garmin FIT SDK (Java) reads files produced by the
 * Python fit-tool exporter, preserving our custom-target conventions
 * (power raw = watts + 1000, HR raw = bpm + 100, duration ms). Plain JUnit —
 * no Quarkus context needed. */
class FitInteropTest {

    @Test
    void decodesPythonGeneratedWorkout() throws Exception {
        List<WorkoutStepMesg> steps = new ArrayList<>();
        Decode decode = new Decode();
        MesgBroadcaster broadcaster = new MesgBroadcaster(decode);
        broadcaster.addListener((WorkoutStepMesgListener) steps::add);
        try (InputStream in = getClass().getResourceAsStream("/fit/reference_bike_py.fit")) {
            broadcaster.run(in);
        }

        assertEquals(4, steps.size());
        // FINDING (bean pending): the Python fit-tool exporter stores raw
        // duration_value = seconds (600), but the FIT profile scale is 1000 —
        // the official SDK reads it as 0.6 s. Suspected latent bug in the
        // Python export (never device-verified; Watch uses .itw, Zwift uses
        // ZWO). Asserting CURRENT behavior so any change is deliberate.
        assertEquals(600, steps.get(0).getDurationValue().intValue());
        assertEquals(1200, steps.get(1).getDurationValue().intValue());
        // Work step: power 200-220 W → raw 1200-1220.
        assertEquals(1200, steps.get(1).getCustomTargetPowerLow().intValue());
        assertEquals(1220, steps.get(1).getCustomTargetPowerHigh().intValue());
        // Recover step: HR 110-125 bpm → raw 210-225.
        assertEquals(210, steps.get(2).getCustomTargetHeartRateLow().intValue());
        assertEquals(225, steps.get(2).getCustomTargetHeartRateHigh().intValue());
    }
}
