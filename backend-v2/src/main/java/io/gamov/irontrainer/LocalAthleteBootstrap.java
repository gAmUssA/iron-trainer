package io.gamov.irontrainer;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/** Creates the single local athlete on first boot when auth is disabled (bean zvc2).
 *
 * <p>With {@code auth-required=false}, {@link io.gamov.irontrainer.auth.BearerAuthFilter}
 * hands every unauthenticated request {@code default-athlete-id} as its identity. On the
 * SaaS deployment that row is created by Strava OAuth / Apple sign-in / device pairing,
 * so it always exists. On a fresh self-host install nothing creates it — and the failure
 * is invisible: {@code /api/status} reports {@code authenticated:true} and every READ
 * returns 200 with an empty payload, while every WRITE dies on a foreign key:
 *
 * <pre>
 * insert or update on table "fitness_test_result" violates foreign key constraint
 * Detail: Key (athlete_id)=(1) is not present in table "athlete".
 * </pre>
 *
 * <p>The condition here is deliberately the SAME one BearerAuthFilter uses to hand out
 * that id. Tying them together is the point: the row must exist exactly when, and only
 * when, the filter will use it. A separate "local mode" flag would be a second source of
 * truth that could drift (the explicit flag arrives with bean 4lve — when it does, this
 * condition should follow it rather than gain a parallel one).
 *
 * <p>Never runs on the SaaS deployment, which sets {@code AUTH_REQUIRED=true}.
 */
@ApplicationScoped
public class LocalAthleteBootstrap {

    private static final Logger LOG = Logger.getLogger(LocalAthleteBootstrap.class);

    @jakarta.inject.Inject
    EntityManager em;

    /** Runs late so Flyway has finished creating the schema. Quarkus performs
     * migrate-at-start from its own StartupEvent observer; an observer without an
     * explicit priority could otherwise run first and hit a table that does not
     * exist yet. */
    @Transactional
    void onStart(@Observes @Priority(Integer.MAX_VALUE) StartupEvent ev) {
        // Read at runtime rather than field-injected, so a native image doesn't bake
        // in the build-time value — same reason StartupBanner does it this way.
        boolean authRequired = ConfigProvider.getConfig()
                .getOptionalValue("irontrainer.auth-required", Boolean.class)
                .orElse(false);
        if (authRequired) {
            return;   // SaaS: athlete rows belong to the auth flow
        }
        int id = ConfigProvider.getConfig()
                .getOptionalValue("irontrainer.default-athlete-id", Integer.class)
                .orElse(1);

        // Explicit id, because the column is a serial that Hibernate maps as IDENTITY —
        // persisting an entity would allocate a different id than the one the auth
        // filter hands out. ON CONFLICT makes this safe on every restart and upgrade.
        int inserted = em.createNativeQuery(
                        "insert into athlete (id, name) values (?1, ?2) on conflict (id) do nothing")
                .setParameter(1, id)
                .setParameter(2, "Me")
                .executeUpdate();

        if (inserted > 0) {
            // An explicit-id insert does not advance the serial's sequence, so a later
            // normal insert (connecting Strava, say) would try to reuse this id and fail
            // on the primary key. Push the sequence past whatever is now in the table.
            em.createNativeQuery(
                    "select setval('athlete_id_seq', greatest((select max(id) from athlete), 1))")
                    .getSingleResult();
            LOG.infof("Local mode: created athlete %d — auth is disabled, so every request "
                    + "is this athlete.", id);
        } else {
            LOG.debugf("Local mode: athlete %d already exists.", id);
        }
    }
}
