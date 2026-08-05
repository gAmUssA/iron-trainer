package io.gamov.irontrainer.whoop;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** One WHOOP journal answer for one day (from journal_entries.csv). Joined to
 * WhoopCycle on (athlete_id, date) for behavior→recovery correlations. */
@Entity
@Table(name = "whoop_journal")
@IdClass(WhoopJournalEntry.PK.class)
public class WhoopJournalEntry extends PanacheEntityBase {

    @Id
    @Column(name = "athlete_id")
    public Integer athleteId;

    @Id
    public String date;

    @Id
    public String question;

    @Column(name = "answered_yes")
    public Boolean answeredYes;

    public String notes;

    @Column(name = "updated_at")
    public String updatedAt;

    /** Composite key (athlete_id, date, question). */
    public static class PK implements Serializable {
        public Integer athleteId;
        public String date;
        public String question;

        public PK() {}

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(athleteId, pk.athleteId) && Objects.equals(date, pk.date)
                    && Objects.equals(question, pk.question);
        }

        @Override
        public int hashCode() {
            return Objects.hash(athleteId, date, question);
        }
    }
}
