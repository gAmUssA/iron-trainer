package io.gamov.irontrainer.export;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.plan.PlannedWorkout;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

/** Exports vertical — first strangled path. Bearer-authenticated (iOS),
 * athlete-scoped lookups; cross-tenant ids 404 exactly like FastAPI. */
@Path("/api/export")
public class ExportResource {

    @Inject
    CurrentAthlete current;

    @Inject
    ItwExport itw;

    @GET
    @Path("/workout/{id}.itw")
    @Produces("application/json")
    public Response workoutItw(@PathParam("id") int id) {
        int athleteId = current.require();
        PlannedWorkout w = PlannedWorkout
                .find("id = ?1 and athleteId = ?2", id, athleteId).firstResult();
        if (w == null) {
            throw new NotFoundException();
        }
        Athlete a = Athlete.findById(athleteId);
        return Response.ok(itw.workoutItw(w, a))
                .header("Content-Disposition", "attachment; filename=\"workout-" + id + ".itw\"")
                .build();
    }
}
