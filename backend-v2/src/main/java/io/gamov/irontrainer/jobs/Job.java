package io.gamov.irontrainer.jobs;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The shared job table (source of truth for background work, same contract
 * as the FastAPI side: queued → running → succeeded | failed, ISO strings). */
@Entity
@Table(name = "job")
public class Job extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    public String kind;
    public String status;

    @Column(name = "created_at")
    public String createdAt;

    @Column(name = "started_at")
    public String startedAt;

    @Column(name = "finished_at")
    public String finishedAt;

    @Column(name = "result_json")
    public String resultJson;

    public String error;
}
