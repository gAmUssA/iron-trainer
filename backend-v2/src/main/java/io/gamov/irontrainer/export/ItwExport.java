package io.gamov.irontrainer.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.plan.PlannedWorkout;
import jakarta.enterprise.context.ApplicationScoped;

/** Port of app/export/itw_export.py — the neutral, versioned JSON the iOS app
 * turns into WorkoutKit compositions. Contract: schema_version 1, athlete
 * thresholds embedded (self-contained file), steps passed through verbatim
 * from structure_json. */
@ApplicationScoped
public class ItwExport {

    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper();

    public String workoutItw(PlannedWorkout w, Athlete a) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc(w, a));
        } catch (Exception e) {
            throw new IllegalStateException("ITW serialization failed", e);
        }
    }

    private ObjectNode doc(PlannedWorkout w, Athlete a) throws Exception {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("schema_version", SCHEMA_VERSION);
        doc.put("generator", "iron-trainer");
        doc.put("date", w.date);
        doc.put("sport", w.sport);
        doc.put("title", w.title);
        doc.put("description", w.description);
        if (w.durationS != null) doc.put("duration_s", w.durationS); else doc.putNull("duration_s");
        if (w.distanceM != null) doc.put("distance_m", w.distanceM); else doc.putNull("distance_m");
        ObjectNode athlete = doc.putObject("athlete");
        athlete.put("ftp", a == null ? null : a.ftp);
        athlete.put("threshold_hr", a == null ? null : a.thresholdHr);
        athlete.put("max_hr", a == null ? null : a.maxHr);
        athlete.put("threshold_pace_run", a == null ? null : a.thresholdPaceRun);
        athlete.put("css_swim", a == null ? null : a.cssSwim);
        // Steps pass through verbatim — same shape fit/zwo exporters consume.
        if (w.structureJson != null && !w.structureJson.isBlank()) {
            var parsed = mapper.readTree(w.structureJson);
            var steps = parsed.isArray() ? parsed : parsed.path("steps");
            doc.set("steps", steps.isArray() ? steps : mapper.createArrayNode());
        } else {
            doc.set("steps", mapper.createArrayNode());
        }
        return doc;
    }
}
