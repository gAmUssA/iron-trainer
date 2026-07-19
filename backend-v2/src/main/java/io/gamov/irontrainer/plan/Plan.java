package io.gamov.irontrainer.plan;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Shared plan table (read side). */
@Entity
@Table(name = "plan")
public class Plan extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    @Column(name = "race_name")
    public String raceName;

    @Column(name = "race_date")
    public String raceDate;

    public String status;
    public String summary;

    @Column(name = "weeks_json")
    public String weeksJson;

    @Column(name = "base_weekly_hours")
    public Double baseWeeklyHours;

    @Column(name = "created_at")
    public String createdAt;

    public static Plan activeFor(int athleteId) {
        return find("athleteId = ?1 and status = 'active' order by id desc", athleteId).firstResult();
    }

    /** save_plan: supersede the athlete's current active plan and insert a new
     * active one from the season. Must run inside a transaction. Returns the id. */
    public static int savePlan(int athleteId, java.util.Map<String, Object> season) {
        update("status = 'superseded' where athleteId = ?1 and status = 'active'", athleteId);
        Plan p = new Plan();
        p.athleteId = athleteId;
        p.raceName = (String) season.get("race_name");
        p.raceDate = (String) season.get("race_date");
        p.status = "active";
        p.summary = (String) season.get("summary");
        Object bwh = season.get("base_weekly_hours");
        p.baseWeeklyHours = bwh == null ? null : ((Number) bwh).doubleValue();
        p.weeksJson = io.gamov.irontrainer.util.PyJson.dumps(
                season.getOrDefault("weeks", java.util.List.of()));
        p.createdAt = io.gamov.irontrainer.util.PyJson.utcNowIso();
        p.persist();
        return p.id;
    }
}
