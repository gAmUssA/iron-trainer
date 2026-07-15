package io.gamov.irontrainer.athlete;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Map;

/** v2 wiring check against the REAL shared schema — read-only; the strangler's
 * first public vertical (exports) comes next. Writes live in SeedResource,
 * which is compiled out of prod builds entirely. */
@Path("/api/v2/athletes")
public class AthleteResource {

    @GET
    public Map<String, Object> summary() {
        return Map.of("backend", "v2", "athletes", Athlete.count());
    }
}
