package io.gamov.irontrainer;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Skeleton probe entity — proves Panache + Flyway + Dev Services wiring.
 * Replaced by real entities when the schema baseline lands (bean jedh). */
@Entity
@Table(name = "athlete_v2_probe")
public class AthleteProbe extends PanacheEntity {
    public String name;
    public Double ftp;
    @jakarta.persistence.Column(name = "threshold_hr")
    public Integer thresholdHr;
}
