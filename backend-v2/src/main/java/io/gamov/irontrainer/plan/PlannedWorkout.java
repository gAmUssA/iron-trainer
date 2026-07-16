package io.gamov.irontrainer.plan;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Shared planned_workouts table (read side for the exports vertical). */
@Entity
@Table(name = "planned_workouts")
public class PlannedWorkout extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    @Column(name = "plan_id")
    public Integer planId;

    public String date;
    public String sport;
    public String title;
    public String description;
    public String intensity;

    @Column(name = "structure_json")
    public String structureJson;

    @Column(name = "duration_s")
    public Integer durationS;

    @Column(name = "distance_m")
    public Double distanceM;
}
