package io.gamov.irontrainer.readiness;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Shared daily_recovery table — recovery data pushed from the phone (Health
 * Auto Export → /api/health/ingest) and read by readiness + /api/health/recovery.
 * Fields ordered to match the SQLModel DailyRecovery (recent_recovery's
 * model_dump order). */
@Entity
@Table(name = "daily_recovery")
public class DailyRecovery extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    public String date;

    @Column(name = "updated_at")
    public String updatedAt;

    @Column(name = "sleep_h")
    public Double sleepH;

    @Column(name = "deep_h")
    public Double deepH;

    @Column(name = "rem_h")
    public Double remH;

    @Column(name = "awake_h")
    public Double awakeH;

    @Column(name = "sleep_start")
    public String sleepStart;

    @Column(name = "sleep_end")
    public String sleepEnd;

    @Column(name = "hrv_ms")
    public Double hrvMs;

    @Column(name = "rhr_bpm")
    public Double rhrBpm;

    @Column(name = "weight_kg")
    public Double weightKg;

    @Column(name = "vo2max")
    public Double vo2max;

    @Column(name = "respiratory_rate")
    public Double respiratoryRate;

    @Column(name = "wrist_temp_c")
    public Double wristTempC;
}
