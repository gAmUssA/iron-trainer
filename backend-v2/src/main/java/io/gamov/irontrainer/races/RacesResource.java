package io.gamov.irontrainer.races;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.util.PyJson;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Races vertical — parity with FastAPI's races_router: the IRONMAN catalog
 * (GET /api/races, filterable) and per-athlete race selection
 * (PUT /api/athlete/race, catalog pick or custom). */
@Path("/api")
public class RacesResource {

    @Inject
    CurrentAthlete current;

    @Inject
    Races races;

    @GET
    @Path("/races")
    public Map<String, Object> listRaces(@QueryParam("distance") String distance,
                                         @QueryParam("country") String country,
                                         @QueryParam("month") String month,
                                         @QueryParam("q") String q) {
        // Guards mirror FastAPI's Python truthiness (`if distance:` etc.): an
        // empty-string param is NO filter, not a zero-match filter.
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (distance != null && !distance.isEmpty()) {
            clauses.add("distance = :distance");
            params.put("distance", distance);
        }
        if (country != null && !country.isEmpty()) {
            clauses.add("country = :country");
            params.put("country", country);
        }
        if (month != null && !month.isEmpty()) {
            clauses.add("date >= :monthStart and date <= :monthEnd");
            params.put("monthStart", month + "-01");
            params.put("monthEnd", month + "-31");
        }
        if (q != null && !q.isEmpty()) {
            clauses.add("(lower(name) like :like or lower(city) like :like)");
            // Locale.ROOT: match the DB lower() / Python str.lower(), not the
            // JVM default locale (e.g. Turkish dotless-i would mis-fold).
            params.put("like", "%" + q.toLowerCase(java.util.Locale.ROOT) + "%");
        }
        String where = String.join(" and ", clauses);
        String query = (where.isEmpty() ? "" : where + " ") + "order by date";
        List<Race> rows = params.isEmpty() ? Race.list(query) : Race.list(query, params);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Race r : rows) {
            out.add(r.toDict());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("races", out);
        return body;
    }

    public static class RaceSelect {
        @JsonProperty("race_id")
        public Integer raceId;
        public String name;
        @JsonProperty("race_date")
        public String raceDate;   // YYYY-MM-DD
        public String distance;   // "70.3" | "140.6"
    }

    @PUT
    @Path("/athlete/race")
    @Transactional
    public Map<String, Object> setRace(RaceSelect sel) {
        Athlete a = Athlete.findById(current.require());
        if (a == null) {
            throw new BadRequestException("No current athlete");  // ValueError → 400
        }
        Integer raceId = sel != null ? sel.raceId : null;
        if (raceId != null) {
            Race r = Race.findById(raceId);
            if (r == null) {
                throw new BadRequestException("Race " + raceId + " not found");
            }
            a.raceId = r.id;
            a.raceName = r.name;
            a.raceDate = r.date;
            a.raceDistance = r.distance;
            a.cutoffSwimS = r.cutoffSwimS;
            a.cutoffBikeS = r.cutoffBikeS;
            a.cutoffFinishS = r.cutoffFinishS;
        } else {
            String name = sel != null ? sel.name : null;
            String raceDate = sel != null ? sel.raceDate : null;
            // FastAPI: `if not (name and race_date)` — both must be truthy.
            if (name == null || name.isEmpty() || raceDate == null || raceDate.isEmpty()) {
                throw new BadRequestException("Custom race needs name and date");
            }
            String distance = sel != null ? sel.distance : null;
            a.raceId = null;
            a.raceName = name;
            a.raceDate = raceDate;
            a.raceDistance = distance;
            int[] c = Races.cutoffsFor(distance);
            a.cutoffSwimS = c[0];
            a.cutoffBikeS = c[1];
            a.cutoffFinishS = c[2];
        }
        a.updatedAt = PyJson.utcNowIso();
        a.persist();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("race", races.effectiveRace(a));
        return body;
    }
}
