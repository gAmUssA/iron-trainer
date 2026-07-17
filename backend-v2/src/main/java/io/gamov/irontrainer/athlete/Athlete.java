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

    // Nutrition profile (shared schema columns; read by the nutrition vertical).
    @Column(name = "body_weight_kg")
    public Double bodyWeightKg;

    @Column(name = "gel_carb_g")
    public Double gelCarbG;

    @Column(name = "sweat_rate_l_h")
    public Double sweatRateLH;

    // save_profile bumps this on any threshold change (iOS delta-sync watches it).
    @Column(name = "updated_at")
    public String updatedAt;

    // Strava OAuth tokens. refresh_token presence signals a connection; the sync
    // refreshes the access token when strava_token_expires_at is near.
    @Column(name = "strava_refresh_token")
    public String stravaRefreshToken;

    @Column(name = "strava_access_token")
    public String stravaAccessToken;

    @Column(name = "strava_token_expires_at")
    public Long stravaTokenExpiresAt;
}
