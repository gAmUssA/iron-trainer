package io.gamov.irontrainer.races;

import io.gamov.irontrainer.athlete.Athlete;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Race helpers — distance→cutoff table and effective_race(), mirroring the
 * FastAPI repo. Config defaults match backend/app/config.py. */
@ApplicationScoped
public class Races {

    // Defaults are mapped to the SAME env vars FastAPI's Settings reads
    // (RACE_NAME / RACE_DATE / CUTOFF_*_S), so a deployment override desyncs
    // neither backend. See application.properties.
    @ConfigProperty(name = "irontrainer.race-name")
    String defaultRaceName;

    @ConfigProperty(name = "irontrainer.race-date")
    String defaultRaceDate;

    @ConfigProperty(name = "irontrainer.cutoff-swim-s")
    int defaultCutoffSwimS;

    @ConfigProperty(name = "irontrainer.cutoff-bike-s")
    int defaultCutoffBikeS;

    @ConfigProperty(name = "irontrainer.cutoff-finish-s")
    int defaultCutoffFinishS;

    /** _CUTOFFS[distance] → {swim, bike, finish} seconds; default "70.3". */
    public static int[] cutoffsFor(String distance) {
        if ("140.6".equals(distance)) {
            return new int[] {8400, 37800, 61200};
        }
        return new int[] {4200, 19800, 30600};  // "70.3" (and the default)
    }

    /** effective_race(): the athlete's selected race, else the config default.
     * The `or`-fallbacks mirror Python truthiness (null/empty/0 → default). */
    public Map<String, Object> effectiveRace(Athlete a) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a != null && a.raceDate != null && !a.raceDate.isEmpty()) {
            m.put("name", truthy(a.raceName) ? a.raceName : defaultRaceName);
            m.put("date", a.raceDate);
            m.put("distance", a.raceDistance);
            m.put("cutoff_swim_s", truthy(a.cutoffSwimS) ? a.cutoffSwimS : defaultCutoffSwimS);
            m.put("cutoff_bike_s", truthy(a.cutoffBikeS) ? a.cutoffBikeS : defaultCutoffBikeS);
            m.put("cutoff_finish_s", truthy(a.cutoffFinishS) ? a.cutoffFinishS : defaultCutoffFinishS);
        } else {
            m.put("name", defaultRaceName);
            m.put("date", defaultRaceDate);
            m.put("distance", null);
            m.put("cutoff_swim_s", defaultCutoffSwimS);
            m.put("cutoff_bike_s", defaultCutoffBikeS);
            m.put("cutoff_finish_s", defaultCutoffFinishS);
        }
        return m;
    }

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean truthy(Integer i) {
        return i != null && i != 0;
    }
}
