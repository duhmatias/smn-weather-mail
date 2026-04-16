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
 * Appends one line per forecast snapshot (same calendar-day file layout as {@link MeasuresDailyLog}:
 * {@code <base>/<yyyy>/<MM>/<yyyy-MM-dd>.txt}). Sources: observation email attachment, morning/evening Telegram
 * bulletin sends.
 */
final class ForecastDailyLog {

    private static final ZoneId ART = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter DAY_FILE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX", Locale.ROOT);

    private static final String COL_GAP = "  ";

    private static final int W_TIME = 28;
    private static final int W_LOC = 6;
    private static final int W_SRC = 12;
    private static final int W_UPD = 36;
    private static final int W_HOST = 24;
    private static final int W_SNIP = 80;

    private final Path baseDir;

    ForecastDailyLog(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * @param writtenArt    row timestamp (ART), e.g. observation time or send time
     * @param source        {@code OBS_EMAIL}, {@code TG_MORNING}, or {@code TG_EVENING}
     * @param smnUpdated    SMN JSON {@code updated} (may be empty)
     * @param bodyForSnippet telegram-style body (truncated to one line in the table)
     */
    void append(
            ZonedDateTime writtenArt,
            int locationId,
            String source,
            String smnUpdated,
            String host,
            String bodyForSnippet)
            throws IOException {
        ZonedDateTime art = writtenArt.withZoneSameInstant(ART);
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
        chunk.append(formatRow(art, locationId, source, smnUpdated, host, bodyForSnippet)).append('\n');

        Files.writeString(
                file,
                chunk.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String headerLine() {
        return colText("written_time_art", W_TIME, false)
                + COL_GAP
                + colText("location_id", W_LOC, true)
                + COL_GAP
                + colText("source", W_SRC, false)
                + COL_GAP
                + colText("smn_updated", W_UPD, false)
                + COL_GAP
                + colText("host", W_HOST, false)
                + COL_GAP
                + colText("forecast_snippet", W_SNIP, false);
    }

    private static String ruleLine() {
        return "-".repeat(W_TIME)
                + COL_GAP
                + "-".repeat(W_LOC)
                + COL_GAP
                + "-".repeat(W_SRC)
                + COL_GAP
                + "-".repeat(W_UPD)
                + COL_GAP
                + "-".repeat(W_HOST)
                + COL_GAP
                + "-".repeat(W_SNIP);
    }

    private static String formatRow(
            ZonedDateTime art,
            int locationId,
            String source,
            String smnUpdated,
            String host,
            String bodyForSnippet) {
        return colText(art.format(LINE_TS), W_TIME, false)
                + COL_GAP
                + colInt(locationId, W_LOC)
                + COL_GAP
                + colText(sanitize(source), W_SRC, false)
                + COL_GAP
                + colText(sanitize(smnUpdated), W_UPD, false)
                + COL_GAP
                + colText(sanitize(host), W_HOST, false)
                + COL_GAP
                + colText(oneLineSnippet(bodyForSnippet), W_SNIP, false);
    }

    private static String oneLineSnippet(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\r", " ").replace("\n", " ").replace("\t", " ");
        while (t.contains("  ")) {
            t = t.replace("  ", " ");
        }
        return t.trim();
    }

    private static String sanitize(String s) {
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
}
