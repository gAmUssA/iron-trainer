package io.gamov.irontrainer.dashboards;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.util.Iso;
import io.gamov.irontrainer.util.Py;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Race-readiness projection — port of dashboards.race_readiness: estimate race
 * splits at current fitness (swim ~ CSS·1.06, bike ~ recent long-ride speed,
 * run ~ threshold pace·1.10) and compare cumulative times to the cut-offs. */
public final class RaceReadiness {

    private RaceReadiness() {}

    private static final Map<String, Map<String, Integer>> LEG_DISTANCES = Map.of(
            "70.3", Map.of("swim", 1900, "bike", 90_000, "run", 21_100),
            "140.6", Map.of("swim", 3860, "bike", 180_000, "run", 42_200));

    /** Sustainable race-day bike intensity as a fraction of FTP. 70.3 is ridden
     * appreciably harder than a full — these are the conventional coaching
     * targets for a strong age-grouper.
     *
     * <p>These are quoted as NORMALIZED power (IF is defined NP/FTP) but are used
     * below against an AVERAGE observed power. That is sound only because a
     * well-paced race bike leg is ridden near-steady — variability index ~1.0, so
     * NP ~ AP. It is an assumption about PACING, and it degrades in exactly one
     * direction: a rider who surges over a hilly course has VI > 1, making the
     * true average lower than this and the projection optimistic. */
    private static final Map<String, Double> RACE_IF = Map.of("70.3", 0.78, "140.6", 0.70);

    /** Ceiling on the intensity correction. The cube-root scaling is only sound
     * while race power is near the observed range; a projection claiming you'll
     * ride 30% faster than any ride you have actually done is not credible
     * whatever the arithmetic says. Clamped rather than dropped so a rider who
     * genuinely only ever trains easy still gets a (capped) correction. Widen it
     * if calibration against real race files shows the cap biting on honest data. */
    private static final double MIN_SCALE = 0.85;
    private static final double MAX_SCALE = 1.25;

    /** Projected race bike speed (m/s) and how it was derived. `basis` is
     * reported to the client so the projection can say what it is standing on. */
    public record BikeSpeed(double speedMs, String basis) {}

    /** Projected race bike speed from rides of >= 1 h in the last 84 days.
     *
     * <p>Speed — not FTP — is the anchor: the observed figure already carries this
     * athlete's CdA, rolling resistance, mass and terrain, none of which we could
     * responsibly guess. But those rides were ridden at TRAINING intensity, so
     * using them raw projects the race at endurance effort and reads systematically
     * slow. FTP supplies the missing piece — how much harder race day is:
     *
     * <pre>v_race = v_observed x (P_race / P_observed)^(1/3)</pre>
     *
     * <p>The ratio form is the whole point: CdA, Crr and mass cancel, so no
     * physical constants are assumed. The cube root is the pure-aerodynamic
     * relationship; real power also carries a rolling term linear in v, so a given
     * power increase buys slightly MORE speed than this predicts — the projection
     * errs slow, never fast, which is the right direction for a cut-off check.
     *
     * <p>Falls back to the uncorrected mean when FTP or ride power is missing
     * (an Apple-Watch-only ride with no bike-computer copy carries no power).
     * Correction is computed over the power-bearing subset ONLY — mixing a
     * powerless ride's speed into v_observed would pair it with a power it never
     * had. Null when there are no qualifying rides at all. */
    static BikeSpeed recentBikeSpeed(List<Activity> activities, Thresholds th, String distance) {
        LocalDate cutoff = LocalDate.now().minusDays(84);   // date.today() - 84d
        double sum = 0;
        int n = 0;
        double powSpeedSum = 0;      // speed over the power-bearing subset
        double powSum = 0;           // matching power over that same subset
        int np = 0;
        for (Activity a : activities) {
            LocalDate d = Iso.parseDate(a.startDate);   // dashboards._day
            if (d == null || d.isBefore(cutoff) || !"Bike".equals(a.sport)) {
                continue;
            }
            int moving = a.movingTime != null ? a.movingTime : 0;
            if (moving < 3600 || !Py.truthy(a.avgSpeed)) {
                continue;
            }
            sum += a.avgSpeed;
            n++;
            // AVERAGE power, deliberately not weighted/normalized. NP is a
            // physiological load metric, not the mean mechanical power that
            // produced avg_speed — the speed relation P = a*v^3 + b*v is defined
            // on means, and NP >= AP always. Pairing avg_speed with NP would
            // understate P_race/P_observed and, on a variable ride (160 W avg /
            // 220 W NP), can flip the correction from scaling up to scaling down.
            // Coasting dilutes avg_speed and avg_power alike, so they stay a
            // consistent pair.
            Double p = a.avgPower;
            if (Py.truthy(p)) {
                powSpeedSum += a.avgSpeed;
                powSum += p;
                np++;
            }
        }
        if (n == 0) {
            return null;
        }
        Double ftp = th == null ? null : th.ftp();
        if (Py.truthy(ftp) && np > 0 && powSum > 0) {
            double raceP = RACE_IF.getOrDefault(String.valueOf(distance), RACE_IF.get("70.3")) * ftp;
            double obsP = powSum / np;
            double scale = Math.cbrt(raceP / obsP);
            scale = Math.max(MIN_SCALE, Math.min(scale, MAX_SCALE));
            return new BikeSpeed(powSpeedSum / np * scale, "measured_speed_ftp_scaled");
        }
        return new BikeSpeed(sum / n, "measured_speed");
    }

