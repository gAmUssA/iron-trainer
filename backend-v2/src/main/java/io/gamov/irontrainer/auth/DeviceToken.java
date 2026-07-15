package io.gamov.irontrainer.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Shared device_token table — bearer credentials (SHA-256 hashed at rest),
 * same contract as the FastAPI side. */
@Entity
@Table(name = "device_token")
public class DeviceToken extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "athlete_id")
    public Integer athleteId;

    public String name;

    @Column(name = "token_hash")
    public String tokenHash;
}
