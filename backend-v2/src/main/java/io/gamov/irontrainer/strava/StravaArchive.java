package io.gamov.irontrainer.strava;

import com.garmin.fit.Decode;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.RecordMesgListener;
import com.garmin.fit.SessionMesgListener;
import io.gamov.irontrainer.metrics.Metrics;
import io.gamov.irontrainer.util.Py;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.jboss.logging.Logger;

/** Parse a Strava GDPR "Download your data" bulk-export ZIP into Strava-API-shaped
 * activity dicts that feed StravaSync's upsert. Reads activities.csv for every
 * activity's summary and — when a row points at a file in activities/ — parses the
 * .fit/.gpx/.tcx (decompressing .gz) for power/HR streams to derive normalized
 * power (which the CSV lacks). Any file that fails to parse falls back to the CSV
 * summary. Port of app/strava_import.py. */
public final class StravaArchive {

    private static final Logger LOG = Logger.getLogger(StravaArchive.class);

    // activities.csv "Activity Date" is non-ISO text, e.g. "Apr 30, 2021, 1:30:45 PM".
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm:ss a", Locale.US),
            DateTimeFormatter.ofPattern("MMM d, yyyy, H:mm:ss", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss", Locale.US),
    };
    // datetime.isoformat() with a 0-microsecond time = "YYYY-MM-DDTHH:MM:SS".
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // Decompression-bomb guard: no single member (nor its gzip payload) may expand
    // beyond this. Real per-activity files are a few MB; activities.csv tens of MB.
    static final long MAX_MEMBER_BYTES = 100L * 1024 * 1024;

    private StravaArchive() {
    }

