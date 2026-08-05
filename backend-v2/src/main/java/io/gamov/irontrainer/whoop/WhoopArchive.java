package io.gamov.irontrainer.whoop;

import io.gamov.irontrainer.strava.StravaArchive;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jboss.logging.Logger;

/** Parse a WHOOP member data-export ZIP ("Export my data" in the WHOOP app) into
 * per-day WhoopCycle rows. Only physiological_cycles.csv is read — it carries the
 * proprietary Recovery % and Day Strain that never reach Apple Health. workouts.csv
 * and sleeps.csv are ignored: their raw content already arrives via HealthKit for
 * users with WHOOP→Apple Health sync (bean iron-trainer-ids6). */
public final class WhoopArchive {

    private static final Logger LOG = Logger.getLogger(WhoopArchive.class);

    // WHOOP export timestamps: "2026-07-28 06:41:12" (UTC; "Cycle timezone" holds
    // the member's offset, e.g. "UTC-04:00").
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);

    static final long MAX_MEMBER_BYTES = 100L * 1024 * 1024;

    private WhoopArchive() {
    }

    /** Everything usable from one export ZIP. */
    public record Export(List<WhoopCycle> cycles, List<WhoopJournalEntry> journal) {}

    /** @throws IllegalArgumentException (→ 400) when the ZIP isn't a WHOOP export. */
    public static Export parse(Path zipPath) {
        List<WhoopCycle> cycles = new ArrayList<>();
        List<WhoopJournalEntry> journal = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            int skipped = 0;
            for (Map<String, String> row : csvRows(zf, "physiological_cycles.csv", true)) {
                WhoopCycle c = rowToCycle(row);
                if (c == null) {
                    skipped++;
                } else {
                    cycles.add(c);
                }
            }
            // Journal is optional garnish — an export without it still imports.
            // Both CSVs carry "Cycle start time", so journal answers join their
            // cycle exactly and inherit its wake-date (an end-date heuristic
            // would misattribute after-midnight bedtimes by one day).
            Map<String, String> dateByCycleStart = new LinkedHashMap<>();
            for (WhoopCycle c : cycles) {
                if (c.cycleStart != null) {
                    dateByCycleStart.put(c.cycleStart, c.date);
                }
            }
            for (Map<String, String> row : csvRows(zf, "journal_entries.csv", false)) {
                WhoopJournalEntry j = rowToJournal(row, dateByCycleStart);
                if (j != null) {
                    journal.add(j);
                }
            }
            LOG.infof("Parsed WHOOP export: %d cycles (%d rows skipped), %d journal answers.",
                    cycles.size(), skipped, journal.size());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read the export ZIP: " + e.getMessage(), e);
        }
        return new Export(cycles, journal);
    }

    private static List<Map<String, String>> csvRows(ZipFile zf, String fileName, boolean required)
            throws Exception {
        ZipEntry entry = zf.stream()
                .filter(e -> e.getName().toLowerCase(Locale.ROOT).endsWith(fileName))
                .findFirst().orElse(null);
        if (entry == null) {
            if (required) {
                throw new IllegalArgumentException(
                        "No " + fileName + " found — is this a WHOOP data export ZIP?");
            }
            return List.of();
        }
        if (entry.getSize() > MAX_MEMBER_BYTES) {
            throw new IllegalArgumentException(fileName + " is implausibly large — refusing to import.");
        }
        String text;
        try (InputStream in = zf.getInputStream(entry)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        return StravaArchive.csvDictRows(text);
    }

    private static WhoopJournalEntry rowToJournal(Map<String, String> row,
                                                  Map<String, String> dateByCycleStart) {
        String question = blank(col(row, "Question text"));
        if (question == null) {
            return null;
        }
        String start = blank(col(row, "Cycle start time"));
        String date = start == null ? null : dateByCycleStart.get(start);
        if (date == null) {
            // Cycle not in (or dropped from) physiological_cycles.csv — fall back
            // to the local cycle-end date (≈ wake day), then cycle start.
            int offsetSec = tzOffsetSeconds(col(row, "Cycle timezone"));
            date = localDate(col(row, "Cycle end time"), offsetSec);
            if (date == null) {
                date = localDate(start, offsetSec);
            }
        }
        if (date == null) {
            return null;
        }
        WhoopJournalEntry j = new WhoopJournalEntry();
        j.date = date;
        j.question = question;
        String yes = blank(col(row, "Answered yes"));
        j.answeredYes = yes == null ? null : Boolean.parseBoolean(yes.toLowerCase(Locale.ROOT));
        j.notes = blank(col(row, "Notes"));
        return j;
    }

    private static WhoopCycle rowToCycle(Map<String, String> row) {
        String start = col(row, "Cycle start time");
        String end = col(row, "Cycle end time");
        String wake = col(row, "Wake onset");
        int offsetSec = tzOffsetSeconds(col(row, "Cycle timezone"));
        // The cycle's calendar day is the LOCAL wake date (recovery is scored on
        // waking; strain accumulates through that day). Fall back to cycle end,
        // then cycle start, for unscored/still-open cycles.
        String date = localDate(wake, offsetSec);
        if (date == null) {
            date = localDate(end, offsetSec);
        }
        if (date == null) {
            date = localDate(start, offsetSec);
        }
        if (date == null) {
            return null;
        }
        WhoopCycle c = new WhoopCycle();
        c.date = date;
        c.cycleStart = blank(start);
        c.cycleEnd = blank(end);
        c.recoveryScore = StravaArchive.num(col(row, "Recovery score %"));
        c.hrvRmssdMs = StravaArchive.num(col(row, "Heart rate variability (ms)"));
        c.rhrBpm = StravaArchive.num(col(row, "Resting heart rate (bpm)"));
        c.dayStrain = StravaArchive.num(col(row, "Day Strain"));
        c.energyKcal = StravaArchive.num(col(row, "Energy burned (cal)"));
        c.spo2Pct = StravaArchive.num(col(row, "Blood oxygen %"));
        c.skinTempC = StravaArchive.num(col(row, "Skin temp (celsius)"));
        c.sleepPerformancePct = StravaArchive.num(col(row, "Sleep performance %"));
        c.sleepEfficiencyPct = StravaArchive.num(col(row, "Sleep efficiency %"));
        c.respiratoryRate = StravaArchive.num(col(row, "Respiratory rate (rpm)"));
        Double asleepMin = StravaArchive.num(col(row, "Asleep duration (min)"));
        c.asleepH = asleepMin == null ? null : asleepMin / 60.0;
        return c;
    }

    /** Header lookup tolerant of WHOOP renaming columns between export versions:
     * exact match first, then case/punctuation-insensitive prefix match. */
    static String col(Map<String, String> row, String name) {
        String v = row.get(name);
        if (v != null) {
            return v;
        }
        String want = norm(name);
        for (Map.Entry<String, String> e : row.entrySet()) {
            String have = norm(e.getKey());
            // Either direction: "Recovery score %" matches "Recovery Score", and
            // "Heart rate variability" matches "Heart rate variability (ms)".
            if (!have.isEmpty() && (have.startsWith(want) || want.startsWith(have))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String norm(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toLowerCase(s.charAt(i));
            if (Character.isLetterOrDigit(ch)) {
                b.append(ch);
            }
        }
        return b.toString();
    }

    /** "UTC-04:00" / "UTC+05:30" → seconds; blank/garbage → 0 (dates stay UTC —
     * wake times are mid-morning, so the day is still right for sane offsets). */
    static int tzOffsetSeconds(String tz) {
        if (tz == null) {
            return 0;
        }
        String s = tz.strip().toUpperCase(Locale.ROOT).replace("UTC", "");
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return ZoneOffset.of(s).getTotalSeconds();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** UTC timestamp string + offset → local ISO date, or null when unparseable. */
    static String localDate(String ts, int offsetSec) {
        if (ts == null || ts.strip().isEmpty()) {
            return null;
        }
        try {
            LocalDateTime t = LocalDateTime.parse(ts.strip(), TS);
            return t.plusSeconds(offsetSec).toLocalDate().toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blank(String v) {
        if (v == null) {
            return null;
        }
        String s = v.strip();
        return s.isEmpty() ? null : s;
    }
}
