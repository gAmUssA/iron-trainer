package io.gamov.irontrainer.athlete;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.Map;

/** v2 wiring check against the REAL shared schema. Read + one test-support
 * write; the strangler's first public vertical (exports) comes next. */
@Path("/api/v2/athletes")
public class AthleteResource {

    @GET
    public Map<String, Object> summary() {
        return Map.of("backend", "v2", "athletes", Athlete.count());
    }

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
