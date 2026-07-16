package io.gamov.irontrainer.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared fitness_test_result table. A recorded test: the raw inputs entered and
 * the thresholds computed from them. */
@Entity
@Table(name = "fitness_test_result")
public class FitnessTestResult extends PanacheEntityBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    @Column(name = "test_slug")
    public String testSlug;

    public String sport;
    public String date;

    @Column(name = "inputs_json")
    public String inputsJson;

    @Column(name = "result_json")
    public String resultJson;

    public Boolean applied;

    @Column(name = "created_at")
    public String createdAt;

    private static Map<String, Object> parse(String json) {
        if (json == null) return new LinkedHashMap<>();  // Python: json.loads(x or "{}")
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = MAPPER.readValue(json, LinkedHashMap.class);
            return m;
        } catch (Exception e) {
            throw new RuntimeException("bad fitness_test_result JSON: " + json, e);
        }
    }

    /** Same key set + order as repo._test_result_dict: model_dump() minus the two
     * *_json columns, with parsed inputs/result appended. */
    public Map<String, Object> toRow() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("athlete_id", athleteId);
        r.put("test_slug", testSlug);
        r.put("sport", sport);
        r.put("date", date);
        r.put("applied", applied);
        r.put("created_at", createdAt);
        r.put("inputs", parse(inputsJson));
        r.put("result", parse(resultJson));
        return r;
    }
}
