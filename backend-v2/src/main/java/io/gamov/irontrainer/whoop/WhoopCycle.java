package io.gamov.irontrainer.whoop;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One WHOOP physiological cycle collapsed to its calendar day (wake date).
 * Fed by the member data-export ZIP (WhoopArchive); the future WHOOP API pull
 * (bean iron-trainer-4a6s) upserts into the same table. Kept separate from
 * daily_recovery: Recovery %/Strain have no HealthKit equivalent, and
 * hrv_rmssd_ms is RMSSD — NOT comparable to daily_recovery.hrv_ms (SDNN). */
@Entity
@Table(name = "whoop_cycles")
@IdClass(WhoopCycle.PK.class)
public class WhoopCycle extends PanacheEntityBase {

    @Id
    @Column(name = "athlete_id")
    public Integer athleteId;

    @Id
    public String date;

    @Column(name = "cycle_start")
    public String cycleStart;
    @Column(name = "cycle_end")
    public String cycleEnd;
    @Column(name = "recovery_score")
    public Double recoveryScore;
    @Column(name = "hrv_rmssd_ms")
    public Double hrvRmssdMs;
    @Column(name = "rhr_bpm")
    public Double rhrBpm;
    @Column(name = "day_strain")
    public Double dayStrain;
    @Column(name = "energy_kcal")
    public Double energyKcal;
    @Column(name = "spo2_pct")
    public Double spo2Pct;
    @Column(name = "skin_temp_c")
    public Double skinTempC;
    @Column(name = "sleep_performance_pct")
    public Double sleepPerformancePct;
    @Column(name = "sleep_efficiency_pct")
    public Double sleepEfficiencyPct;
    @Column(name = "respiratory_rate")
    public Double respiratoryRate;
    @Column(name = "asleep_h")
    public Double asleepH;
    @Column(name = "updated_at")
    public String updatedAt;

    /** 'zip' (member data export) or 'api' (live WHOOP API). Drives precedence in
     * WhoopSync.upsert — an 'api' row is never overwritten by a 'zip' row, so
     * re-uploading an old export cannot undo fresher live data. */
    @Column(name = "source")
    public String source;

    /** The API's own updated_at for this cycle. Within source='api', the newer one
     * wins; a re-fetch of unchanged data is a no-op. Null for ZIP rows, which carry
     * no per-row modification time — which is exactly why precedence ranks by
     * SOURCE first and only then by timestamp. */
    @Column(name = "api_updated_at")
    public String apiUpdatedAt;

    /** WHOOP's own cycle id, for traceability back to the API. Not a key here:
     * the table is keyed by (athlete, local wake date) so ZIP and API rows for the
     * same physiological day collapse onto one row. */
    @Column(name = "whoop_cycle_id")
    public Long whoopCycleId;

    public Map<String, Object> toRow() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date);
        m.put("recovery_score", recoveryScore);
        m.put("hrv_rmssd_ms", hrvRmssdMs);
        m.put("rhr_bpm", rhrBpm);
        m.put("day_strain", dayStrain);
        m.put("energy_kcal", energyKcal);
        m.put("spo2_pct", spo2Pct);
        m.put("skin_temp_c", skinTempC);
        m.put("sleep_performance_pct", sleepPerformancePct);
        m.put("sleep_efficiency_pct", sleepEfficiencyPct);
        m.put("respiratory_rate", respiratoryRate);
        m.put("asleep_h", asleepH);
        return m;
    }

    /** Composite key (athlete_id, date). */
    public static class PK implements Serializable {
        public Integer athleteId;
        public String date;

        public PK() {}

        public PK(Integer athleteId, String date) {
            this.athleteId = athleteId;
            this.date = date;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(athleteId, pk.athleteId) && Objects.equals(date, pk.date);
        }

        @Override
        public int hashCode() {
            return Objects.hash(athleteId, date);
        }
    }

    /** Collapse rows that share a date, deterministically (bean 80i2).
     *
     * <p>{@code (athlete_id, date)} is the primary key, so a day carrying two WHOOP
     * cycles cannot be represented — one is discarded. In a real 5.7-year export that
     * is <b>128 of 2085 days</b>: 21 spanning a timezone change, the rest days where
     * WHOOP simply recorded two cycles.
     *
     * <p>Without this, which cycle survives depends on ingest ORDER, so the export ZIP
     * and the API could keep DIFFERENT cycles for the same date. Observed on
     * 2025-10-07, a transatlantic travel day: the ZIP kept the 08:46-local wake
     * (recovery 30) and a later API sync overwrote it with the post-flight cycle
     * (recovery 10). Not a rounding difference — a different day's readiness.
     *
     * <p>Two rules, in order:
     * <ol>
     *   <li><b>A scored cycle beats an unscored one.</b> On 118 of those 128 days only
     *       one cycle has a recovery score, so this alone resolves them — and resolves
     *       them explicitly, rather than relying on the upsert's null-guard to do it
     *       by accident.</li>
     *   <li><b>Otherwise the earliest cycle start wins</b> — the morning cycle, which
     *       is the recovery WHOOP's own app shows for the day. The later one is the
     *       nap or the post-flight cycle. This decides the remaining 10 days.</li>
     * </ol>
     *
     * <p>Both ingest paths must call this, or the disagreement returns. cycleStart is
     * stored as UTC {@code yyyy-MM-dd HH:mm:ss} in both, so lexicographic order is
     * chronological order and no parsing is needed.
     */
    public static List<WhoopCycle> dedupeByDate(List<WhoopCycle> rows) {
        Map<String, WhoopCycle> best = new LinkedHashMap<>();
        for (WhoopCycle c : rows) {
            if (c == null || c.date == null) {
                continue;
            }
            WhoopCycle held = best.get(c.date);
            if (held == null || preferOver(c, held)) {
                best.put(c.date, c);
            }
        }
        return new ArrayList<>(best.values());
    }

    /** True when {@code cand} should replace {@code held} for the same date. */
    private static boolean preferOver(WhoopCycle cand, WhoopCycle held) {
        boolean candScored = cand.recoveryScore != null;
        boolean heldScored = held.recoveryScore != null;
        if (candScored != heldScored) {
            return candScored;
        }
        if (cand.cycleStart == null) {
            return false;
        }
        if (held.cycleStart == null) {
            return true;
        }
        return cand.cycleStart.compareTo(held.cycleStart) < 0;
    }
}
