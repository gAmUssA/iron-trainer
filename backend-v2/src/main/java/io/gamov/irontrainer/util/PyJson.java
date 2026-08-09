package io.gamov.irontrainer.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** JSON + timestamp helpers. Historically these reproduced Python's json.dumps
 * spacing byte-for-byte because the DB blobs (inputs_json, result_json, …) were
 * shared with a FastAPI backend. FastAPI is decommissioned — backend-v2 is the
 * sole reader/writer of these blobs and only ever parses them — so dumps now emits
 * plain compact JSON. The timestamp format is still ISO-8601-UTC (kept: it is the
 * iOS wire format and the columns are still text with lexicographic range queries). */
public final class PyJson {

    private PyJson() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Python isoformat: microsecond precision, explicit +00:00 offset (not 'Z').
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    /** Serialize to compact JSON. (Only backend-v2 reads these blobs; it parses
     * them, so whitespace is insignificant.) */
    public static String dumps(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("json.dumps failed", e);
        }
    }

    /** Parse a stored JSON blob into Maps/Lists/scalars. THROWS on malformed input
     * — a caller reading a possibly-null column should apply its own type-appropriate
     * default first (e.g. [] for a list, {} for a map) rather than passing null. */
    public static Object loads(String s) {
        try {
            return MAPPER.readValue(s, Object.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("json.loads failed", e);
        }
    }

    /** datetime.now(timezone.utc).isoformat() — e.g. 2026-07-16T12:34:56.789012+00:00. */
    public static String utcNowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).format(ISO);
    }

    /** Same format as {@link #utcNowIso()}, N days ago — a lexicographically-correct
     * lower bound for comparing against stored created_at strings. */
    public static String utcIsoDaysAgo(long days) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusDays(days).truncatedTo(ChronoUnit.MICROS).format(ISO);
    }
}
