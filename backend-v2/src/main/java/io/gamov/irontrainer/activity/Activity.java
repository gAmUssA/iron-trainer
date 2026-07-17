package io.gamov.irontrainer.activity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared activities table (read side). Strava activity id is explicit (not
 * generated). Read-mostly subset of columns for the verticals that need history
 * (fitness-test prefill; later, race_readiness). */
@Entity
@Table(name = "activities")
public class Activity extends PanacheEntityBase {

    @Id  // Strava id, not auto-assigned
    public Long id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    public String sport;

    @Column(name = "start_date")
    public String startDate;

    public String name;

    @Column(name = "moving_time")
    public Integer movingTime;

    public Double distance;

    @Column(name = "avg_power")
    public Double avgPower;

    @Column(name = "weighted_power")
    public Double weightedPower;

    @Column(name = "avg_hr")
    public Double avgHr;

    @Column(name = "is_duplicate")
    public Integer isDuplicate;

    // Write side (recompute_tss): the derived training load per activity.
    public Double tss;

    @Column(name = "intensity_factor")
    public Double intensityFactor;

    @Column(name = "tss_method")
    public String tssMethod;

    // Dedup: device source + which activity in a duplicate cluster is kept.
    @Column(name = "device_name")
    public String deviceName;

    @Column(name = "has_power_meter")
    public Integer hasPowerMeter;

    @Column(name = "primary_id")
    public Long primaryId;

    // Written by the Strava sync upsert (_map_activity), not read by ported reads.
    @Column(name = "elapsed_time")
    public Integer elapsedTime;

    @Column(name = "max_hr")
    public Double maxHr;

    @Column(name = "avg_speed")
    public Double avgSpeed;

    @Column(name = "elevation_gain")
    public Double elevationGain;

    @Column(name = "created_at")
    public String createdAt;

    // NOTE: raw_json is deliberately NOT mapped here. It's a large Strava JSON
    // blob and this entity is loaded in bulk by the metrics rebuild + Strava
    // sync, which never need it — mapping it would eagerly SELECT the blob on
    // every read. The /api/activities feed (the only reader that needs it)
    // fetches it with a separate projection query. raw_json is added to the
    // dict by that resource, not here.

    /** Activity.model_dump() minus raw_json (see note above). Key order is
     * irrelevant to the parity `==` (Python dict equality), but kept faithful. */
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("athlete_id", athleteId);
        m.put("sport", sport);
        m.put("start_date", startDate);
        m.put("name", name);
        m.put("moving_time", movingTime);
        m.put("elapsed_time", elapsedTime);
        m.put("distance", distance);
        m.put("avg_power", avgPower);
        m.put("weighted_power", weightedPower);
        m.put("avg_hr", avgHr);
        m.put("max_hr", maxHr);
        m.put("avg_speed", avgSpeed);
        m.put("elevation_gain", elevationGain);
        m.put("has_power_meter", hasPowerMeter);
        m.put("tss", tss);
        m.put("intensity_factor", intensityFactor);
        m.put("tss_method", tssMethod);
        m.put("device_name", deviceName);
        m.put("is_duplicate", isDuplicate);
        m.put("primary_id", primaryId);
        m.put("created_at", createdAt);
        return m;
    }
}
