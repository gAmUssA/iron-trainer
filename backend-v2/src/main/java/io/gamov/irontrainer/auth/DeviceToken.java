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

    // Pairing dance: a short-lived code the native app exchanges for a token.
    @Column(name = "pairing_code")
    public String pairingCode;

    @Column(name = "pairing_expires_at")
    public Long pairingExpiresAt;

    @Column(name = "token_hash")
    public String tokenHash;

    @Column(name = "created_at")
    public String createdAt;

    @Column(name = "last_used_at")
    public String lastUsedAt;
}
