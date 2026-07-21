package io.gamov.irontrainer.app;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.health.HealthIngest;
import io.gamov.irontrainer.readiness.DailyRecovery;
import io.gamov.irontrainer.util.Params;
import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/** GET /api/health — liveness by default; ?deep=1 also checks DB connectivity
 * (503 when down). Port of main.health. Unauthenticated (a readiness probe). */
@Path("/api/health")
public class HealthResource {

    private static final Logger LOG = Logger.getLogger(HealthResource.class);

    @Inject
    DataSource dataSource;

    @Inject
    CurrentAthlete current;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response health(@QueryParam("deep") String deepParam) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("version", AppInfo.VERSION);
        if (Params.boolOr(deepParam, false)) {
            // Probe on a raw pooled connection OUTSIDE any JTA transaction (like
            // FastAPI's separate engine connection). NOT @Transactional: a failed
            // SELECT would mark the request transaction rollback-only and the
            // interceptor would turn this intended 503 into a bodyless 500.
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("SELECT 1");
                out.put("database", "ok");
            } catch (Exception e) {
                // Full error to the logs only — DB errors can echo the DSN; this
                // endpoint is unauthenticated.
                LOG.errorf(e, "Deep health check failed");
                out.put("status", "degraded");
                out.put("database", "error");
                out.put("detail", "database unreachable — see server logs");
                return Response.status(503).entity(out).build();
            }
        }
        return Response.ok(out).build();
    }

    /** GET /api/health/recovery?days=N — recent recovery rows, newest first
     * (dashboard / debugging). Port of health_router.recovery + repo.recent_recovery. */
    @GET
    @Path("/recovery")
    @Produces(MediaType.APPLICATION_JSON)
    @jakarta.transaction.Transactional
    public Map<String, Object> recovery(@QueryParam("days") String daysParam) {
        int aid = current.require();
        // Params.intParam (not a raw @QueryParam int) → 422 on a non-numeric value,
        // matching FastAPI's `days: int = 35` (a raw int would 404 on RESTEasy).
        int days = daysParam == null ? 35 : Params.intParam(daysParam);
        int limit = Math.max(1, Math.min(days, 365));
        List<DailyRecovery> rows = DailyRecovery
                .<DailyRecovery>find("athleteId = ?1 order by date desc", aid).page(0, limit).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DailyRecovery r : rows) {
            // model_dump(exclude={id, athlete_id}) — SQLModel field order.
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", r.date);
            m.put("updated_at", r.updatedAt);
            m.put("sleep_h", r.sleepH);
            m.put("deep_h", r.deepH);
            m.put("rem_h", r.remH);
            m.put("awake_h", r.awakeH);
            m.put("sleep_start", r.sleepStart);
            m.put("sleep_end", r.sleepEnd);
            m.put("hrv_ms", r.hrvMs);
            m.put("rhr_bpm", r.rhrBpm);
            m.put("weight_kg", r.weightKg);
            m.put("vo2max", r.vo2max);
            m.put("respiratory_rate", r.respiratoryRate);
            m.put("wrist_temp_c", r.wristTempC);
            m.put("hr_recovery_bpm", r.hrRecoveryBpm);
            m.put("spo2_pct", r.spo2Pct);
            m.put("active_energy_kcal", r.activeEnergyKcal);
            m.put("exercise_min", r.exerciseMin);
            m.put("step_count", r.stepCount);
            m.put("cycling_ftp_w", r.cyclingFtpW);
            out.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("days", out);
        return resp;
    }

    /** POST /api/health/ingest — Health Auto Export recovery ingestion. Malformed
     * JSON gets a fast 200 {ok:false} (the app surfaces non-2xx as automation
     * errors); auth is enforced only when there's something to store, matching
     * FastAPI (upsert → current_athlete_id → 401). Port of health_router.ingest. */
    @POST
    @Path("/ingest")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> ingest(String body) {
        Object parsed;
        try {
            parsed = PyJson.loads(body == null ? "" : body);
        } catch (Exception e) {
            LOG.warn("Health ingest: malformed JSON body");
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("ok", false);
            bad.put("error", "invalid JSON");
            bad.put("days", 0);
            return bad;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = parsed instanceof Map<?, ?> mp ? (Map<String, Object>) mp : Map.of();
        HealthIngest.Result r = HealthIngest.parsePayload(payload);
        int stored = 0;
        for (Map.Entry<String, Map<String, Object>> e : r.days.entrySet()) {
            try {
                // Resolve the athlete INSIDE the per-day try, like FastAPI (whose
                // upsert_daily_recovery calls current_athlete_id): an auth failure
                // (401) is caught here and swallowed, so an unauthenticated caller
                // with data gets {ok:true, days:0} — NOT a 401. Matches
                // health_router.ingest's `except Exception`.
                int aid = current.require();
                upsertDailyRecovery(aid, e.getKey(), e.getValue());
                stored++;
            } catch (Exception ex) {   // one bad day (or a 401) must not 500 the batch
                LOG.warnf("Recovery upsert failed for %s: %s", e.getKey(), ex.toString());
            }
        }
        // Seed bike FTP from HealthKit's cycling-FTP estimate when the athlete has
        // none yet, so power zones work out of the box. Never OVERWRITE a set FTP —
        // a real bike-test value must not be clobbered by Apple's estimate. stored>0
        // means auth already succeeded, so current.require() here won't 401.
        if (stored > 0) {
            Double ftp = latestFtp(r.days);
            if (ftp != null && ftp > 0 && ftp < 2000) {
                try {
                    seedFtpIfUnset(current.require(), ftp);
                } catch (Exception ex) {
                    LOG.warnf("FTP seed skipped: %s", ex.toString());
                }
            }
        }
        Map<String, Object> parsedOut = new LinkedHashMap<>();
        parsedOut.put("records", r.records);
        parsedOut.put("unknown_metrics", r.unknownMetrics);
        parsedOut.put("bad_dates", r.badDates);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("days", stored);
        out.put("parsed", parsedOut);
        return out;
    }

    /** upsert_daily_recovery: merge one day's fields, last-write-wins per field,
     * bump updated_at. Own transaction so one bad day doesn't roll back the batch. */
    private static void upsertDailyRecovery(int aid, String day, Map<String, Object> fields) {
        QuarkusTransaction.requiringNew().run(() -> {
            DailyRecovery row = DailyRecovery
                    .<DailyRecovery>find("athleteId = ?1 and date = ?2", aid, day).firstResult();
            if (row == null) {
                row = new DailyRecovery();
                row.athleteId = aid;
                row.date = day;
                row.persist();
            }
            applyFields(row, fields);
            row.updatedAt = PyJson.utcNowIso();
        });
    }

    private static void applyFields(DailyRecovery row, Map<String, Object> f) {
        if (f.get("sleep_h") != null) {
            row.sleepH = asD(f.get("sleep_h"));
        }
        if (f.get("deep_h") != null) {
            row.deepH = asD(f.get("deep_h"));
        }
        if (f.get("rem_h") != null) {
            row.remH = asD(f.get("rem_h"));
        }
        if (f.get("awake_h") != null) {
            row.awakeH = asD(f.get("awake_h"));
        }
        if (f.get("sleep_start") != null) {
            row.sleepStart = (String) f.get("sleep_start");
        }
        if (f.get("sleep_end") != null) {
            row.sleepEnd = (String) f.get("sleep_end");
        }
        if (f.get("hrv_ms") != null) {
            row.hrvMs = asD(f.get("hrv_ms"));
        }
        if (f.get("rhr_bpm") != null) {
            row.rhrBpm = asD(f.get("rhr_bpm"));
        }
        if (f.get("weight_kg") != null) {
            row.weightKg = asD(f.get("weight_kg"));
        }
        if (f.get("vo2max") != null) {
            row.vo2max = asD(f.get("vo2max"));
        }
        if (f.get("respiratory_rate") != null) {
            row.respiratoryRate = asD(f.get("respiratory_rate"));
        }
        if (f.get("wrist_temp_c") != null) {
            row.wristTempC = asD(f.get("wrist_temp_c"));
        }
        if (f.get("hr_recovery_bpm") != null) {
            row.hrRecoveryBpm = asD(f.get("hr_recovery_bpm"));
        }
        if (f.get("spo2_pct") != null) {
            row.spo2Pct = asD(f.get("spo2_pct"));
        }
        if (f.get("active_energy_kcal") != null) {
            row.activeEnergyKcal = asD(f.get("active_energy_kcal"));
        }
        if (f.get("exercise_min") != null) {
            row.exerciseMin = asD(f.get("exercise_min"));
        }
        if (f.get("step_count") != null) {
            row.stepCount = asD(f.get("step_count"));
        }
        if (f.get("cycling_ftp_w") != null) {
            row.cyclingFtpW = asD(f.get("cycling_ftp_w"));
        }
    }

    private static Double asD(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    /** The cycling FTP from the most recent day in the parsed batch, if any. */
    private static Double latestFtp(Map<String, Map<String, Object>> days) {
        String bestDay = null;
        Object ftp = null;
        for (Map.Entry<String, Map<String, Object>> e : days.entrySet()) {
            Object v = e.getValue().get("cycling_ftp_w");
            if (v != null && (bestDay == null || e.getKey().compareTo(bestDay) > 0)) {
                bestDay = e.getKey();
                ftp = v;
            }
        }
        return ftp instanceof Number n ? n.doubleValue() : null;
    }

    /** Set Athlete.ftp only when currently null (seed, never overwrite); bump
     * updated_at so the iOS delta-sync picks up the new power zones. Own txn. */
    private static void seedFtpIfUnset(int aid, double ftp) {
        QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = Athlete.findById(aid);
            if (a != null && a.ftp == null) {
                a.ftp = ftp;
                a.updatedAt = PyJson.utcNowIso();
            }
        });
    }
}
