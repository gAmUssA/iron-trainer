package io.gamov.irontrainer.races;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;

/** Global IRONMAN event catalog (not owned by an athlete). Mirrors the FastAPI
 * Race model — column set + model_dump field order. */
@Entity
@Table(name = "race")
public class Race extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    public String slug;
    public String name;
    public String date;       // ISO YYYY-MM-DD
    public String distance;   // "70.3" | "140.6"
    public String city;
    public String country;

    @Column(name = "cutoff_swim_s")
    public Integer cutoffSwimS;

    @Column(name = "cutoff_bike_s")
    public Integer cutoffBikeS;

    @Column(name = "cutoff_finish_s")
    public Integer cutoffFinishS;

    /** Race.model_dump(): all fields in definition order (id, slug, name, date,
     * distance, city, country, cutoff_swim_s, cutoff_bike_s, cutoff_finish_s). */
    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("slug", slug);
        m.put("name", name);
        m.put("date", date);
        m.put("distance", distance);
        m.put("city", city);
        m.put("country", country);
        m.put("cutoff_swim_s", cutoffSwimS);
        m.put("cutoff_bike_s", cutoffBikeS);
        m.put("cutoff_finish_s", cutoffFinishS);
        return m;
    }
}
