package ar.gob.smn.weather;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * From SMN open-data tiepre text (same file as {@code /current}): global max/min temperature and strongest numeric wind
 * among stations <b>except</b> those named in {@link Config#tiepreExtremaExcludeStations()}; {@link Snapshot} carries
 * the last tiepre row for each configured name (when present) for display only. Telegram + HTML email bodies and a
 * stable fingerprint for dedupe.
 */
final class TiepreExtremaHourly {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm z", ES_AR);

    /** Extreme observations older than this relative to the message time are ignored when ranking. */
    static final Duration EXTREMA_MAX_AGE = Duration.ofHours(3);

    private TiepreExtremaHourly() {}

    static final class Snapshot {
        final SmnClient.Observation maxTemp;
        final SmnClient.Observation minTemp;
        final SmnClient.Observation maxWind;
        /**
         * Station names from config ({@code tiepre.extrema.exclude.stations}), deduped by
         * {@link TiepreOpenData#stationIdentityKey(String)}, order preserved.
         */
        final List<String> excludedStationNames;
        /**
         * Parallel to {@link #excludedStationNames}: last tiepre row matching that name, or {@code null} if absent in
         * the file.
         */
        final List<SmnClient.Observation> excludedRows;

        Snapshot(
                SmnClient.Observation maxTemp,
                SmnClient.Observation minTemp,
                SmnClient.Observation maxWind,
                List<String> excludedStationNames,
                List<SmnClient.Observation> excludedRows) {
            if (excludedStationNames.size() != excludedRows.size()) {
                throw new IllegalArgumentException("excludedStationNames and excludedRows size mismatch");
            }
            this.maxTemp = maxTemp;
            this.minTemp = minTemp;
            this.maxWind = maxWind;
            this.excludedStationNames = List.copyOf(excludedStationNames);
            this.excludedRows = List.copyOf(excludedRows);
        }
    }

    /** Dedupe by {@link TiepreOpenData#stationIdentityKey(String)}; preserve first occurrence order. */
    static List<String> dedupeExcludedStationNames(List<String> names) {
        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String n = raw.trim();
            String key = TiepreOpenData.stationIdentityKey(n);
            if (key.isEmpty()) {
                continue;
            }
            if (seenKeys.add(key)) {
                out.add(n);
            }
        }
        return List.copyOf(out);
    }

    private static boolean isStaleForExtrema(SmnClient.Observation o, Instant cutoff) {
        if (o.observationTime() == null) {
            return true;
        }
        return o.observationTime().toInstant().isBefore(cutoff);
    }

    private static boolean isExcludedFromRanking(SmnClient.Observation o, Set<String> excludeKeys) {
        if (o.stationName() == null || excludeKeys.isEmpty()) {
            return false;
        }
        String k = TiepreOpenData.stationIdentityKey(o.stationName());
        return !k.isEmpty() && excludeKeys.contains(k);
    }

    /** Last occurrence wins if the file lists the station more than once (Unicode + encoding-tolerant match). */
    private static SmnClient.Observation findLastByStationName(
            List<SmnClient.Observation> observations, String canonicalStationName) {
        String want = TiepreOpenData.stationIdentityKey(canonicalStationName);
        if (want.isEmpty()) {
            return null;
        }
        SmnClient.Observation found = null;
        for (SmnClient.Observation o : observations) {
            if (o.stationName() != null && TiepreOpenData.stationIdentityKey(o.stationName()).equals(want)) {
                found = o;
            }
        }
        return found;
    }

    /**
     * @param now message reference time; observations older than {@link #EXTREMA_MAX_AGE} relative to it are not
     *     eligible as extremes (excluded-station display rows are unaffected).
     */
    static Optional<Snapshot> compute(
            List<SmnClient.Observation> observations, List<String> excludeStationNames, Instant now) {
        if (observations.isEmpty()) {
            return Optional.empty();
        }
        List<String> excludedNames = dedupeExcludedStationNames(excludeStationNames);
        Set<String> excludeKeys = new HashSet<>();
        for (String n : excludedNames) {
            excludeKeys.add(TiepreOpenData.stationIdentityKey(n));
        }

        List<SmnClient.Observation> excludedRows = new ArrayList<>(excludedNames.size());
        for (String name : excludedNames) {
            excludedRows.add(findLastByStationName(observations, name));
        }

        Instant cutoff = now.minus(EXTREMA_MAX_AGE);

        List<SmnClient.Observation> withTemp = new ArrayList<>();
        for (SmnClient.Observation o : observations) {
            if (isExcludedFromRanking(o, excludeKeys)) {
                continue;
            }
            if (isStaleForExtrema(o, cutoff)) {
                continue;
            }
            if (o.temperature() != null && !Double.isNaN(o.temperature())) {
                withTemp.add(o);
            }
        }
        if (withTemp.isEmpty()) {
            return Optional.empty();
        }
        SmnClient.Observation maxTemp = withTemp.get(0);
        SmnClient.Observation minTemp = withTemp.get(0);
        for (int i = 1; i < withTemp.size(); i++) {
            SmnClient.Observation o = withTemp.get(i);
            double t = o.temperature();
            double maxV = maxTemp.temperature();
            double minV = minTemp.temperature();
            int cmpMax = Double.compare(t, maxV);
            if (cmpMax > 0 || (cmpMax == 0 && o.stationName().compareToIgnoreCase(maxTemp.stationName()) < 0)) {
                maxTemp = o;
            }
            int cmpMin = Double.compare(t, minV);
            if (cmpMin < 0 || (cmpMin == 0 && o.stationName().compareToIgnoreCase(minTemp.stationName()) < 0)) {
                minTemp = o;
            }
        }

        List<SmnClient.Observation> withWind = new ArrayList<>();
        for (SmnClient.Observation o : observations) {
            if (isExcludedFromRanking(o, excludeKeys)) {
                continue;
            }
            if (isStaleForExtrema(o, cutoff)) {
                continue;
            }
            if (o.windSpeed() != null && !Double.isNaN(o.windSpeed())) {
                withWind.add(o);
            }
        }
        SmnClient.Observation maxWind = null;
        for (SmnClient.Observation o : withWind) {
            if (maxWind == null) {
                maxWind = o;
                continue;
            }
            double ws = o.windSpeed();
            double best = maxWind.windSpeed();
            int cmp = Double.compare(ws, best);
            if (cmp > 0 || (cmp == 0 && o.stationName().compareToIgnoreCase(maxWind.stationName()) < 0)) {
                maxWind = o;
            }
        }
        return Optional.of(new Snapshot(maxTemp, minTemp, maxWind, excludedNames, excludedRows));
    }

    static String fingerprint(Snapshot s) {
        StringBuilder raw = new StringBuilder();
        raw.append(keyPart(s.maxTemp))
                .append('\u0001')
                .append(keyPart(s.minTemp))
                .append('\u0001')
                .append(s.maxWind == null ? "-" : keyPartWind(s.maxWind));
        for (int i = 0; i < s.excludedRows.size(); i++) {
            SmnClient.Observation ex = s.excludedRows.get(i);
            raw.append('\u0001').append(ex == null ? "-" : keyExcludedFull(ex));
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder()
                    .encodeToString(md.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String keyPart(SmnClient.Observation o) {
        return o.stationName()
                + "\u0002"
                + o.observationTime().toInstant().toEpochMilli()
                + "\u0002"
                + String.format(Locale.ROOT, "%.2f", o.temperature());
    }

    private static String keyPartWind(SmnClient.Observation o) {
        String dir = o.windDirection() != null ? o.windDirection() : "";
        return o.stationName()
                + "\u0002"
                + o.observationTime().toInstant().toEpochMilli()
                + "\u0002"
                + String.format(Locale.ROOT, "%.2f", o.windSpeed())
                + "\u0002"
                + dir;
    }

    private static String keyExcludedFull(SmnClient.Observation o) {
        String t =
                o.temperature() != null && !Double.isNaN(o.temperature())
                        ? String.format(Locale.ROOT, "%.2f", o.temperature())
                        : "-";
        String w =
                o.windSpeed() != null && !Double.isNaN(o.windSpeed())
                        ? String.format(Locale.ROOT, "%.2f", o.windSpeed())
                        : "-";
        String dir = o.windDirection() != null ? o.windDirection() : "";
        return o.stationName()
                + "\u0002"
                + o.observationTime().toInstant().toEpochMilli()
                + "\u0002"
                + t
                + "\u0002"
                + w
                + "\u0002"
                + dir;
    }

    /**
     * @param listExcludedInMessage when {@code false}, excluded stations are not repeated in the body (ranking still
     *     omits them; fingerprint unchanged).
     */
    static String formatTelegramHtml(Snapshot s, ZoneId zone, String reportHost, boolean listExcludedInMessage) {
        String maxLine = fmtTempLine("Máx", s.maxTemp, zone);
        String minLine = fmtTempLine("Mín", s.minTemp, zone);
        String windLine = fmtWindLine(s, zone);
        StringBuilder sb = new StringBuilder();
        sb.append("<b>Datos extremos horarios</b>\n")
                .append(esc(maxLine))
                .append("\n")
                .append(esc(minLine))
                .append("\n")
                .append(esc(windLine));
        if (listExcludedInMessage) {
            for (SmnClient.Observation row : s.excludedRows) {
                appendExcludedTelegram(sb, row, zone);
            }
        }
        String host = reportHost == null || reportHost.isBlank() ? "—" : reportHost;
        sb.append("\n\n(").append(esc(host)).append(")");
        return sb.toString();
    }

    private static void appendExcludedTelegram(StringBuilder sb, SmnClient.Observation row, ZoneId zone) {
        if (row == null) {
            return;
        }
        sb.append("\n\n<b>")
                .append(esc(row.stationName()))
                .append("</b> (excluida del ranking)\n")
                .append(esc(fmtExcludedLines(row, zone)));
    }

    private static String fmtExcludedLines(SmnClient.Observation m, ZoneId zone) {
        StringBuilder b = new StringBuilder();
        if (m.temperature() != null && !Double.isNaN(m.temperature())) {
            b.append(fmtTempLine("Temp.", m, zone));
        }
        if (m.windSpeed() != null && !Double.isNaN(m.windSpeed())) {
            if (b.length() > 0) {
                b.append("\n");
            }
            String dir = m.windDirection() != null && !m.windDirection().isBlank() ? m.windDirection() + " " : "";
            b.append("Viento ")
                    .append(dir)
                    .append(String.format(Locale.ROOT, "%.1f km/h", m.windSpeed()))
                    .append(" — ")
                    .append(m.observationTime().withZoneSameInstant(zone).format(WHEN));
        }
        if (b.length() == 0) {
            b.append("(sin temperatura ni viento numérico en tiepre)");
        }
        return b.toString();
    }

    private static String fmtTempLine(String label, SmnClient.Observation o, ZoneId zone) {
        return label
                + " "
                + String.format(Locale.ROOT, "%.1f °C", o.temperature())
                + " — "
                + o.stationName()
                + " — "
                + o.observationTime().withZoneSameInstant(zone).format(WHEN);
    }

    private static String fmtWindLine(Snapshot s, ZoneId zone) {
        if (s.maxWind == null) {
            return "Viento máx: sin magnitud numérica en tiepre (todas las filas sin km/h numérico)";
        }
        SmnClient.Observation w = s.maxWind;
        String dir = w.windDirection() != null && !w.windDirection().isBlank() ? w.windDirection() + " " : "";
        return "Viento máx "
                + dir
                + String.format(Locale.ROOT, "%.1f km/h", w.windSpeed())
                + " — "
                + w.stationName()
                + " — "
                + w.observationTime().withZoneSameInstant(zone).format(WHEN);
    }

    /**
     * @param listExcludedInMessage when {@code false}, no email copy for excluded station names/rows (see
     *     {@link #formatTelegramHtml}).
     */
    static String formatEmailHtml(
            Snapshot s, ZoneId zone, String reportHost, boolean listExcludedInMessage) {
        String h = esc(reportHost);
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Datos extremos horarios</h2>\n");
        if (listExcludedInMessage && !s.excludedStationNames.isEmpty()) {
            sb.append("<p><b>Excluidas del ranking</b> (resto del país arriba; valores propios más abajo): ")
                    .append(esc(String.join(", ", s.excludedStationNames)))
                    .append(".</p>\n");
        }
        sb.append("<p>Generado en referencia horaria: ").append(esc(zone.getId())).append(".</p>\n");
        sb.append("<ul>\n");
        sb.append("<li><b>Temperatura máxima</b> (resto del país)<br>")
                .append(rowDetails(s.maxTemp, zone, true))
                .append("</li>\n");
        sb.append("<li><b>Temperatura mínima</b> (resto del país)<br>")
                .append(rowDetails(s.minTemp, zone, true))
                .append("</li>\n");
        sb.append("<li><b>Viento (máx. intensidad numérica)</b> (resto del país)<br>")
                .append(s.maxWind == null ? esc(fmtWindLine(s, zone)) : rowDetails(s.maxWind, zone, false))
                .append("</li>\n");
        if (listExcludedInMessage) {
            for (SmnClient.Observation row : s.excludedRows) {
                appendExcludedEmailLi(sb, row, zone);
            }
        }
        sb.append("</ul>\n");
        sb.append("<p style=\"color:#666;font-size:90%\">(").append(h).append(")</p>\n");
        return sb.toString();
    }

    private static void appendExcludedEmailLi(StringBuilder sb, SmnClient.Observation row, ZoneId zone) {
        if (row == null) {
            return;
        }
        sb.append("<li><b>")
                .append(esc(row.stationName()))
                .append("</b> (excluida del cálculo de extremos; valores propios)<br>")
                .append(excludedEmailBlock(row, zone))
                .append("</li>\n");
    }

    private static String excludedEmailBlock(SmnClient.Observation o, ZoneId zone) {
        boolean haveT = o.temperature() != null && !Double.isNaN(o.temperature());
        boolean haveW = o.windSpeed() != null && !Double.isNaN(o.windSpeed());
        if (!haveT && !haveW) {
            return esc("(sin temperatura ni viento numérico en tiepre)");
        }
        ZonedDateTime z = o.observationTime().withZoneSameInstant(zone);
        StringBuilder sb = new StringBuilder();
        sb.append("Lugar: ").append(esc(o.stationName())).append("<br>");
        sb.append("Hora observación: ").append(esc(z.format(WHEN))).append("<br>");
        if (haveT) {
            sb.append("Temperatura: ")
                    .append(esc(String.format(Locale.ROOT, "%.1f °C", o.temperature())))
                    .append("<br>");
        }
        if (haveW) {
            String dir = o.windDirection() != null ? o.windDirection() : "—";
            sb.append("Dirección viento: ").append(esc(dir)).append("<br>");
            sb.append("Velocidad: ")
                    .append(esc(String.format(Locale.ROOT, "%.1f km/h", o.windSpeed())))
                    .append("<br>");
        }
        return sb.toString();
    }

    private static String rowDetails(SmnClient.Observation o, ZoneId zone, boolean temp) {
        ZonedDateTime z = o.observationTime().withZoneSameInstant(zone);
        StringBuilder sb = new StringBuilder();
        sb.append("Lugar: ").append(esc(o.stationName())).append("<br>");
        sb.append("Hora observación: ").append(esc(z.format(WHEN))).append("<br>");
        if (temp) {
            sb.append("Temperatura: ")
                    .append(esc(String.format(Locale.ROOT, "%.1f °C", o.temperature())))
                    .append("<br>");
        } else {
            String dir = o.windDirection() != null ? o.windDirection() : "—";
            sb.append("Dirección: ").append(esc(dir)).append("<br>");
            sb.append("Velocidad: ")
                    .append(esc(String.format(Locale.ROOT, "%.1f km/h", o.windSpeed())))
                    .append("<br>");
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
