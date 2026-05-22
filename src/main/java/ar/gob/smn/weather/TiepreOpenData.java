package ar.gob.smn.weather;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMN open-data text {@code observaciones/tiepreYYYYMMDD.txt} (ssl.smn.gob.ar {@code descarga_opendata.php}): semicolon
 * rows with current conditions for many stations — used for {@code /current} before georef/ws1.
 */
final class TiepreOpenData {

    private static final Logger LOG = Logger.getLogger(TiepreOpenData.class.getName());
    private static final ZoneId ART = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");

    private static final DateTimeFormatter TIEPRE_DATE =
            new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern("dd-MMMM-uuuu")
                    .toFormatter(ES_AR);
    private static final DateTimeFormatter TIEPRE_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final Pattern VIS_KM = Pattern.compile("([\\d.,]+)\\s*km", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIS_MTS = Pattern.compile("([\\d.,]+)\\s*mts", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRESS_NUM = Pattern.compile("([\\d.,]+)");

    private TiepreOpenData() {}

    /**
     * Parses the file body, ranks rows by {@code query}, returns observations (same shape as ws1) for formatting.
     */
    static List<SmnClient.Observation> matchQuery(String query, String fileBody) {
        String qRaw = query != null ? query.trim() : "";
        if (qRaw.isEmpty() || fileBody == null || fileBody.isBlank()) {
            return List.of();
        }
        String q = normalizeForMatch(expandQueryAlias(qRaw));
        if (q.isEmpty()) {
            return List.of();
        }
        List<Row> rows = parseRows(fileBody);
        List<Scored> scored = new ArrayList<>();
        for (Row r : rows) {
            int score = matchScore(q, r.stationName);
            if (score >= 0) {
                scored.add(new Scored(score, r));
            }
        }
        scored.sort(
                Comparator.comparingInt((Scored s) -> s.score)
                        .thenComparing(s -> s.row.stationName, String.CASE_INSENSITIVE_ORDER));

        List<SmnClient.Observation> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= CurrentConditionsQuery.MAX_CURRENT_MATCHES) {
                break;
            }
            try {
                out.add(toObservation(s.row));
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "tiepre row skip: " + s.row.stationName, e);
            }
        }
        return out;
    }

    /**
     * Every tiepre row that parses into an {@link SmnClient.Observation} (same source as {@code /current} before georef).
     */
    static List<SmnClient.Observation> allObservations(String fileBody) {
        if (fileBody == null || fileBody.isBlank()) {
            return List.of();
        }
        List<SmnClient.Observation> out = new ArrayList<>();
        for (Row r : parseRows(fileBody)) {
            try {
                out.add(toObservation(r));
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "tiepre row skip (allObservations): " + r.stationName, e);
            }
        }
        return out;
    }

    /** Maps common nicknames to substrings that appear in tiepre station names. */
    private static String expandQueryAlias(String q) {
        String s = q.trim();
        String lower = s.toLowerCase(ES_AR);
        if (lower.equals("caba") || lower.equals("capital") || lower.equals("capital federal")) {
            return "Buenos Aires";
        }
        if (lower.equals("aep")) {
            return "Aeroparque";
        }
        return s;
    }

    /**
     * Same folding as {@code /current} tiepre name matching: trim, Unicode NFD, strip combining marks, then
     * {@link Locale#forLanguageTag(String) es-AR} lower case. Compare two results for equality regardless of NFC vs NFD
     * or accented vs plain ASCII spellings where marks differ only by composition.
     */
    static String stationIdentityKey(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(ES_AR);
    }

    private static String normalizeForMatch(String s) {
        return stationIdentityKey(s);
    }

    /**
     * Lower score = better. {@code -1} = no match.
     */
    private static int matchScore(String qNorm, String stationName) {
        String station = normalizeForMatch(stationName);
        if (station.isEmpty()) {
            return -1;
        }
        if (station.equals(qNorm)) {
            return 0;
        }
        if (station.startsWith(qNorm) || qNorm.startsWith(station)) {
            return 1;
        }
        if (station.contains(qNorm)) {
            return 2;
        }
        return -1;
    }

    private static List<Row> parseRows(String fileBody) {
        List<Row> out = new ArrayList<>();
        String stripped = fileBody.startsWith("\uFEFF") ? fileBody.substring(1) : fileBody;
        for (String line : stripped.split("\\R")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split(";", -1);
            if (p.length < 10) {
                continue;
            }
            for (int i = 0; i < p.length; i++) {
                p[i] = p[i].trim();
            }
            out.add(
                    new Row(
                            p[0],
                            p[1],
                            p[2],
                            p[3],
                            p[4],
                            p[5],
                            p[6],
                            p[7],
                            p[8],
                            p[9]));
        }
        return out;
    }

    private static SmnClient.Observation toObservation(Row r) {
        ZonedDateTime when = parseObservationTime(r.dateRaw, r.timeRaw);
        Double temp = parseDoubleLoose(r.tempRaw);
        Double feels = parseFeelsLike(r.feelsRaw);
        Double hum = parseDoubleLoose(r.humidityRaw);
        Double vis = parseVisibilityKm(r.visibilityRaw);
        Double press = parsePressureHpa(r.pressureRaw);
        Wind w = parseWind(r.windRaw);
        return new SmnClient.Observation(
                r.stationName,
                when,
                temp,
                feels,
                hum,
                press,
                vis,
                w.direction,
                w.speed,
                r.descriptionRaw.isEmpty() ? "—" : r.descriptionRaw,
                null);
    }

    private static ZonedDateTime parseObservationTime(String dateRaw, String timeRaw) {
        try {
            LocalDate d = LocalDate.parse(dateRaw, TIEPRE_DATE);
            LocalTime t = LocalTime.parse(timeRaw, TIEPRE_TIME);
            return ZonedDateTime.of(d, t, ART);
        } catch (DateTimeParseException e) {
            LOG.log(Level.FINE, "tiepre date/time parse, using now: " + dateRaw + " " + timeRaw, e);
            return ZonedDateTime.now(ART);
        }
    }

    private static Double parseFeelsLike(String feelsRaw) {
        if (feelsRaw == null || feelsRaw.isBlank()) {
            return null;
        }
        if (feelsRaw.toLowerCase(ES_AR).contains("calcula")) {
            return null;
        }
        return parseDoubleLoose(feelsRaw);
    }

    private static final Pattern LEADING_SIGNED_NUMBER =
            Pattern.compile("^\\s*(-?[0-9]+(?:[.,][0-9]+)?)");

    private static Double parseDoubleLoose(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim().replace('\u2212', '-').replace('\u2013', '-').replace(',', '.');
        if (t.equals("-") || t.equals("/")) {
            return null;
        }
        Matcher lead = LEADING_SIGNED_NUMBER.matcher(t);
        if (lead.find()) {
            try {
                return Double.parseDouble(lead.group(1).replace(',', '.'));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseVisibilityKm(String visibilityRaw) {
        if (visibilityRaw == null || visibilityRaw.isBlank()) {
            return null;
        }
        String v = visibilityRaw.trim();
        Matcher mk = VIS_KM.matcher(v);
        if (mk.find()) {
            return parseDoubleLoose(mk.group(1));
        }
        Matcher mm = VIS_MTS.matcher(v);
        if (mm.find()) {
            Double mts = parseDoubleLoose(mm.group(1));
            return mts != null ? mts / 1000.0 : null;
        }
        return null;
    }

    private static Double parsePressureHpa(String pressureRaw) {
        if (pressureRaw == null || pressureRaw.isBlank()) {
            return null;
        }
        Matcher m = PRESS_NUM.matcher(pressureRaw);
        if (m.find()) {
            return parseDoubleLoose(m.group(1));
        }
        return null;
    }

    private static final Pattern WIND_NUMBER = Pattern.compile("(-?\\d+(?:[.,]\\d+)?)");

    /**
     * Tiepre wind text (e.g. {@code Oeste  55}, {@code Sur  3}, {@code Calma}). Uses the <b>largest</b> numeric literal
     * in the field as km/h (handles {@code 25/40} gust-style tokens). Direction is the text before that number (SMN
     * convention: cardinal names then speed at the end).
     */
    private static Wind parseWind(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Wind(null, null);
        }
        String w = raw.trim();
        if (w.equalsIgnoreCase("Calma")) {
            return new Wind("Calma", null);
        }
        Matcher m = WIND_NUMBER.matcher(w);
        double best = Double.NaN;
        int bestStart = -1;
        int bestEnd = -1;
        while (m.find()) {
            Double v = parseDoubleLoose(m.group(1));
            if (v == null || Double.isNaN(v)) {
                continue;
            }
            if (Double.isNaN(best)
                    || v > best
                    || (v == best && m.start(1) > bestStart)) {
                best = v;
                bestStart = m.start(1);
                bestEnd = m.end(1);
            }
        }
        if (Double.isNaN(best)) {
            return new Wind(w, null);
        }
        String dir = w.substring(0, bestStart).trim();
        if (dir.isEmpty()) {
            dir = null;
        }
        return new Wind(dir, best);
    }

    /**
     * Tag id for {@link MailBodyFormatter#formatCurrentConditionsPlain}: {@code -1} = datos abiertos sin id SMN
     * conocido.
     */
    static int tiepreStationToTagId(String stationName) {
        if (stationName == null) {
            return -1;
        }
        String s = stationName.trim();
        if (s.equalsIgnoreCase("Buenos Aires")) {
            return 4864;
        }
        if (s.equalsIgnoreCase("Aeroparque Buenos Aires")) {
            return 10821;
        }
        return -1;
    }

    private static final class Row {
        final String stationName;
        final String dateRaw;
        final String timeRaw;
        final String descriptionRaw;
        final String visibilityRaw;
        final String tempRaw;
        final String feelsRaw;
        final String humidityRaw;
        final String windRaw;
        final String pressureRaw;

        Row(
                String stationName,
                String dateRaw,
                String timeRaw,
                String descriptionRaw,
                String visibilityRaw,
                String tempRaw,
                String feelsRaw,
                String humidityRaw,
                String windRaw,
                String pressureRaw) {
            this.stationName = stationName;
            this.dateRaw = dateRaw;
            this.timeRaw = timeRaw;
            this.descriptionRaw = descriptionRaw;
            this.visibilityRaw = visibilityRaw;
            this.tempRaw = tempRaw;
            this.feelsRaw = feelsRaw;
            this.humidityRaw = humidityRaw;
            this.windRaw = windRaw;
            this.pressureRaw = pressureRaw;
        }
    }

    private static final class Scored {
        final int score;
        final Row row;

        Scored(int score, Row row) {
            this.score = score;
            this.row = row;
        }
    }

    private static final class Wind {
        final String direction;
        final Double speed;

        Wind(String direction, Double speed) {
            this.direction = direction;
            this.speed = speed;
        }
    }
}
