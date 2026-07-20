package io.gamov.irontrainer.export;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.plan.Plan;
import io.gamov.irontrainer.plan.PlannedWorkout;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jboss.logging.Logger;

/** Exports vertical — first strangled path. Bearer-authenticated (iOS),
 * athlete-scoped lookups; cross-tenant ids 404 exactly like FastAPI. */
@Path("/api/export")
public class ExportResource {

    private static final Logger LOG = Logger.getLogger(ExportResource.class);

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
        LOG.infof("Export itw: athlete=%d workout=%d sport=%s", athleteId, id, w.sport);
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
        LOG.infof("Export zwo: athlete=%d workout=%d eligible=%b", athleteId, id, xml != null);
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
        LOG.infof("Export fit: athlete=%d workout=%d sport=%s", athleteId, id, w.sport);
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
                : PlannedWorkout.forPlan(athleteId, plan.id);   // shared (date, id) order
        Athlete a = Athlete.findById(athleteId);
        LOG.infof("Export plan.itw: athlete=%d workouts=%d plan=%s",
                athleteId, workouts.size(), plan == null ? "none" : plan.id);
        return Response.ok(itw.planItw(workouts, plan, a))
                .header("Content-Disposition", "attachment; filename=\"iron-trainer-plan.itw\"")
                .build();
    }

    /** GET /api/export/plan.zip — the whole active plan bundled: .fit for every
     * workout, .zwo for bike, .itw for all, + IMPORT_INSTRUCTIONS.txt. 404 when no
     * active plan. Port of export_router.plan_zip. */
    @GET
    @Path("/plan.zip")
    @Produces("application/zip")
    @Transactional
    public Response planZip() {
        int aid = current.require();
        Plan plan = Plan.activeFor(aid);
        List<PlannedWorkout> workouts = plan == null ? List.of() : PlannedWorkout.forPlan(aid, plan.id);
        if (workouts.isEmpty()) {
            throw new NotFoundException("No active plan to export");
        }
        return zipResponse(aid, workouts, "iron-trainer-plan.zip");
    }

    /** GET /api/export/week/{week_start}.zip — one week's workouts bundled. 404
     * when the week has none. Port of export_router.week_zip. */
    @GET
    @Path("/week/{week_start}.zip")
    @Produces("application/zip")
    @Transactional
    public Response weekZip(@PathParam("week_start") String weekStart) {
        int aid = current.require();
        // date.fromisoformat(week_start) — a malformed date propagates (500), parity.
        // The FILTER still compares the RAW week_start string (so a compact-form
        // "20260705" parses but excludes the extended-form dates → 404, like Python).
        String end = parseIsoDate(weekStart).plusDays(6).toString();
        Plan plan = Plan.activeFor(aid);
        List<PlannedWorkout> all = plan == null ? List.of() : PlannedWorkout.forPlan(aid, plan.id);
        List<PlannedWorkout> workouts = new ArrayList<>();
        for (PlannedWorkout w : all) {
            // week_start <= (w.date or "") <= end
            String d = w.date == null ? "" : w.date;
            if (d.compareTo(weekStart) >= 0 && d.compareTo(end) <= 0) {
                workouts.add(w);
            }
        }
        if (workouts.isEmpty()) {
            throw new NotFoundException("No workouts in that week");
        }
        return zipResponse(aid, workouts, "iron-trainer-week-" + weekStart + ".zip");
    }

    private Response zipResponse(int aid, List<PlannedWorkout> workouts, String name) {
        Athlete a = Athlete.findById(aid);
        byte[] data = bundleZip(workouts, a, a == null ? null : a.ftp);
        LOG.infof("Export %s: athlete=%d workouts=%d bytes=%d", name, aid, workouts.size(), data.length);
        return Response.ok(data)
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .build();
    }

    /** bundle_zip: .fit for every workout, .zwo for bike (when eligible), .itw for
     * all, + the README. Zip container bytes differ from Python's (deflate/tz), so
     * parity is on the extracted entries, not the raw archive. */
    private byte[] bundleZip(List<PlannedWorkout> workouts, Athlete a, Double ftp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Set<String> seen = new HashSet<>();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (PlannedWorkout w : workouts) {
                putEntry(z, seen, filename(w, "fit"), fit.workoutFit(w));
                String zwoXml = zwo.workoutZwo(w, ftp);
                if (zwoXml != null && !zwoXml.isEmpty()) {   // Python `if zwo:`
                    putEntry(z, seen, filename(w, "zwo"), zwoXml.getBytes(StandardCharsets.UTF_8));
                }
                putEntry(z, seen, filename(w, "itw"), itw.workoutItw(w, a).getBytes(StandardCharsets.UTF_8));
            }
            putEntry(z, seen, "IMPORT_INSTRUCTIONS.txt", readme());
        } catch (IOException e) {
            throw new RuntimeException("Failed to build the export zip", e);
        }
        return bos.toByteArray();
    }

    private static void putEntry(ZipOutputStream z, Set<String> seen, String name, byte[] data)
            throws IOException {
        // Java's ZipOutputStream throws on a duplicate entry name; Python's
        // zipfile.writestr tolerates it (both extract to the same file). Keep the
        // first → a 200 with a valid zip, not a 500. Only reachable for two
        // workouts with the same (date, sport, title).
        if (!seen.add(name)) {
            return;
        }
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    /** filename(workout, ext) = "{date}_{sport}_{slug(title)}.{ext}". A null date/
     * sport renders as "None" (Python f-string), not Java's "null". */
    private static String filename(PlannedWorkout w, String ext) {
        return noneIfNull(w.date) + "_" + noneIfNull(w.sport) + "_" + slug(w.title) + "." + ext;
    }

    private static String noneIfNull(String s) {
        return s == null ? "None" : s;
    }

    /** date.fromisoformat parity (3.11+): extended ("2026-07-05") OR basic
     * ("20260705") ISO; anything else propagates as a 500. */
    private static LocalDate parseIsoDate(String s) {
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
        }
    }

    /** _slug: non-alphanumerics → '-', trimmed of leading/trailing '-', ≤40 chars,
     * "workout" when empty. */
    static String slug(String text) {
        String s = (text == null ? "" : text).strip()
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40);
        }
        return s.isEmpty() ? "workout" : s;
    }

    // Lazy (not a static-final initializer): a resource-load failure then only
    // fails the zip request, not class init — which would 500 the already-shipped
    // .fit/.itw/.zwo/plan.itw endpoints too.
    private static volatile byte[] readmeCache;

    private static byte[] readme() {
        byte[] r = readmeCache;
        if (r == null) {
            readmeCache = r = loadReadme();
        }
        return r;
    }

    private static byte[] loadReadme() {
        try (InputStream in = ExportResource.class.getResourceAsStream("/import_instructions.txt")) {
            if (in == null) {
                throw new IllegalStateException("import_instructions.txt not on the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
