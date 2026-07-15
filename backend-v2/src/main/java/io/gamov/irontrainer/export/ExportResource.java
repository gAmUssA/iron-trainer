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

    @Inject
    ZwoExport zwo;

    @Inject
    FitExport fit;

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

    @GET
    @Path("/workout/{id}.zwo")
    @Produces("application/xml")
    public Response workoutZwo(@PathParam("id") int id) {
        int athleteId = current.require();
        PlannedWorkout w = owned(id, athleteId);
        Athlete a = Athlete.findById(athleteId);
        String xml = zwo.workoutZwo(w, a == null ? null : a.ftp);
        if (xml == null) {
            throw new NotFoundException("No ZWO for this workout (needs a Bike/Brick session and an athlete FTP)");
        }
        return Response.ok(xml)
                .header("Content-Disposition", "attachment; filename=\"workout-" + id + ".zwo\"")
                .build();
    }

    @GET
    @Path("/workout/{id}.fit")
    @Produces("application/octet-stream")
    public Response workoutFit(@PathParam("id") int id) {
        int athleteId = current.require();
        PlannedWorkout w = owned(id, athleteId);
        return Response.ok(fit.workoutFit(w))
                .header("Content-Disposition", "attachment; filename=\"workout-" + id + ".fit\"")
                .build();
    }

    @GET
    @Path("/plan.itw")
    @Produces("application/json")
    public Response planItw() {
        int athleteId = current.require();
        io.gamov.irontrainer.plan.Plan plan = io.gamov.irontrainer.plan.Plan.activeFor(athleteId);
        java.util.List<PlannedWorkout> workouts = plan == null ? java.util.List.of()
                : PlannedWorkout.list("planId = ?1 and athleteId = ?2 order by date", plan.id, athleteId);
        Athlete a = Athlete.findById(athleteId);
        return Response.ok(itw.planItw(workouts, plan, a))
                .header("Content-Disposition", "attachment; filename=\"iron-trainer-plan.itw\"")
                .build();
    }

    private PlannedWorkout owned(int id, int athleteId) {
        PlannedWorkout w = PlannedWorkout
                .find("id = ?1 and athleteId = ?2", id, athleteId).firstResult();
        if (w == null) {
            throw new NotFoundException();
        }
        return w;
    }
}
