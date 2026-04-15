package ar.gob.smn.weather;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Appends observation rows to a text file per calendar day (Buenos Aires). Files live under
 * {@code <base>/<yyyy>/<MM>/<yyyy-MM-dd>.txt}. Rows use fixed-width columns (two spaces between fields)
 * so values line up when viewed in a monospace font.
 */
final class MeasuresDailyLog {

    private static final ZoneId ART = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter DAY_FILE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX", Locale.ROOT);

    private static final String COL_GAP = "  ";

    private static final int W_TIME = 28;
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

    private final Path baseDir;

    MeasuresDailyLog(Path baseDir) {
        this.baseDir = baseDir;
    }

    void append(SmnClient.Observation o, int locationId) throws IOException {
        ZonedDateTime art = o.observationTime().withZoneSameInstant(ART);
        LocalDate day = art.toLocalDate();
        Path monthDir = baseDir
                .resolve(String.format(Locale.ROOT, "%04d", day.getYear()))
                .resolve(String.format(Locale.ROOT, "%02d", day.getMonthValue()));
        Files.createDirectories(monthDir);
        Path file = monthDir.resolve(day.format(DAY_FILE) + ".txt");

        boolean writeHeader = !Files.isRegularFile(file) || Files.size(file) == 0;
        StringBuilder chunk = new StringBuilder();
        if (writeHeader) {
            chunk.append(headerLine()).append('\n');
            chunk.append(ruleLine()).append('\n');
        }
        chunk.append(formatRow(art, locationId, o)).append('\n');

        Files.writeString(
                file,
                chunk.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String headerLine() {
        return colText("observation_time_art", W_TIME, false)
                + COL_GAP
                + colText("location_id", W_LOC, true)
                + COL_GAP
                + colText("station", W_STATION, false)
                + COL_GAP
                + colText("temp_c", W_TEMP, true)
                + COL_GAP
                + colText("feels_c", W_FEEL, true)
                + COL_GAP
                + colText("hum_%", W_HUM, true)
                + COL_GAP
                + colText("pres_hpa", W_PRES, true)
                + COL_GAP
                + colText("vis_km", W_VIS, true)
                + COL_GAP
                + colText("wind_dir", W_DIR, false)
                + COL_GAP
                + colText("wind_kmh", W_SPD, true)
                + COL_GAP
                + colText("conditions", W_COND, false);
    }

    /** Separator row of dashes under the header for readability. */
    private static String ruleLine() {
        return "-".repeat(W_TIME)
                + COL_GAP
                + "-".repeat(W_LOC)
                + COL_GAP
                + "-".repeat(W_STATION)
                + COL_GAP
                + "-".repeat(W_TEMP)
                + COL_GAP
                + "-".repeat(W_FEEL)
                + COL_GAP
                + "-".repeat(W_HUM)
                + COL_GAP
                + "-".repeat(W_PRES)
                + COL_GAP
                + "-".repeat(W_VIS)
                + COL_GAP
                + "-".repeat(W_DIR)
                + COL_GAP
                + "-".repeat(W_SPD)
                + COL_GAP
                + "-".repeat(W_COND);
    }

    private static String formatRow(ZonedDateTime art, int locationId, SmnClient.Observation o) {
        return colText(art.format(LINE_TS), W_TIME, false)
                + COL_GAP
                + colInt(locationId, W_LOC)
                + COL_GAP
                + colText(sanitizeText(o.stationName()), W_STATION, false)
                + COL_GAP
                + colDouble(o.temperature(), W_TEMP, 1)
                + COL_GAP
                + colDouble(o.feelsLike(), W_FEEL, 1)
                + COL_GAP
                + colDouble(o.humidity(), W_HUM, 1)
                + COL_GAP
                + colDouble(o.pressure(), W_PRES, 1)
                + COL_GAP
                + colDouble(o.visibilityKm(), W_VIS, 1)
                + COL_GAP
                + colText(sanitizeText(o.windDirection()), W_DIR, false)
                + COL_GAP
                + colDouble(o.windSpeed(), W_SPD, 1)
                + COL_GAP
                + colText(sanitizeText(o.description()), W_COND, false);
    }

    private static String sanitizeText(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r", " ").replace("\n", " ").replace("\t", " ");
    }

    private static String colText(String s, int width, boolean rightAlign) {
        if (s == null) {
            s = "";
        }
        if (s.length() > width) {
            s = width >= 3 ? s.substring(0, width - 3) + "..." : s.substring(0, width);
        }
        if (rightAlign) {
            return String.format(Locale.ROOT, "%" + width + "s", s);
        }
        return String.format(Locale.ROOT, "%-" + width + "s", s);
    }

    private static String colInt(int n, int width) {
        return String.format(Locale.ROOT, "%" + width + "d", n);
    }

    private static String colDouble(Double v, int width, int fractionDigits) {
        if (v == null) {
            return " ".repeat(width);
        }
        return String.format(Locale.ROOT, "%" + width + "." + fractionDigits + "f", v);
    }
}
