package io.gamov.irontrainer.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garmin.fit.BufferEncoder;
import com.garmin.fit.DateTime;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.Fit;
import com.garmin.fit.Intensity;
import com.garmin.fit.Manufacturer;
import com.garmin.fit.Sport;
import com.garmin.fit.WktStepDuration;
import com.garmin.fit.WktStepTarget;
import com.garmin.fit.WorkoutMesg;
import com.garmin.fit.WorkoutStepMesg;
import io.gamov.irontrainer.plan.PlannedWorkout;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Date;

/** FIT encoding via Garmin's OFFICIAL SDK — same conventions as the Python
 * exporter (power raw = watts + 1000, HR raw = bpm + 100). Durations are
 * encoded FIT-SPEC-CORRECT (seconds via the SDK's scaled setter → ms raw),
 * deliberately diverging from the Python exporter's suspected unscaled-raw
 * bug (iron-trainer-sqib): v2 must not reproduce a defect. */
@ApplicationScoped
public class FitExport {

    private final ObjectMapper mapper = new ObjectMapper();

    public byte[] workoutFit(PlannedWorkout w) {
        try {
            BufferEncoder encoder = new BufferEncoder(Fit.ProtocolVersion.V2_0);

            FileIdMesg fileId = new FileIdMesg();
            fileId.setType(com.garmin.fit.File.WORKOUT);
            fileId.setManufacturer(Manufacturer.DEVELOPMENT);
            fileId.setProduct(1);
            fileId.setSerialNumber(1L);
            fileId.setTimeCreated(new DateTime(new Date()));
            encoder.write(fileId);

            JsonNode steps = (w.structureJson == null || w.structureJson.isBlank())
                    ? mapper.createArrayNode() : mapper.readTree(w.structureJson);
            if (!steps.isArray()) steps = steps.path("steps");

            WorkoutMesg workout = new WorkoutMesg();
            workout.setWktName(w.title == null ? "Workout" : w.title);
            workout.setSport(sport(w.sport));
            workout.setNumValidSteps(steps.isArray() ? steps.size() : 0);
            encoder.write(workout);

            int idx = 0;
            for (JsonNode step : steps) {
                encoder.write(step(idx++, step));
            }
            return encoder.close();
        } catch (Exception e) {
            throw new IllegalStateException("FIT encoding failed", e);
        }
    }

    private WorkoutStepMesg step(int index, JsonNode s) {
        WorkoutStepMesg m = new WorkoutStepMesg();
        m.setMessageIndex(index);
        m.setIntensity(switch (s.path("type").asText("")) {
            case "warmup" -> Intensity.WARMUP;
            case "cooldown" -> Intensity.COOLDOWN;
            case "recover", "rest" -> Intensity.REST;
            default -> Intensity.ACTIVE;
        });
        int dur = s.path("duration_s").asInt(0);
        double dist = s.path("distance_m").asDouble(0);
        if (dur > 0) {
            m.setDurationType(WktStepDuration.TIME);
            m.setDurationTime((float) dur);  // SDK setter is scaled: seconds in, ms raw out
        } else if (dist > 0) {
            m.setDurationType(WktStepDuration.DISTANCE);
            m.setDurationDistance((float) dist);
        } else {
            m.setDurationType(WktStepDuration.OPEN);
        }
        JsonNode t = s.path("target");
        String type = t.path("type").asText("");
        long low = t.path("low").asLong(0);
        long high = t.path("high").asLong(0);
        if (type.equals("power") && low > 0 && high > 0) {
            m.setTargetType(WktStepTarget.POWER);
            m.setTargetValue(0L);
            m.setCustomTargetPowerLow(low + 1000);
            m.setCustomTargetPowerHigh(high + 1000);
        } else if (type.equals("hr") && low > 0 && high > 0) {
            m.setTargetType(WktStepTarget.HEART_RATE);
            m.setTargetValue(0L);
            m.setCustomTargetHeartRateLow(low + 100);
            m.setCustomTargetHeartRateHigh(high + 100);
        } else {
            m.setTargetType(WktStepTarget.OPEN);
            m.setTargetValue(0L);
        }
        return m;
    }

    private static Sport sport(String s) {
        if (s == null) return Sport.GENERIC;
        return switch (s) {
            case "Bike", "Brick" -> Sport.CYCLING;
            case "Run" -> Sport.RUNNING;
            case "Swim" -> Sport.SWIMMING;
            default -> Sport.GENERIC;
        };
    }
}