    /** _fmt_hms: int(seconds) then h:mm:ss (truncates — NOT rounded). */
    static String fmtHms(double seconds) {
        long s = (long) seconds;   // int() truncates toward zero
        long h = s / 3600;
        long rem = s % 3600;
        // Locale.ROOT: ASCII digits, no grouping — match Python's f-string.
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, rem / 60, rem % 60);
    }

    /** Race intensity as a whole-percent string for the note ("78", "70"). */
    private static String racePct(String distance) {
        double f = RACE_IF.getOrDefault(String.valueOf(distance), RACE_IF.get("70.3"));
        return String.valueOf(Py.roundInt(f * 100));
    }

    private static Map<String, Object> leg(double seconds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seconds", Py.roundInt(seconds));   // round(); display uses raw
        m.put("display", fmtHms(seconds));
        return m;
    }

    public static Map<String, Object> raceReadiness(List<Activity> activities, Thresholds th,
                                                    Double currentCtl, Map<String, Integer> cutoffs,
                                                    String distance) {
        Map<String, Object> legs = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        int t1 = 5 * 60;
        int t2 = 3 * 60;
        // Python: LEG_DISTANCES.get(str(distance), LEG_DISTANCES["70.3"]) — the
        // key is stringified first, so a null distance (no race selected) maps to
        // "null" → miss → the 70.3 default (Map.of is null-hostile, hence valueOf).
        Map<String, Integer> d = LEG_DISTANCES.getOrDefault(
                String.valueOf(distance), LEG_DISTANCES.get("70.3"));

        Double swimS = null;
        if (Py.truthy(th.cssSwim())) {
            double swimPace = th.cssSwim() * 1.06;             // sec/100m at race effort
            swimS = d.get("swim") / 100.0 * swimPace;
            legs.put("swim", leg(swimS));
        } else {
            missing.add("css_swim");
        }

        Double bikeS = null;
        BikeSpeed bikeSpeed = recentBikeSpeed(activities, th, distance);
        boolean ftpScaled = false;
        if (bikeSpeed != null && bikeSpeed.speedMs() > 0) {
            bikeS = d.get("bike") / bikeSpeed.speedMs();
            Map<String, Object> bikeLeg = leg(bikeS);
            bikeLeg.put("basis", bikeSpeed.basis());
            legs.put("bike", bikeLeg);
            ftpScaled = "measured_speed_ftp_scaled".equals(bikeSpeed.basis());
        } else {
            missing.add("bike_speed_history");
        }

        Double runS = null;
        if (Py.truthy(th.thresholdPaceRun())) {
            double runPace = th.thresholdPaceRun() * 1.10;     // sec/km off the bike
            runS = d.get("run") / 1000.0 * runPace;
            legs.put("run", leg(runS));
        } else {
            missing.add("threshold_pace_run");
        }

        int transitionsS = t1 + t2;
        // total sums the ALREADY-ROUNDED leg seconds + transitions (if any legs).
        double totalS = 0;
        for (Object legO : legs.values()) {
            totalS += ((Number) ((Map<?, ?>) legO).get("seconds")).doubleValue();
        }
        if (!legs.isEmpty()) {
            totalS += transitionsS;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("legs", legs);
        Map<String, Object> trans = new LinkedHashMap<>();
        trans.put("seconds", transitionsS);
        trans.put("display", fmtHms(transitionsS));
        out.put("transitions", trans);
        out.put("total", legs.isEmpty() ? null : leg(totalS));
        out.put("current_ctl", currentCtl != null ? Py.round(currentCtl, 1) : null);
        out.put("missing", missing);
        out.put("cutoffs", cutoffChecks(swimS, bikeS, runS, t1, t2, cutoffs));
        out.put("note", ftpScaled
                ? "Projection at current fitness. Bike leg is your own long-ride speed scaled "
                        + "from ride power to race intensity (" + racePct(distance) + "% of FTP); "
                        + "swim and run come from your thresholds. Edit thresholds to refine."
                : "Projection at current fitness from your thresholds and recent bike speed. "
                        + "Edit thresholds to refine.");
        return out;
    }

    private static List<Map<String, Object>> cutoffChecks(Double swimS, Double bikeS, Double runS,
                                                          int t1, int t2, Map<String, Integer> cutoffs) {
        // Python `cutoffs or {default}` — an empty (falsy) map falls back too, not
        // just null; otherwise c.get("swim") is null → NPE unboxing into int limit.
        Map<String, Integer> c = (cutoffs != null && !cutoffs.isEmpty()) ? cutoffs
                : Map.of("swim", 70 * 60, "bike", 330 * 60, "finish", 510 * 60);
        List<Map<String, Object>> checks = new ArrayList<>();
        addCheck(checks, "Swim", swimS, c.get("swim"));
        Double cumBike = (swimS != null && bikeS != null) ? swimS + t1 + bikeS : null;
        addCheck(checks, "Bike", cumBike, c.get("bike"));
        Double cumFinish = (swimS != null && bikeS != null && runS != null)
                ? swimS + t1 + bikeS + t2 + runS : null;
        addCheck(checks, "Finish", cumFinish, c.get("finish"));
        return checks;
    }

    private static void addCheck(List<Map<String, Object>> checks, String name, Double projected,
                                 int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkpoint", name);
        m.put("limit_s", limit);
        m.put("limit", fmtHms(limit));
        if (projected == null) {
            m.put("projected_s", null);
            m.put("ok", null);
            checks.add(m);
            return;
        }
        double margin = limit - projected;
        m.put("projected_s", Py.roundInt(projected));
        m.put("projected", fmtHms(projected));
        m.put("margin_s", Py.roundInt(margin));
        m.put("margin", (margin < 0 ? "-" : "+") + fmtHms(Math.abs(margin)));
        m.put("ok", margin >= 0);
        checks.add(m);
    }
}
