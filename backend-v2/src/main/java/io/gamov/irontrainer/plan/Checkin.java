package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.util.PyJson;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;

/** Shared checkin table — the compounding memory each weekly check-in persists.
 * Port of the FastAPI Checkin model + repo.save_checkin. */
@Entity
@Table(name = "checkin")
public class Checkin extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    public String date;

    @Column(name = "created_at")
    public String createdAt;

    @Column(name = "inputs_json")
    public String inputsJson;

    @Column(name = "story_json")
    public String storyJson;

    @Column(name = "readiness_json")
    public String readinessJson;

    /** save_checkin: persist one check-in. Must run inside a transaction.
     * inputs/readiness → null column when absent (Python `json.dumps(x) if x`). */
    public static void save(int athleteId, String day, Map<String, Object> inputs,
                            List<String> story, Map<String, Object> readiness) {
        Checkin c = new Checkin();
        c.athleteId = athleteId;
        c.date = day;
        c.createdAt = PyJson.utcNowIso();
        c.inputsJson = (inputs == null || inputs.isEmpty()) ? null : PyJson.dumps(inputs);
        c.storyJson = PyJson.dumps(story);
        c.readinessJson = (readiness == null || readiness.isEmpty()) ? null : PyJson.dumps(readiness);
        c.persist();
    }
}
