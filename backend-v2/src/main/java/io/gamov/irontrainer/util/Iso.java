package io.gamov.irontrainer.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Parse a stored start_date the way Python's
 * datetime.fromisoformat(s.replace("Z","+00:00")) does — accepting a date-only
 * value, a 'T' OR space separator, and an optional offset — then take the naive
 * wall-clock (offset stripped) or the calendar date. Shared by dedup (event
 * timestamps) and the metrics rebuild (day bucketing) so they parse identically. */
public final class Iso {

    private Iso() {}

    /** Naive wall-clock LocalDateTime, or null if unparseable (Python skips). */
    public static LocalDateTime parseDateTime(String s) {
        if (s == null) return null;
        String v = s.replace("Z", "+00:00").replace(' ', 'T');
        try {
            if (v.length() == 10) return LocalDate.parse(v).atStartOfDay();
            try {
                return OffsetDateTime.parse(v).toLocalDateTime();  // strip offset, keep wall-clock
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(v);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** The calendar date (Python .date()), or null. */
    public static LocalDate parseDate(String s) {
        LocalDateTime dt = parseDateTime(s);
        return dt == null ? null : dt.toLocalDate();
    }
}
