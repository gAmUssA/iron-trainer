package io.gamov.irontrainer.whoop;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
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
}
