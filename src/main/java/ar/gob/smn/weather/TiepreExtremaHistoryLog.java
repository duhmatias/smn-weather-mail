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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Per-day append log of the three extremes chosen by each hourly tiepre extrema send: max temperature, min temperature
 * and max wind. One file per Buenos Aires calendar day under {@code <base>/<yyyy>/<MM>/<yyyy-MM-dd>.txt}; written only
 * after the hourly job sends successfully (see {@link WeatherMailApplication}).
 *
 * <p>Each extreme is appended on its own line so the daily summary at 08:00 ART can read the previous day's file and
 * pick the absolute max/min/max-wind across the day.</p>
 *
 * <p>Line format (pipe-separated, UTF-8):
 * {@code <sent_at_art>|<TYPE>|<station>|<obs_at_art>|<value>|<wind_dir>}.</p>
 *
 * <ul>
 *   <li>{@code TYPE}: {@link Type#MAX_TEMP MAX_T} / {@link Type#MIN_TEMP MIN_T} / {@link Type#MAX_WIND MAX_W}.</li>
 *   <li>{@code value}: temperature in °C or wind speed in km/h, locale-root with one decimal.</li>
 *   <li>{@code wind_dir}: only set for {@code MAX_W} (may be empty).</li>
 *   <li>Day file = ART date of the observation time (so a row read at 00:30 ART that points to 23:00 ART yesterday is
 *       still appended to yesterday's file).</li>
 * </ul>
 */
final class TiepreExtremaHistoryLog {

    private static final ZoneId ART = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter DAY_FILE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter LINE_TS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX", Locale.ROOT);
    /** Field separator inside a record. Stations and direction text are sanitized to never contain it. */
    private static final char SEP = '|';

    enum Type {
        MAX_TEMP("MAX_T"),
        MIN_TEMP("MIN_T"),
        MAX_WIND("MAX_W");

        private final String code;

        Type(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }

        static Optional<Type> fromCode(String s) {
            if (s == null) {
                return Optional.empty();
            }
            for (Type t : values()) {
                if (t.code.equals(s)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    /** One stored extreme: {@link #type}, station, observation time (ART), value, optional wind direction. */
    static final class Record {
        private final Type type;
        private final String stationName;
        private final ZonedDateTime observationArt;
        private final double value;
        private final String windDirection;

        Record(Type type, String stationName, ZonedDateTime observationArt, double value, String windDirection) {
            this.type = type;
            this.stationName = stationName;
            this.observationArt = observationArt;
            this.value = value;
            this.windDirection = windDirection;
        }

        Type type() {
            return type;
        }

        String stationName() {
            return stationName;
        }

        ZonedDateTime observationArt() {
            return observationArt;
        }

        double value() {
            return value;
        }

        /** Empty {@link String} when not a wind record or when SMN does not provide a direction. */
        String windDirection() {
            return windDirection == null ? "" : windDirection;
        }
    }

    private final Path baseDir;

    TiepreExtremaHistoryLog(Path baseDir) {
        this.baseDir = baseDir;
    }

    Path baseDir() {
        return baseDir;
    }

    /**
     * Appends the snapshot's three extremes (when present and numeric) to their respective daily files. Wind is only
     * written when {@link TiepreExtremaHourly.Snapshot#maxWind} has a numeric speed.
     *
     * @param sentAt instant when the hourly send completed (used as the {@code sent_at_art} column)
     */
    void append(TiepreExtremaHourly.Snapshot snap, java.time.Instant sentAt) throws IOException {
        if (snap == null) {
            return;
        }
        ZonedDateTime sentArt = sentAt.atZone(ART);
        appendRecord(sentArt, Type.MAX_TEMP, snap.maxTemp);
        appendRecord(sentArt, Type.MIN_TEMP, snap.minTemp);
        if (snap.maxWind != null && snap.maxWind.windSpeed() != null && !Double.isNaN(snap.maxWind.windSpeed())) {
            appendRecord(sentArt, Type.MAX_WIND, snap.maxWind);
        }
    }

    private void appendRecord(ZonedDateTime sentArt, Type type, SmnClient.Observation o) throws IOException {
        if (o == null) {
            return;
        }
        if (type != Type.MAX_WIND
                && (o.temperature() == null || Double.isNaN(o.temperature()))) {
            return;
        }
        ZonedDateTime obsArt = o.observationTime().withZoneSameInstant(ART);
        LocalDate day = obsArt.toLocalDate();
        Path file = dayFile(day);
        Files.createDirectories(file.getParent());
        double value =
                type == Type.MAX_WIND ? o.windSpeed() : o.temperature();
        String dir = type == Type.MAX_WIND ? sanitize(o.windDirection()) : "";
        StringBuilder line = new StringBuilder();
        line.append(sentArt.format(LINE_TS))
                .append(SEP)
                .append(type.code())
                .append(SEP)
                .append(sanitize(o.stationName()))
                .append(SEP)
                .append(obsArt.format(LINE_TS))
                .append(SEP)
                .append(String.format(Locale.ROOT, "%.1f", value))
                .append(SEP)
                .append(dir)
                .append('\n');
        Files.writeString(
                file,
                line.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    Path dayFile(LocalDate day) {
        return baseDir
                .resolve(String.format(Locale.ROOT, "%04d", day.getYear()))
                .resolve(String.format(Locale.ROOT, "%02d", day.getMonthValue()))
                .resolve(day.format(DAY_FILE) + ".txt");
    }

    /**
     * Reads every parseable record for {@code day}; missing file returns an empty list. Malformed lines are skipped
     * silently (best-effort log; unparseable rows are not treated as fatal).
     */
    List<Record> readDay(LocalDate day) throws IOException {
        Path file = dayFile(day);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Record> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split("\\|", -1);
            if (p.length < 6) {
                continue;
            }
            Optional<Type> typeOpt = Type.fromCode(p[1].trim());
            if (typeOpt.isEmpty()) {
                continue;
            }
            ZonedDateTime obsArt;
            try {
                obsArt = ZonedDateTime.parse(p[3].trim(), LINE_TS);
            } catch (Exception e) {
                continue;
            }
            double value;
            try {
                value = Double.parseDouble(p[4].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String station = p[2];
            String dir = p[5];
            out.add(new Record(typeOpt.get(), station, obsArt, value, dir));
        }
        return out;
    }

    /**
     * Picks the most extreme record for each {@link Type} from {@code records} (max for MAX_T/MAX_W, min for MIN_T).
     * Ties broken by latest observation time (so the most recent hour wins).
     */
    static DailyExtremes pickDailyExtremes(List<Record> records) {
        Record maxTemp = null;
        Record minTemp = null;
        Record maxWind = null;
        for (Record r : records) {
            switch (r.type()) {
                case MAX_TEMP:
                    if (maxTemp == null
                            || r.value() > maxTemp.value()
                            || (r.value() == maxTemp.value()
                                    && r.observationArt().isAfter(maxTemp.observationArt()))) {
                        maxTemp = r;
                    }
                    break;
                case MIN_TEMP:
                    if (minTemp == null
                            || r.value() < minTemp.value()
                            || (r.value() == minTemp.value()
                                    && r.observationArt().isAfter(minTemp.observationArt()))) {
                        minTemp = r;
                    }
                    break;
                case MAX_WIND:
                    if (maxWind == null
                            || r.value() > maxWind.value()
                            || (r.value() == maxWind.value()
                                    && r.observationArt().isAfter(maxWind.observationArt()))) {
                        maxWind = r;
                    }
                    break;
            }
        }
        return new DailyExtremes(maxTemp, minTemp, maxWind);
    }

    /** Result of {@link #pickDailyExtremes}; any field may be {@code null} when no records of that type exist. */
    static final class DailyExtremes {
        private final Record maxTemp;
        private final Record minTemp;
        private final Record maxWind;

        DailyExtremes(Record maxTemp, Record minTemp, Record maxWind) {
            this.maxTemp = maxTemp;
            this.minTemp = minTemp;
            this.maxWind = maxWind;
        }

        Record maxTemp() {
            return maxTemp;
        }

        Record minTemp() {
            return minTemp;
        }

        Record maxWind() {
            return maxWind;
        }

        boolean isEmpty() {
            return maxTemp == null && minTemp == null && maxWind == null;
        }
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r", " ").replace("\n", " ").replace("\t", " ").replace('|', '/');
    }
}
