package io.gamov.irontrainer.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gamov.irontrainer.plan.PlannedWorkout;
import jakarta.enterprise.context.ApplicationScoped;

/** Port of app/export/zwo_export.py — Zwift workout XML for bike power
 * sessions. Power as fraction of FTP; null for non-bike or FTP-less. */
@ApplicationScoped
public class ZwoExport {

    private final ObjectMapper mapper = new ObjectMapper();

    public String workoutZwo(PlannedWorkout w, Double ftp) {
        if (w.sport == null || !(w.sport.equals("Bike") || w.sport.equals("Brick"))) return null;
        if (ftp == null || ftp <= 0) return null;

        StringBuilder segments = new StringBuilder();
        try {
            JsonNode steps = w.structureJson == null ? mapper.createArrayNode()
                    : mapper.readTree(w.structureJson);
            if (!steps.isArray()) steps = steps.path("steps");
            for (JsonNode step : steps) {
                int dur = step.path("duration_s").asInt(0);
                if (dur <= 0) continue;
                JsonNode t = step.path("target");
                double lo = 0.6, hi = 0.6;
                if ("power".equals(t.path("type").asText())
                        && t.path("low").asDouble(0) > 0 && t.path("high").asDouble(0) > 0) {
                    lo = t.path("low").asDouble() / ftp;
                    hi = t.path("high").asDouble() / ftp;
                }
                String kind = step.path("type").asText("");
                if (kind.equals("warmup")) {
                    segments.append(String.format(
                        "    <Warmup Duration=\"%d\" PowerLow=\"%.3f\" PowerHigh=\"%.3f\"/>%n", dur, lo, hi));
                } else if (kind.equals("cooldown")) {
                    segments.append(String.format(
                        "    <Cooldown Duration=\"%d\" PowerLow=\"%.3f\" PowerHigh=\"%.3f\"/>%n", dur, lo, hi));
                } else {
                    segments.append(String.format(
                        "    <SteadyState Duration=\"%d\" Power=\"%.3f\"/>%n", dur, (lo + hi) / 2));
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (segments.isEmpty()) return null;

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<workout_file>\n"
                + "  <author>Iron Trainer</author>\n"
                + "  <name>" + xml(w.title == null ? "Bike workout" : w.title) + "</name>\n"
                + "  <description>" + xml(w.description == null ? "" : w.description) + "</description>\n"
                + "  <sportType>bike</sportType>\n  <workout>\n"
                + segments + "  </workout>\n</workout_file>\n";
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
