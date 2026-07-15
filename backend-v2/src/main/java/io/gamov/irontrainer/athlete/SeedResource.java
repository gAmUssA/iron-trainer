package io.gamov.irontrainer.athlete;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.Map;

/** Test/dev-only seeding. @IfBuildProfile removes this bean AT BUILD TIME for
 * prod — there is no unauthenticated write path against the shared schema in
 * a production artifact (Copilot review, PR #31). */
@IfBuildProfile(anyOf = {"dev", "test"})
@Path("/api/v2/athletes")
public class SeedResource {

    @POST
    @Transactional
    public Map<String, Object> create() {
        Athlete a = new Athlete();
        a.name = "Probe Athlete";
        a.ftp = 228.0;
        a.thresholdHr = 160;
        a.persist();
        return Map.of("id", a.id, "athletes", Athlete.count());
    }
}
