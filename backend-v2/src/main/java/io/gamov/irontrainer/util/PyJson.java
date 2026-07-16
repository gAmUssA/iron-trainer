package io.gamov.irontrainer.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Match Python's json.dumps + datetime.isoformat byte-for-byte, so JSON blobs
 * and timestamps this backend WRITES to the shared DB (inputs_json, result_json,
 * structure_json, created_at) are identical to what FastAPI writes. Jackson's
 * default is compact ({"a":1}); Python's default has ", "/": " separators
 * ({"a": 1}). */
public final class PyJson {

    private PyJson() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** MinimalPrettyPrinter emits no newlines; override the separators to match
     * json.dumps' default (item ", ", key/value ": "). */
    private static final PrettyPrinter DUMPS = new MinimalPrettyPrinter() {
        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(": ");
        }
        @Override
        public void writeObjectEntrySeparator(JsonGenerator g) throws IOException {
            g.writeRaw(", ");
        }
        @Override
        public void writeArrayValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(", ");
        }
    };

    // Python isoformat: microsecond precision, explicit +00:00 offset (not 'Z').
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    /** json.dumps(obj) — compact but with ", "/": " spacing. */
    public static String dumps(Object obj) {
        try {
            return MAPPER.writer(DUMPS).writeValueAsString(obj);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("json.dumps failed", e);
        }
    }

    /** datetime.now(timezone.utc).isoformat() — e.g. 2026-07-16T12:34:56.789012+00:00. */
    public static String utcNowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).format(ISO);
    }
}
