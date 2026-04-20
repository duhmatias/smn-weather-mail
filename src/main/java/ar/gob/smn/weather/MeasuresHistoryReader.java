package ar.gob.smn.weather;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@link MeasuresDailyLog} day files and parses fixed-width rows (same layout as written by
 * {@link MeasuresDailyLog#append}).
 */
final class MeasuresHistoryReader {

    private static final DateTimeFormatter DAY_FILE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT);

    /** Column widths and gaps — must match {@link MeasuresDailyLog}. */
    private static final int W_TIME = 28;
    private static final int GAP = 2;
    private static final int W_LOC = 6;
    private static final int W_STATION = 36;
    private static final int W_TEMP = 7;
    private static final int W_FEEL = 7;
    private static final int W_HUM = 6;
    private static final int W_PRES = 8;
    private static final int W_VIS = 6;
    private static final int W_DIR = 12;
    private static final int W_SPD = 6;
    private static final int W_COND = 28;

    private static final int I0_TIME = 0;
    private static final int I0_LOC = I0_TIME + W_TIME + GAP;
    private static final int I0_STATION = I0_LOC + W_LOC + GAP;
    private static final int I0_TEMP = I0_STATION + W_STATION + GAP;
    private static final int I0_FEEL = I0_TEMP + W_TEMP + GAP;
    private static final int I0_HUM = I0_FEEL + W_FEEL + GAP;
    private static final int I0_PRES = I0_HUM + W_HUM + GAP;
    private static final int I0_VIS = I0_PRES + W_PRES + GAP;
    private static final int I0_DIR = I0_VIS + W_VIS + GAP;
    private static final int I0_SPD = I0_DIR + W_DIR + GAP;
    private static final int I0_COND = I0_SPD + W_SPD + GAP;

    private MeasuresHistoryReader() {}

    static List<MeasureRow> readDay(Path baseDir, LocalDate day, int locationId) throws IOException {
        Path file = dayFile(baseDir, day);
        if (!Files.isRegularFile(file)) {
            return Collections.emptyList();
        }
        List<MeasureRow> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            parseLine(line, day, locationId).ifPresent(out::add);
        }
        return out;
    }

    static List<MeasureRow> readInclusive(Path baseDir, LocalDate start, LocalDate end, int locationId)
            throws IOException {
        List<MeasureRow> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            out.addAll(readDay(baseDir, d, locationId));
        }
        return out;
    }

    static Path dayFile(Path baseDir, LocalDate day) {
        return baseDir
                .resolve(String.format(Locale.ROOT, "%04d", day.getYear()))
                .resolve(String.format(Locale.ROOT, "%02d", day.getMonthValue()))
                .resolve(day.format(DAY_FILE) + ".txt");
    }

    static Optional<MeasureRow> parseLine(String line, LocalDate fileDay, int filterLocationId) {
        if (line == null || line.length() < I0_COND + W_COND) {
            return Optional.empty();
        }
        if (!Character.isDigit(line.charAt(0))) {
            return Optional.empty();
        }
        int loc;
        try {
            loc = Integer.parseInt(slice(line, I0_LOC, W_LOC).trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (loc != filterLocationId) {
            return Optional.empty();
        }
        String station = slice(line, I0_STATION, W_STATION).trim();
        Double temp = parseDoubleCol(slice(line, I0_TEMP, W_TEMP));
        Double feels = parseDoubleCol(slice(line, I0_FEEL, W_FEEL));
        String windDir = slice(line, I0_DIR, W_DIR).trim();
        Double wind = parseDoubleCol(slice(line, I0_SPD, W_SPD));
        String cond = slice(line, I0_COND, W_COND).trim();
        return Optional.of(new MeasureRow(fileDay, loc, station, temp, feels, windDir, wind, cond));
    }

    private static String slice(String line, int start, int width) {
        int end = Math.min(line.length(), start + width);
        if (start >= line.length()) {
            return "";
        }
        return line.substring(start, end);
    }

    private static Double parseDoubleCol(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(t.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean looksLikeRain(String conditions) {
        if (conditions == null || conditions.isBlank()) {
            return false;
        }
        String c = conditions.toLowerCase(Locale.ROOT);
        return c.contains("lluvia")
                || c.contains("rain")
                || c.contains("precip")
                || c.contains("llovizna")
                || c.contains("chaparrón")
                || c.contains("tormenta");
    }

    static int countRainDays(List<MeasureRow> rows) {
        Set<LocalDate> days = new HashSet<>();
        for (MeasureRow r : rows) {
            if (looksLikeRain(r.conditions())) {
                days.add(r.day());
            }
        }
        return days.size();
    }

    /**
     * Speed and direction from one observation row where {@code windKmh} is maximal (first row wins on ties).
     */
    static Optional<WindMax> maxWindWithDirection(List<MeasureRow> rows) {
        Double bestKmh = null;
        String bestDir = "";
        for (MeasureRow r : rows) {
            Double w = r.windKmh();
            if (w == null || Double.isNaN(w)) {
                continue;
            }
            if (bestKmh == null || w > bestKmh) {
                bestKmh = w;
                String d = r.windDir();
                bestDir = d != null ? d.trim() : "";
            }
        }
        if (bestKmh == null) {
            return Optional.empty();
        }
        return Optional.of(new WindMax(bestKmh, bestDir));
    }

    static final class WindMax {
        private final double kmh;
        private final String direction;

        WindMax(double kmh, String direction) {
            this.kmh = kmh;
            this.direction = direction != null ? direction : "";
        }

        double kmh() {
            return kmh;
        }

        /** Cardinal/intercardinal label from SMN (e.g. NE), may be empty. */
        String direction() {
            return direction;
        }
    }

    /** One parsed observation row from a measures file. */
    static final class MeasureRow {
        private final LocalDate day;
        private final int locationId;
        private final String stationLabel;
        private final Double tempC;
        private final Double feelsC;
        private final String windDir;
        private final Double windKmh;
        private final String conditions;

        MeasureRow(
                LocalDate day,
                int locationId,
                String stationLabel,
                Double tempC,
                Double feelsC,
                String windDir,
                Double windKmh,
                String conditions) {
            this.day = day;
            this.locationId = locationId;
            this.stationLabel = stationLabel;
            this.tempC = tempC;
            this.feelsC = feelsC;
            this.windDir = windDir;
            this.windKmh = windKmh;
            this.conditions = conditions;
        }

        LocalDate day() {
            return day;
        }

        int locationId() {
            return locationId;
        }

        String stationLabel() {
            return stationLabel;
        }

        Double tempC() {
            return tempC;
        }

        Double feelsC() {
            return feelsC;
        }

        /** Wind direction label from the measures row (same line as {@link #windKmh()}). */
        String windDir() {
            return windDir;
        }

        Double windKmh() {
            return windKmh;
        }

        String conditions() {
            return conditions;
        }
    }
}
