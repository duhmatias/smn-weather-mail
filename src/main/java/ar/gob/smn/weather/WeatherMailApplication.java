package ar.gob.smn.weather;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polls SMN on a fixed interval; when a station reports a new observation (not yet recorded in the
 * sent log), emails that station alone. Each distinct observation time per location is mailed at most once.
 * Stations are not polled while the current Buenos Aires clock hour already has a mailed observation
 * for that location (SMN may still lag; polling resumes next hour or when observation hour catches up).
 */
public final class WeatherMailApplication {

    private static final Logger LOG = Logger.getLogger(WeatherMailApplication.class.getName());

    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(2);
    private static final Duration SLEEP_BETWEEN_STATIONS = Duration.ofSeconds(10);
    private static final DateTimeFormatter SUBJECT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static final String DEFAULT_SENT_LOG = "smn-weather-sent.txt";
    private static final String DEFAULT_MEASURES_DIR = "smn-measures";
    /** Degrees Celsius; below this difference, temp and sensación térmica are treated as equal for the subject. */
    private static final double TEMP_SUBJECT_EPS = 0.05;

    /** Default stations: CABA + Aeroparque (override with SMN_LOCATION_IDS). */
    private static final Map<Integer, String> KNOWN_STATION_NAMES = Map.of(
            4864, "Ciudad Autónoma de Buenos Aires",
            10821, "Aeroparque Buenos Aires");

    /** Throttle "SMN still on previous hour" logs per location id. */
    private static final Map<Integer, Long> lastStaleDedupeLogAtMs = new HashMap<>();

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        SmnClient smn = new SmnClient();
        MailSender mail = new MailSender(config);
        TelegramNotifier telegram = config.telegram() != null ? new TelegramNotifier(config.telegram()) : null;

        List<SmnClient.Station> stations = stationsFromEnv();
        Path sentLogPath = sentLogPathFromEnv();
        SentWeatherLog sent = SentWeatherLog.open(sentLogPath);
        Map<Integer, ZonedDateTime> lastMailedObsHourArt =
                new HashMap<>(sent.latestMailedObservationHourPerLocation(BUENOS_AIRES));
        Path measuresDir = measuresDirFromEnv();
        MeasuresDailyLog measures = new MeasuresDailyLog(measuresDir);

        LOG.info(() -> "Stations: " + stations);
        LOG.info(() -> "Sent log (dedupe): " + sentLogPath.toAbsolutePath());
        LOG.info(() -> "Measures dir: " + measuresDir.toAbsolutePath() + " (YYYY/MM/<day>.txt)");
        LOG.info(() -> "Recipients: " + config.recipients() + " | zone: " + BUENOS_AIRES + " | poll: " + POLL_INTERVAL
                + " | pause between stations: " + SLEEP_BETWEEN_STATIONS);
        if (telegram != null) {
            LOG.info(() -> "Telegram: " + config.telegram().chatIds().size() + " chat(s)");
        }

        while (true) {
            ZonedDateTime nowHourArt = ZonedDateTime.now(BUENOS_AIRES).truncatedTo(ChronoUnit.HOURS);
            for (int i = 0; i < stations.size(); i++) {
                SmnClient.Station station = stations.get(i);
                ZonedDateTime mailedObsHour = lastMailedObsHourArt.get(station.locationId());
                if (mailedObsHour != null && mailedObsHour.equals(nowHourArt)) {
                    sleepBetweenStationsIfNeeded(i, stations.size());
                    continue;
                }
                try {
                    SmnClient.Observation o = smn.fetchObservation(station);
                    Instant instant = o.observationTime().toInstant();
                    String key = SentWeatherLog.key(station.locationId(), instant);
                    if (sent.contains(key)) {
                        logIfStaleDedupeSkip(station, o);
                        sleepBetweenStationsIfNeeded(i, stations.size());
                        continue;
                    }
                    String body = MailBodyFormatter.formatSingle(o);
                    String subject = subjectFor(o);
                    mail.send(config.recipients(), subject, body);
                    if (telegram != null) {
                        try {
                            telegram.sendWeatherMessage(subject, body);
                        } catch (Exception e) {
                            LOG.log(Level.WARNING, "Telegram send failed (email was sent)", e);
                        }
                    }
                    sent.append(key);
                    lastMailedObsHourArt.put(
                            station.locationId(),
                            o.observationTime().withZoneSameInstant(BUENOS_AIRES).truncatedTo(ChronoUnit.HOURS));
                    try {
                        measures.append(o, station.locationId());
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "Measures file append failed for " + station, e);
                    }
                    LOG.info(() -> "Email sent: " + o.stationName() + " @ " + instant);
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().contains("401")) {
                        LOG.warning("SMN fetch for " + station + ": " + e.getMessage());
                    } else {
                        LOG.log(Level.WARNING, "SMN fetch failed for " + station, e);
                    }
                }
                sleepBetweenStationsIfNeeded(i, stations.size());
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    private static void sleepBetweenStationsIfNeeded(int stationIndex, int stationCount) throws InterruptedException {
        if (stationIndex >= stationCount - 1) {
            return;
        }
        Thread.sleep(SLEEP_BETWEEN_STATIONS.toMillis());
    }

