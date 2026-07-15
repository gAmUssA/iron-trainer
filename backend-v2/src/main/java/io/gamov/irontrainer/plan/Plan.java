package io.gamov.irontrainer.plan;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Shared plan table (read side). */
@Entity
@Table(name = "plan")
public class Plan extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    @Column(name = "race_name")
    public String raceName;

    @Column(name = "race_date")
    public String raceDate;

    public String status;
    public String summary;

    public static Plan activeFor(int athleteId) {
        return find("athleteId = ?1 and status = 'active'", athleteId).firstResult();
    }
}
