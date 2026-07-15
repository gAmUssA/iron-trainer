package io.gamov.irontrainer.athlete;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** First REAL entity against the shared schema (V1 = prod pg_dump baseline).
 * Read-mostly subset of columns; Strava token columns intentionally unmapped
 * here — the auth vertical owns those later. */
@Entity
@Table(name = "athlete")
public class Athlete extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // FastAPI schema uses serial ids
    public Integer id;

    public String name;

    @Column(name = "strava_athlete_id")
    public Long stravaAthleteId;

    public Double ftp;

    @Column(name = "threshold_hr")
    public Integer thresholdHr;

    @Column(name = "max_hr")
    public Integer maxHr;

    @Column(name = "threshold_pace_run")
    public Double thresholdPaceRun;

    @Column(name = "css_swim")
    public Double cssSwim;

    @Column(name = "weekly_hours_target")
    public Double weeklyHoursTarget;
}
