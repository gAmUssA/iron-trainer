package io.gamov.irontrainer;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.Map;

/** Skeleton wiring check: REST → Panache → Postgres (Dev Services in dev/test). */
@Path("/api/v2/probe")
public class ProbeResource {

    @GET
    public Map<String, Object> status() {
        return Map.of("backend", "v2", "athletes", AthleteProbe.count());
    }

    @POST
    @Transactional
    public Map<String, Object> seed() {
        AthleteProbe a = new AthleteProbe();
        a.name = "Viktor";
        a.ftp = 228.0;
        a.thresholdHr = 160;
        a.persist();
        return Map.of("id", a.id, "athletes", AthleteProbe.count());
    }
}
