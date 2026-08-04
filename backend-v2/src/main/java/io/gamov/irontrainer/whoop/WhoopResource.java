package io.gamov.irontrainer.whoop;

import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.util.Params;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/** WHOOP member data-export import + per-day series. The ZIP path needs no
 * OAuth app approval (WHOOP caps unapproved API apps at 10 users) — the member
 * exports their own data from the WHOOP app and uploads it here. */
@Path("/api/whoop")
public class WhoopResource {

    private static final Logger LOG = Logger.getLogger(WhoopResource.class);

    // WHOOP exports are CSVs — a few MB even for years of data.
    private static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;

    @Inject
    CurrentAthlete current;

    /** POST /api/whoop/import — parse an uploaded WHOOP export ZIP and upsert
     * one row per (athlete, day). Re-uploading a newer export is idempotent:
     * same days are overwritten, new days appended. Sync — even a multi-year
     * export parses in well under a second. */
    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Map<String, Object> importArchive(@RestForm("file") FileUpload file) {
        int aid = current.require();
        if (file == null) {
            throw new BadRequestException("file is required");
        }
        if (file.size() > MAX_UPLOAD_BYTES) {
            throw new ClientErrorException("Archive exceeds the 200 MB upload limit.", 413);
        }
        List<WhoopCycle> cycles;
        try {
            cycles = WhoopArchive.parse(file.uploadedFile());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        // The export is newest-first; sort oldest-first so the summary reads
        // naturally and, on duplicate days (e.g. travel splitting a day across
        // two cycles), the LATER cycle wins the upsert.
        cycles.sort(Comparator.comparing((WhoopCycle c) -> c.date)
                .thenComparing(c -> c.cycleStart == null ? "" : c.cycleStart));
        String now = Instant.now().toString();
        int upserted = QuarkusTransaction.requiringNew().call(() -> {
            int n = 0;
            for (WhoopCycle c : cycles) {
                c.athleteId = aid;
                c.updatedAt = now;
                // merge = insert-or-update by the (athlete_id, date) PK.
                WhoopCycle.getEntityManager().merge(c);
                n++;
            }
            return n;
        });
        String first = cycles.isEmpty() ? null : cycles.get(0).date;
        String last = cycles.isEmpty() ? null : cycles.get(cycles.size() - 1).date;
        LOG.infof("Athlete %d imported WHOOP export: %d cycles (%s → %s).", aid, upserted, first, last);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cycles", upserted);
        out.put("first_date", first);
        out.put("last_date", last);
        return out;
    }

    /** GET /api/whoop/cycles?days=N — recent WHOOP days, newest first (mirrors
     * /api/health/recovery so the overlay page can zip the two by date). */
    @GET
    @Path("/cycles")
    @jakarta.transaction.Transactional
    public Map<String, Object> cycles(@QueryParam("days") String daysParam) {
        int aid = current.require();
        int days = daysParam == null ? 35 : Params.intParam(daysParam);
        int limit = Math.max(1, Math.min(days, 365));
        List<WhoopCycle> rows = WhoopCycle
                .<WhoopCycle>find("athleteId = ?1 order by date desc", aid).page(0, limit).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (WhoopCycle c : rows) {
            out.add(c.toRow());
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("days", out);
        return resp;
    }
}
