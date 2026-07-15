package io.gamov.irontrainer.metrics;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** Shared metrics_daily table (read side). Composite PK (athlete_id, date),
 * matching the FastAPI SQLModel. */
@Entity
@Table(name = "metrics_daily")
@IdClass(MetricDaily.PK.class)
public class MetricDaily extends PanacheEntityBase {

    @Id
    @Column(name = "athlete_id")
    public Integer athleteId;

    @Id
    public String date;

    public Double tss;
    public Double ctl;
    public Double atl;
    public Double tsb;

    /** Composite key. */
    public static class PK implements Serializable {
        public Integer athleteId;
        public String date;

        public PK() {}

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(athleteId, pk.athleteId) && Objects.equals(date, pk.date);
        }

        @Override
        public int hashCode() {
            return Objects.hash(athleteId, date);
        }
    }
}
