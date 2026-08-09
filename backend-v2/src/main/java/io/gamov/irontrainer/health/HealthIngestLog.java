package io.gamov.irontrainer.health;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Audit row per POST /api/health/ingest (bean j05e). The ingest itself is
 * fire-and-forget (upsert + return counts); this makes it observable in the
 * admin console — when health data arrived, from which client, and what was
 * dropped. Deliberately does NOT store the payload (PII + size); just the stats.
 * One row per POST (Health Auto Export batches large syncs into several POSTs).
 */
@Entity
@Table(name = "health_ingest_log")
public class HealthIngestLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    /** Nullable: an unauthenticated post with data lands days_stored=0, no athlete. */
    @Column(name = "athlete_id")
    public Integer athleteId;

    /** hae | native | unknown — see HealthResource.detectSource. */
    public String source;

    @Column(name = "received_at")
    public String receivedAt;

    public Boolean ok;

    @Column(name = "days_stored")
    public Integer daysStored;

    public Integer records;

    @Column(name = "unknown_metrics")
    public Integer unknownMetrics;

    @Column(name = "bad_dates")
    public Integer badDates;

    @Column(name = "byte_size")
    public Integer byteSize;

    @Column(name = "user_agent")
    public String userAgent;

    public String error;
}
