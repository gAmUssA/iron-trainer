package io.gamov.irontrainer.whoop;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The athlete's latest AI analysis of their WHOOP data — persisted because the
 * LLM call is slow and paid, so the result must survive page reloads. */
@Entity
@Table(name = "whoop_insight")
public class WhoopInsight extends PanacheEntityBase {

    @Id
    @Column(name = "athlete_id")
    public Integer athleteId;

    @Column(name = "analysis_md")
    public String analysisMd;

    @Column(name = "created_at")
    public String createdAt;

    // Paid-call rate limit: runs so far on runs_date (UTC day).
    @Column(name = "runs_date")
    public String runsDate;

    @Column(name = "runs_count")
    public Integer runsCount;
}