    private static Path sentLogPathFromEnv() {
        String raw = System.getenv("SMN_SENT_LOG_FILE");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_SENT_LOG);
    }

    private static Path measuresDirFromEnv() {
        String raw = System.getenv("SMN_MEASURES_DIR");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_MEASURES_DIR);
    }

    /**
     * SMN often keeps the same {@code date} in JSON until the next synoptic bulletin is published, which can
     * lag wall-clock (e.g. still 12:00 ART after 13:00). We already mailed that snapshot; explain in logs.
     */
    private static void logIfStaleDedupeSkip(SmnClient.Station station, SmnClient.Observation o) {
        ZonedDateTime obsHour =
                o.observationTime().withZoneSameInstant(BUENOS_AIRES).truncatedTo(ChronoUnit.HOURS);
        ZonedDateTime nowHour = ZonedDateTime.now(BUENOS_AIRES).truncatedTo(ChronoUnit.HOURS);
        if (!nowHour.isAfter(obsHour)) {
            return;
        }
        long ms = System.currentTimeMillis();
        synchronized (lastStaleDedupeLogAtMs) {
            long prev = lastStaleDedupeLogAtMs.getOrDefault(station.locationId(), 0L);
            if (ms - prev < Duration.ofMinutes(20).toMillis()) {
                return;
            }
            lastStaleDedupeLogAtMs.put(station.locationId(), ms);
        }
        LOG.info(() -> "No new email for " + station.label()
                + ": API observation is still " + obsHour + " ART while local time is " + nowHour
                + " ART. SMN usually updates the hour after the bulletin; the next send happens when "
                + "\"date\" in the JSON advances.");
    }

    private static String subjectFor(SmnClient.Observation o) {
        ZonedDateTime local = o.observationTime().withZoneSameInstant(BUENOS_AIRES);
        String tempStr = o.temperature() != null ? String.format(Locale.ROOT, "%.1f °C", o.temperature()) : "—";
        String lead = tempStr;
        Double t = o.temperature();
        Double st = o.feelsLike();
        if (st != null
                && t != null
                && Math.abs(st - t) >= TEMP_SUBJECT_EPS) {
            lead = tempStr + " — ST " + String.format(Locale.ROOT, "%.1f °C", st);
        }
        return lead + " — SMN weather — " + o.stationName() + " — " + local.format(SUBJECT_TIME) + " ART";
    }

    /**
     * Env SMN_LOCATION_IDS: comma-separated location ids (e.g. {@code 4864} or {@code 4864,10821}).
     * If unset, uses CABA and Aeroparque ({@code 4864,10821}).
     */
    private static List<SmnClient.Station> stationsFromEnv() {
        String raw = System.getenv("SMN_LOCATION_IDS");
        if (raw == null || raw.isBlank()) {
            raw = "4864,10821";
        }
        String[] parts = raw.split(",");
        List<SmnClient.Station> list = new ArrayList<>();
        for (String part : parts) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            int id = Integer.parseInt(s);
            String name = KNOWN_STATION_NAMES.getOrDefault(id, "Ubicación SMN " + id);
            list.add(new SmnClient.Station(id, name));
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("SMN_LOCATION_IDS produced no stations: " + raw);
        }
        return List.copyOf(list);
    }
}
