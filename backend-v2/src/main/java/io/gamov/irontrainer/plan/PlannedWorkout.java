package io.gamov.irontrainer.plan;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Shared planned_workouts table (read side for the exports vertical). */
@Entity
@Table(name = "planned_workouts")
public class PlannedWorkout extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    @Column(name = "plan_id")
    public Integer planId;

    public String date;
    public String sport;
    public String title;
    public String description;
    public String intensity;

    @Column(name = "structure_json")
    public String structureJson;

    @Column(name = "duration_s")
    public Integer durationS;

    @Column(name = "distance_m")
    public Double distanceM;

    // fit_path / zwo_path: cached export artifact paths (nullable). Not written
    // by v2 yet, but part of the model_dump shape GET /api/plan returns, so they
    // must round-trip for workout-dict parity.
    @Column(name = "fit_path")
    public String fitPath;

    @Column(name = "zwo_path")
    public String zwoPath;

    // Write side (fitness-test schedule): save_workouts / the SQLModel defaults
    // also set these. status defaults to "planned" (reconcile.py skips anything
    // that isn't "planned"); the Python model default is app-level, so the Java
    // writer must set it explicitly or the row stores NULL.
    public String status;

    // Set by reconcile when a planned session is matched to a Strava activity;
    // BigInteger column (Strava ids exceed int). Nullable.
    @Column(name = "matched_activity_id")
    public Long matchedActivityId;

    @Column(name = "planned_tss")
    public Double plannedTss;

    @Column(name = "created_at")
    public String createdAt;

    /** An athlete's workouts for a plan, in schedule order. Ordered by (date, id):
     * the template schedules multiple sports on the same date (Swim+Bike on day 1),
     * so date alone leaves ties unspecified — the id tiebreak (= insertion/schedule
     * order) keeps the list deterministic and byte-parity-stable vs FastAPI
     * (repo.get_workouts, matched order). Shared by the plan-read and export
     * verticals so both order identically. */
    public static java.util.List<PlannedWorkout> forPlan(int athleteId, int planId) {
        return list("athleteId = ?1 and planId = ?2 order by date, id", athleteId, planId);
    }

    /** save_workouts(replace_all=True): delete the plan's existing workouts and
     * insert the given ones. Must run inside a transaction. Returns the count.
     * Mirrors repo.save_workouts — status defaults to "planned"; steps →
     * structure_json (PyJson.dumps). */
    public static int saveAll(int athleteId, int planId, java.util.List<java.util.Map<String, Object>> workouts) {
        delete("planId = ?1", planId);
        for (java.util.Map<String, Object> w : workouts) {
            PlannedWorkout pw = new PlannedWorkout();
            pw.athleteId = athleteId;
            pw.planId = planId;
            pw.date = (String) w.get("date");
            pw.sport = (String) w.get("sport");
            pw.title = (String) w.get("title");
            pw.description = (String) w.get("description");
            pw.structureJson = io.gamov.irontrainer.util.PyJson.dumps(
                    w.getOrDefault("steps", java.util.List.of()));
            Object dur = w.get("duration_s");
            pw.durationS = dur == null ? null : ((Number) dur).intValue();
            Object dist = w.get("distance_m");
            pw.distanceM = dist == null ? null : ((Number) dist).doubleValue();
            Object tss = w.get("planned_tss");
            pw.plannedTss = tss == null ? null : ((Number) tss).doubleValue();
            pw.intensity = (String) w.get("intensity");
            pw.status = "planned";   // SQLModel default
            pw.createdAt = io.gamov.irontrainer.util.PyJson.utcNowIso();
            pw.persist();
        }
        return workouts.size();
    }
}
