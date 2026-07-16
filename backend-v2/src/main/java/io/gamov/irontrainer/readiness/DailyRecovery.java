package io.gamov.irontrainer.readiness;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Shared daily_recovery table (read side) — the fields readiness reads. */
@Entity
@Table(name = "daily_recovery")
public class DailyRecovery extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    public String date;

    @Column(name = "sleep_h")
    public Double sleepH;

    @Column(name = "hrv_ms")
    public Double hrvMs;

    @Column(name = "rhr_bpm")
    public Double rhrBpm;
}
