package ar.gob.smn.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Append-only log of per-day forecast stats extracted from SMN JSON each time we store a bulletin (email or
 * Telegram). Used to find the <em>earliest</em> snapshot where a calendar day appeared in the API response.
 */
final class ForecastDaySnapshotLog {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ISO_TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final String[] PERIOD_KEYS =
            {"early_morning", "morning", "afternoon", "night"};
    private static final char SEP = '|';

    private final Path file;

    ForecastDaySnapshotLog(Path file) {
        this.file = file;
    }

    /**
     * Writes one line per day row in {@code forecastJson} for the given location (typically CABA).
     */
    void append(ZonedDateTime writtenArt, int locationId, String forecastJson, String smnUpdated)
            throws IOException {
        if (forecastJson == null || forecastJson.isBlank()) {
            return;
        }
        List<DayLine> lines = extractDays(forecastJson);
        if (lines.isEmpty()) {
            return;
        }
        String written = ISO_TS.format(writtenArt);
        String upd = smnUpdated != null ? smnUpdated.replace(SEP, ' ') : "";
        StringBuilder chunk = new StringBuilder();
        for (DayLine d : lines) {
            chunk.append(written)
                    .append(SEP)
                    .append(locationId)
                    .append(SEP)
                    .append(d.day)
                    .append(SEP)
                    .append(d.tempMin != null ? d.tempMin : "")
                    .append(SEP)
                    .append(d.tempMax != null ? d.tempMax : "")
                    .append(SEP)
                    .append(d.maxRainUpper)
                    .append(SEP)
                    .append(upd)
                    .append('\n');
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                file,
                chunk.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /**
     * Earliest snapshot line for {@code targetDay} and {@code locationId}, by {@code writtenArt}.
     */
    Optional<SnapshotRow> findFirstSnapshot(LocalDate targetDay, int locationId) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        List<SnapshotRow> matches = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Optional<SnapshotRow> row = parseLine(line);
            if (row.isEmpty()) {
                continue;
            }
            SnapshotRow r = row.get();
            if (r.locationId == locationId && r.targetDay.equals(targetDay)) {
                matches.add(r);
            }
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        matches.sort(Comparator.comparing(SnapshotRow::writtenArt));
        return Optional.of(matches.get(0));
    }

    private static Optional<SnapshotRow> parseLine(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String[] p = line.split("\\|", -1);
        if (p.length < 7) {
            return Optional.empty();
        }
        try {
            ZonedDateTime written = ZonedDateTime.parse(p[0].trim(), ISO_TS);
            int loc = Integer.parseInt(p[1].trim());
            LocalDate day = LocalDate.parse(p[2].trim());
            Double tmin = p[3].isBlank() ? null : Double.parseDouble(p[3].trim().replace(',', '.'));
            Double tmax = p[4].isBlank() ? null : Double.parseDouble(p[4].trim().replace(',', '.'));
            int maxRain = Integer.parseInt(p[5].trim());
            String upd = p[6].trim();
            return Optional.of(new SnapshotRow(written, loc, day, tmin, tmax, maxRain, upd));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<DayLine> extractDays(String jsonBody) throws IOException {
        JsonNode root = JSON.readTree(jsonBody);
        JsonNode days = root.path("forecast");
        if (!days.isArray() || days.isEmpty()) {
            return Collections.emptyList();
        }
        List<DayLine> out = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            JsonNode day = days.get(i);
            LocalDate d = parseForecastLocalDate(day.path("date").asText(""));
            if (d == null) {
                continue;
            }
            Double tmin = num(day, "temp_min");
            Double tmax = num(day, "temp_max");
            int maxRain = 0;
            for (String pk : PERIOD_KEYS) {
                JsonNode block = day.path(pk);
                if (block.isMissingNode() || block.isNull()) {
                    continue;
                }
                JsonNode rainArr = block.path("rain_prob_range");
                if (rainArr.isArray() && rainArr.size() >= 2) {
                    maxRain = Math.max(maxRain, rainArr.get(1).asInt());
                }
            }
            out.add(new DayLine(d, tmin, tmax, maxRain));
        }
        return out;
    }

    private static LocalDate parseForecastLocalDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        String core = dateStr.length() >= 10 ? dateStr.substring(0, 10) : dateStr;
        try {
            return LocalDate.parse(core);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        return v.asDouble();
    }

    private static final class DayLine {
        final LocalDate day;
        final Double tempMin;
        final Double tempMax;
        final int maxRainUpper;

        DayLine(LocalDate day, Double tempMin, Double tempMax, int maxRainUpper) {
            this.day = day;
            this.tempMin = tempMin;
            this.tempMax = tempMax;
            this.maxRainUpper = maxRainUpper;
        }
    }

    static final class SnapshotRow {
        private final ZonedDateTime writtenArt;
        private final int locationId;
        private final LocalDate targetDay;
        private final Double tempMin;
        private final Double tempMax;
        private final int maxRainUpper;
        private final String smnUpdated;

        SnapshotRow(
                ZonedDateTime writtenArt,
                int locationId,
                LocalDate targetDay,
                Double tempMin,
                Double tempMax,
                int maxRainUpper,
                String smnUpdated) {
            this.writtenArt = writtenArt;
            this.locationId = locationId;
            this.targetDay = targetDay;
            this.tempMin = tempMin;
            this.tempMax = tempMax;
            this.maxRainUpper = maxRainUpper;
            this.smnUpdated = smnUpdated;
        }

        ZonedDateTime writtenArt() {
            return writtenArt;
        }

        Double tempMin() {
            return tempMin;
        }

        Double tempMax() {
            return tempMax;
        }

        int maxRainUpper() {
            return maxRainUpper;
        }

        String smnUpdated() {
            return smnUpdated;
        }
    }

    static String fmtTemp(Double v) {
        if (v == null || Double.isNaN(v)) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.1f °C", v);
    }
}