    /** Parse a Strava bulk-export ZIP into Strava-API-shaped activity dicts.
     * @throws IllegalArgumentException (→ 400) when the ZIP isn't a Strava export. */
    public static List<Map<String, Object>> parse(Path zipPath) {
        List<Map<String, Object>> activities = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Set<String> names = new java.util.HashSet<>();
            zf.stream().forEach(e -> names.add(e.getName()));
            String csvName = names.stream().filter(n -> n.endsWith("activities.csv")).findFirst().orElse(null);
            if (csvName == null) {
                throw new IllegalArgumentException("No activities.csv found — is this a Strava data export ZIP?");
            }
            if (zf.getEntry(csvName).getSize() > MAX_MEMBER_BYTES) {
                throw new IllegalArgumentException("activities.csv is implausibly large — refusing to import.");
            }
            String text = new String(readAll(zf, csvName), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1);   // strip UTF-8 BOM (Python utf-8-sig)
            }
            List<Map<String, String>> rows = csvDictRows(text);
            int parsed = 0, enriched = 0, failed = 0;
            for (Map<String, String> row : rows) {
                Map<String, Object> act = rowToActivity(row);
                if (act == null) {
                    continue;
                }
                parsed++;
                String fname = (String) act.remove("_filename");
                if (fname != null && !fname.isEmpty()) {
                    try {
                        Map<String, Object> extra = parseActivityFile(zf, names, fname);
                        if (extra != null && !extra.isEmpty()) {
                            act.putAll(extra);
                            enriched++;
                        }
                    } catch (Exception e) {   // never let one bad file abort the import
                        failed++;
                        LOG.debugf("Failed to parse %s: %s", fname, e.toString());
                    }
                }
                activities.add(act);
            }
            LOG.infof("Parsed export: %d activities (%d enriched from files, %d file errors).",
                    parsed, enriched, failed);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read the export ZIP: " + e.getMessage(), e);
        }
        return activities;
    }

    // ── activities.csv ────────────────────────────────────────────────────────

    private static Map<String, Object> rowToActivity(Map<String, String> row) {
        String aid = row.get("Activity ID");
        if (aid == null) {
            aid = row.get("Activity Id");
        }
        if (aid == null || aid.strip().isEmpty()) {
            return null;
        }
        long actId;
        try {
            actId = Long.parseLong(aid.strip());
        } catch (NumberFormatException e) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", actId);
        out.put("type", blankToNull(row.get("Activity Type")));
        out.put("name", blankToNull(row.get("Activity Name")));
        out.put("start_date_local", parseDate(row.get("Activity Date")));
        out.put("moving_time", num(row.get("Moving Time")));
        out.put("elapsed_time", num(row.get("Elapsed Time")));
        out.put("distance", num(row.get("Distance")));
        out.put("average_heartrate", num(row.get("Average Heart Rate")));
        out.put("max_heartrate", num(row.get("Max Heart Rate")));
        out.put("average_watts", num(row.get("Average Watts")));
        out.put("average_speed", num(row.get("Average Speed")));
        out.put("total_elevation_gain", num(row.get("Elevation Gain")));
        out.put("_filename", row.get("Filename") == null ? "" : row.get("Filename").strip());
        return out;
    }

    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String s = v.strip();
        return s.isEmpty() ? null : s;
    }

    /** _num: strip, drop thousands commas, empty → null, else the parsed double. */
    static Double num(String v) {
        if (v == null) {
            return null;
        }
        String s = v.strip().replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** _parse_date: try the known formats → ISO; otherwise store the raw string. */
    static String parseDate(String v) {
        if (v == null || v.strip().isEmpty()) {
            return null;
        }
        String s = v.strip();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDateTime.parse(s, fmt).format(ISO);
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return s;   // store as-is; better than dropping the activity
    }

    /** Minimal RFC 4180 CSV → header-keyed row maps (Python csv.DictReader). */
    static List<Map<String, String>> csvDictRows(String text) {
        List<List<String>> rows = parseCsv(text);
        List<Map<String, String>> out = new ArrayList<>();
        if (rows.isEmpty()) {
            return out;
        }
        List<String> header = rows.get(0);
        for (int r = 1; r < rows.size(); r++) {
            List<String> fields = rows.get(r);
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < header.size() && i < fields.size(); i++) {
                m.put(header.get(i), fields.get(i));
            }
            out.add(m);
        }
        return out;
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasData = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                rowHasData = true;
                i++;
            } else if (c == ',') {
                cur.add(field.toString());
                field.setLength(0);
                rowHasData = true;
                i++;
            } else if (c == '\r') {
                i++;
            } else if (c == '\n') {
                cur.add(field.toString());
                field.setLength(0);
                rows.add(cur);
                cur = new ArrayList<>();
                rowHasData = false;
                i++;
            } else {
                field.append(c);
                rowHasData = true;
                i++;
            }
        }
        if (rowHasData || field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            rows.add(cur);
        }
        return rows;
    }

    // ── per-activity file parsing → stream-derived metrics ──────────────────────

    private static Map<String, Object> parseActivityFile(ZipFile zf, Set<String> names, String filename)
            throws Exception {
        String name = filename.startsWith("/") ? filename.substring(1) : filename;
        if (!names.contains(name)) {
            String alt = name.startsWith("activities/") ? name : "activities/" + name;
            if (names.contains(alt)) {
                name = alt;
            } else {
                return null;
            }
        }
        if (zf.getEntry(name).getSize() > MAX_MEMBER_BYTES) {
            LOG.warnf("Skipping %s: member exceeds %d bytes.", name, MAX_MEMBER_BYTES);
            return null;
        }
        byte[] raw = readAll(zf, name);
        if (name.endsWith(".gz")) {
            raw = gunzip(raw);
            if (raw == null) {
                LOG.warnf("Skipping %s: gzip payload exceeds %d bytes.", name, MAX_MEMBER_BYTES);
                return null;
            }
            name = name.substring(0, name.length() - 3);
        }
        String ext = name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        switch (ext) {
            case ".fit":
                return parseFit(raw);
            case ".gpx":
                return parseGpx(raw);
            case ".tcx":
                return parseTcx(raw);
            default:
                return null;
        }
    }

    /** _stream_metrics: averages from the streams (banker's-rounded ints), plus
     * normalized power from the full (null-tolerant) power series. */
    private static Map<String, Object> streamMetrics(List<Double> power, List<Double> hr) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Double> pw = nonNull(power);
        List<Double> hrs = nonNull(hr);
        if (!pw.isEmpty()) {
            out.put("average_watts", Py.roundInt(mean(pw)));
            out.put("device_watts", true);
            Long np = Metrics.normalizedPower(power, 1.0);
            if (np != null) {
                out.put("weighted_average_watts", np);
            }
        }
        if (!hrs.isEmpty()) {
            out.put("average_heartrate", Py.roundInt(mean(hrs)));
            out.put("max_heartrate", Py.roundInt(max(hrs)));
        }
        return out;
    }

    private static Map<String, Object> parseFit(byte[] data) {
        List<Double> power = new ArrayList<>();
        List<Double> hr = new ArrayList<>();
        // session summary: [normalized_power, avg_power, avg_heart_rate, max_heart_rate]
        Integer[] session = new Integer[4];
        Decode decode = new Decode();
        MesgBroadcaster broadcaster = new MesgBroadcaster(decode);
        broadcaster.addListener((RecordMesgListener) m -> {
            Integer p = m.getPower();
            Short h = m.getHeartRate();
            power.add(p == null ? null : p.doubleValue());
            hr.add(h == null ? null : h.doubleValue());
        });
        broadcaster.addListener((SessionMesgListener) m -> {
            if (m.getNormalizedPower() != null) {
                session[0] = m.getNormalizedPower();
            }
            if (m.getAvgPower() != null) {
                session[1] = m.getAvgPower();
            }
            if (m.getAvgHeartRate() != null) {
                session[2] = m.getAvgHeartRate().intValue();
            }
            if (m.getMaxHeartRate() != null) {
                session[3] = m.getMaxHeartRate().intValue();
            }
        });
        broadcaster.run(new ByteArrayInputStream(data));

        Map<String, Object> out = streamMetrics(power, hr);
        // FIT session messages carry authoritative summaries — prefer them (Python
        // uses truthiness, so a 0 summary field is ignored).
        if (truthy(session[0])) {
            out.put("weighted_average_watts", (long) (int) session[0]);
            out.put("device_watts", true);
        }
        if (truthy(session[1]) && !out.containsKey("average_watts")) {
            out.put("average_watts", (long) (int) session[1]);
        }
        if (truthy(session[2])) {
            out.put("average_heartrate", (long) (int) session[2]);
        }
        if (truthy(session[3])) {
            out.put("max_heartrate", (long) (int) session[3]);
        }
        return out;
    }

    private static Map<String, Object> parseGpx(byte[] data) throws Exception {
        List<Double> power = new ArrayList<>();
        List<Double> hr = new ArrayList<>();
        XMLStreamReader r = xmlReader(data);
        StringBuilder text = new StringBuilder();
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    text.setLength(0);
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName().toLowerCase(Locale.ROOT);
                    Double val = num(text.toString());
                    if (val != null) {
                        if (name.equals("power") || name.equals("watts")) {
                            power.add(val);
                        } else if (name.equals("hr") || name.equals("heartrate")) {
                            hr.add(val);
                        }
                    }
                    text.setLength(0);
                }
            }
        } finally {
            r.close();
        }
        return streamMetrics(power, hr);
    }

    private static Map<String, Object> parseTcx(byte[] data) throws Exception {
        List<Double> power = new ArrayList<>();
        List<Double> hr = new ArrayList<>();
        XMLStreamReader r = xmlReader(data);
        Deque<String> stack = new ArrayDeque<>();
        StringBuilder text = new StringBuilder();
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    stack.push(r.getLocalName());
                    text.setLength(0);
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = stack.pop();
                    String parent = stack.peek();
                    if (stack.contains("Trackpoint")) {
                        if (name.equals("Watts")) {
                            Double v = num(text.toString());
                            if (v != null) {
                                power.add(v);
                            }
                        } else if (name.equals("Value") && "HeartRateBpm".equals(parent)) {
                            Double v = num(text.toString());
                            if (v != null) {
                                hr.add(v);
                            }
                        }
                    }
                    text.setLength(0);
                }
            }
        } finally {
            r.close();
        }
        return streamMetrics(power, hr);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static XMLStreamReader xmlReader(byte[] data) throws Exception {
        XMLInputFactory f = XMLInputFactory.newInstance();
        // XXE-safe + native-friendly: no DTDs, no external entities.
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f.createXMLStreamReader(new ByteArrayInputStream(data));
    }

    private static byte[] readAll(ZipFile zf, String name) throws Exception {
        try (InputStream in = zf.getInputStream(zf.getEntry(name))) {
            return in.readAllBytes();
        }
    }

    /** gunzip with the same bomb guard as the members — null when it overflows. */
    private static byte[] gunzip(byte[] raw) throws Exception {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            byte[] out = gz.readNBytes((int) MAX_MEMBER_BYTES + 1);
            return out.length > MAX_MEMBER_BYTES ? null : out;
        }
    }

    private static List<Double> nonNull(List<Double> in) {
        List<Double> out = new ArrayList<>();
        for (Double d : in) {
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    private static double mean(List<Double> xs) {
        double sum = 0;
        for (double x : xs) {
            sum += x;
        }
        return sum / xs.size();
    }

    private static double max(List<Double> xs) {
        double m = xs.get(0);
        for (double x : xs) {
            if (x > m) {
                m = x;
            }
        }
        return m;
    }

    private static boolean truthy(Integer v) {
        return v != null && v != 0;
    }
}
