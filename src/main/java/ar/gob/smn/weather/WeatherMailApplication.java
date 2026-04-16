package ar.gob.smn.weather;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * Polls SMN on an interval; when a station reports a new observation (not yet recorded in the
 * sent log), emails that station alone. Each distinct observation time per location is mailed at most once.
 * Stations are not polled while the current Buenos Aires clock hour already has a mailed observation
 * for that location. While the clock hour has no mailed bulletin yet, sleep is 2 minutes through minute 19
 * and 5 minutes from minute 20 until SMN publishes the new hour (or the next hour begins).
 */
public final class WeatherMailApplication {

    private static final Logger LOG = Logger.getLogger(WeatherMailApplication.class.getName());

    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(2);
    /** While the current ART clock hour has no mailed bulletin yet: sleep this long from :00 through :19. */
    private static final Duration POLL_STALE_EARLY = Duration.ofMinutes(2);
    /** Same situation from minute 20 until SMN catches up: slower cadence. */
    private static final Duration POLL_STALE_LATE = Duration.ofMinutes(5);
    /** While in the CABA forecast Telegram window and SMN has not yet published a new {@code updated}, poll faster. */
    private static final Duration FORECAST_POLL_ACTIVE = Duration.ofMinutes(1);
    private static final Duration SLEEP_BETWEEN_STATIONS = Duration.ofSeconds(10);
    private static final DateTimeFormatter SUBJECT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static final String DEFAULT_SENT_LOG = "smn-weather-sent.txt";
    private static final String DEFAULT_FORECAST_SENT_FILE = "smn-forecast-sent.txt";
    private static final String DEFAULT_MEASURES_DIR = "smn-measures";
    private static final String DEFAULT_FORECAST_HISTORY_DIR = "smn-forecast-history";
    private static final String FH_SRC_EMAIL = "OBS_EMAIL";
    private static final String FH_TG_MORNING = "TG_MORNING";
    private static final String FH_TG_EVENING = "TG_EVENING";
    /** CABA — only this location gets forecast in email and scheduled forecast Telegram. */
    private static final int CABA_LOCATION_ID = 4864;
    /** Degrees Celsius; below this difference, temp and sensación térmica are treated as equal for the subject. */
    private static final double TEMP_SUBJECT_EPS = 0.05;

    /** Default stations: CABA + Aeroparque (override with SMN_LOCATION_IDS). */
    private static final Map<Integer, String> KNOWN_STATION_NAMES = Map.of(
            4864, "Ciudad Autónoma de Buenos Aires",
            10821, "Aeroparque Buenos Aires");

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        SmnClient smn = new SmnClient();
        MailSender mail = new MailSender(config);
        TelegramNotifier telegramConditions =
                config.telegram() != null ? new TelegramNotifier(config.telegram()) : null;
        TelegramNotifier telegramForecast =
                config.telegram() != null ? new TelegramNotifier(config.telegramForForecast()) : null;

        List<SmnClient.Station> stations = stationsFromEnv();
        Path sentLogPath = sentLogPathFromEnv();
        SentWeatherLog sent = SentWeatherLog.open(sentLogPath);
        Path forecastSentPath = forecastSentPathFromEnv();
        SentForecastLog forecastSent = SentForecastLog.open(forecastSentPath);
        Map<Integer, ZonedDateTime> lastMailedObsHourArt =
                new HashMap<>(sent.latestMailedObservationHourPerLocation(BUENOS_AIRES));
        Path measuresDir = measuresDirFromEnv();
        MeasuresDailyLog measures = new MeasuresDailyLog(measuresDir);
        Path forecastHistoryDir = forecastHistoryDirFromEnv();
        ForecastDailyLog forecastHistory = new ForecastDailyLog(forecastHistoryDir);
        boolean configIncludesCaba = stationListIncludesCaba(stations);

        LOG.info(() -> "Stations: " + stations);
        LOG.info(() -> "Sent log (dedupe): " + sentLogPath.toAbsolutePath());
        LOG.info(() -> "Forecast Telegram dedupe: " + forecastSentPath.toAbsolutePath());
        LOG.info(() -> "Measures dir: " + measuresDir.toAbsolutePath() + " (YYYY/MM/<day>.txt)");
        LOG.info(() -> "Forecast history dir: " + forecastHistoryDir.toAbsolutePath() + " (YYYY/MM/<day>.txt)");
        LOG.info(() -> "Recipients: " + config.recipients() + " | zone: " + BUENOS_AIRES + " | poll: " + POLL_INTERVAL
                + " (2m through :19, 5m from :20 if hourly bulletin still pending) | pause between stations: "
                + SLEEP_BETWEEN_STATIONS);
        if (telegramConditions != null) {
            LOG.info(() -> "Telegram (conditions): " + config.telegram().chatIds().size() + " chat(s)");
            if (config.telegramForecastConfig() != null) {
                boolean otherBot =
                        !config.telegramForForecast().botToken().equals(config.telegram().botToken());
                LOG.info(() -> "Telegram (forecast): "
                        + config.telegramForForecast().chatIds().size()
                        + " chat(s)"
                        + (otherBot ? " — separate bot (telegram.forecast.bot.token)" : " — same bot, different chats"));
            }
        }

        String reportHost = resolveReportHostLabel(config);
        LOG.info(() -> "Condition messages host footer: " + reportHost + " (smn.report.host / SMN_REPORT_HOST / HOSTNAME)");

        tryCatchUpMissingForecastTelegramOnStartup(
                smn, telegramForecast, forecastSent, configIncludesCaba, forecastHistory, reportHost);

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
                        logConditionsCheckedNotUpdatedYet(station, o);
                        sleepBetweenStationsIfNeeded(i, stations.size());
                        continue;
                    }
                    SmnClient.ForecastPayload forecastPayload = null;
                    String forecastBlock = "";
                    String forecastTgDays = null;
                    if (station.locationId() == CABA_LOCATION_ID) {
                        try {
                            forecastPayload = smn.fetchForecastPayload(station);
                            LocalDate obsDay =
                                    o.observationTime().withZoneSameInstant(BUENOS_AIRES).toLocalDate();
                            forecastBlock =
                                    ForecastFormatter.formatForCalendarDay(
                                            forecastPayload.forecastJson(), obsDay);
                            forecastTgDays =
                                    TelegramForecastFormatter.formatTelegramHtmlDaySlice(
                                            forecastPayload.forecastJson(), obsDay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (IOException e) {
                            LOG.log(Level.FINE, "Forecast fetch failed: " + e.getMessage());
                        }
                    }
                    String bodyEmail = MailBodyFormatter.formatSingle(
                            o,
                            forecastBlock,
                            null,
                            true,
                            station.locationId(),
                            reportHost,
                            MailBodyFormatter.BodyTarget.HTML_EMAIL);
                    String bodyTelegram = MailBodyFormatter.formatSingle(
                            o,
                            forecastBlock,
                            forecastTgDays,
                            true,
                            station.locationId(),
                            reportHost,
                            MailBodyFormatter.BodyTarget.HTML_TELEGRAM);
                    String subject = subjectFor(o);
                    mail.send(config.recipients(), subject, bodyEmail);
                    if (telegramConditions != null) {
                        try {
                            telegramConditions.sendHtml(bodyTelegram);
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
                        if (station.locationId() == CABA_LOCATION_ID && forecastPayload != null) {
                            ZonedDateTime obsArt = o.observationTime().withZoneSameInstant(BUENOS_AIRES);
                            forecastHistory.append(
                                    obsArt,
                                    CABA_LOCATION_ID,
                                    FH_SRC_EMAIL,
                                    forecastPayload.updated() != null ? forecastPayload.updated() : "",
                                    reportHost,
                                    forecastPayload.telegramText());
                        }
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "Measures/forecast history append failed for " + station, e);
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
            boolean fastForecastPoll =
                    trySendCabaForecastTelegram(
                            smn, telegramForecast, forecastSent, configIncludesCaba, forecastHistory, reportHost);
            Thread.sleep(PollCadence.resolve(fastForecastPoll, stations, lastMailedObsHourArt).sleepMillis());
        }
    }

    /**
     * Cadence between full poll cycles (all stations + optional forecast round). Explicit states replace nested
     * conditionals for the “hourly bulletin not in yet” window in ART.
     */
    private enum PollCadence {
        /** CABA forecast morning/evening window: poll every minute until SMN publishes a new {@code updated}. */
        FORECAST_WINDOW(FORECAST_POLL_ACTIVE),
        /**
         * At least one station still needs a mailed observation for the current ART clock hour; local minute is
         * 0–19 → poll every 2 minutes.
         */
        WAITING_BULLETIN_EARLY_ART(POLL_STALE_EARLY),
        /**
         * Same as {@link #WAITING_BULLETIN_EARLY_ART} but from minute 20 until SMN catches up → poll every 5 minutes.
         */
        WAITING_BULLETIN_LATE_ART(POLL_STALE_LATE),
        /** All stations have this hour’s bulletin on file, or we are not waiting on the clock hour. */
        STEADY(POLL_INTERVAL);

        private final Duration sleep;

        PollCadence(Duration sleep) {
            this.sleep = sleep;
        }

        long sleepMillis() {
            return sleep.toMillis();
        }

        static PollCadence resolve(
                boolean fastForecastPoll,
                List<SmnClient.Station> stations,
                Map<Integer, ZonedDateTime> lastMailedObsHourArt) {
            if (fastForecastPoll) {
                return FORECAST_WINDOW;
            }
            ZonedDateTime nowArt = ZonedDateTime.now(BUENOS_AIRES);
            ZonedDateTime nowHourArt = nowArt.truncatedTo(ChronoUnit.HOURS);
            if (!owesThisClockHour(stations, lastMailedObsHourArt, nowHourArt)) {
                return STEADY;
            }
            return nowArt.getMinute() < 20 ? WAITING_BULLETIN_EARLY_ART : WAITING_BULLETIN_LATE_ART;
        }

        private static boolean owesThisClockHour(
                List<SmnClient.Station> stations,
                Map<Integer, ZonedDateTime> lastMailedObsHourArt,
                ZonedDateTime nowHourArt) {
            for (SmnClient.Station s : stations) {
                ZonedDateTime mailed = lastMailedObsHourArt.get(s.locationId());
                if (mailed == null || !mailed.equals(nowHourArt)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Full HTML bulletin + host line (no {@code pre}); bulletin already uses allowed Telegram HTML tags. */
    private static String forecastTelegramEnvelope(String telegramHtmlBulletin, String reportHost) {
        String h = reportHost != null && !reportHost.isBlank() ? reportHost.trim() : "unknown";
        return telegramHtmlBulletin + "\n(" + htmlEscape(h) + ")";
    }

    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Before noon ART, ensure today's morning bulletin is in {@link SentForecastLog}; from noon on, ensure
     * today's evening bulletin is. If missing, one fetch/send attempt (no time-window restriction).
     */
    private static void tryCatchUpMissingForecastTelegramOnStartup(
            SmnClient smn,
            TelegramNotifier telegramForecast,
            SentForecastLog forecastSent,
            boolean configIncludesCaba,
            ForecastDailyLog forecastHistory,
            String reportHost) {
        if (telegramForecast == null || !configIncludesCaba) {
            LOG.info("Forecast catch-up at startup: skipped (Telegram off or CABA not in SMN_LOCATION_IDS).");
            return;
        }
        ZonedDateTime art = ZonedDateTime.now(BUENOS_AIRES);
        LocalDate dayArt = art.toLocalDate();
        LocalTime t = art.toLocalTime();
        boolean afterNoon = !t.isBefore(LocalTime.NOON);
        try {
            if (!afterNoon) {
                if (forecastSent.hasMorningRecordedFor(dayArt)) {
                    LOG.info("Forecast catch-up at startup: today's morning bulletin already recorded for " + dayArt + ".");
                    return;
                }
                LOG.info("Forecast catch-up at startup: today's morning bulletin not on file — attempting fetch.");
                morningForecastTelegramRound(
                        smn, telegramForecast, forecastSent, dayArt, "startup", forecastHistory, reportHost);
            } else {
                if (forecastSent.hasEveningRecordedFor(dayArt)) {
                    LOG.info("Forecast catch-up at startup: today's evening bulletin already recorded for " + dayArt + ".");
                    return;
                }
                LOG.info("Forecast catch-up at startup: today's evening bulletin not on file — attempting fetch.");
                eveningForecastTelegramRound(
                        smn, telegramForecast, forecastSent, dayArt, "startup", forecastHistory, reportHost);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Polls SMN for CABA forecast in ~5:30 / ~17:30 ART windows and sends Telegram when the bulletin
     * {@code updated} advances. Returns true to poll again sooner while still waiting on SMN.
     */
    private static boolean trySendCabaForecastTelegram(
            SmnClient smn,
            TelegramNotifier telegramForecast,
            SentForecastLog forecastSent,
            boolean configIncludesCaba,
            ForecastDailyLog forecastHistory,
            String reportHost)
            throws InterruptedException {
        if (telegramForecast == null || !configIncludesCaba) {
            return false;
        }
        ZonedDateTime art = ZonedDateTime.now(BUENOS_AIRES);
        LocalTime t = art.toLocalTime();
        LocalDate dayArt = art.toLocalDate();
        boolean morningWindow = !t.isBefore(LocalTime.of(5, 15)) && t.isBefore(LocalTime.NOON);
        boolean eveningWindow = !t.isBefore(LocalTime.of(17, 15));
        if (!morningWindow && !eveningWindow) {
            return false;
        }
        if (morningWindow) {
            return morningForecastTelegramRound(
                    smn, telegramForecast, forecastSent, dayArt, "scheduled", forecastHistory, reportHost);
        }
        return eveningForecastTelegramRound(
                smn, telegramForecast, forecastSent, dayArt, "scheduled", forecastHistory, reportHost);
    }

    private static boolean morningForecastTelegramRound(
            SmnClient smn,
            TelegramNotifier telegramForecast,
            SentForecastLog forecastSent,
            LocalDate dayArt,
            String mode,
            ForecastDailyLog forecastHistory,
            String reportHost)
            throws InterruptedException {
        SmnClient.Station caba = cabaStation();
        try {
            SmnClient.ForecastPayload p = smn.fetchForecastPayload(caba);
            String u = p.updated();
            if (u.isBlank()) {
                LOG.fine("CABA forecast (" + mode + "): missing \"updated\", will retry soon");
                return true;
            }
            if (forecastSent.shouldSendMorning(dayArt, u)) {
                telegramForecast.sendHtml(forecastTelegramEnvelope(p.telegramHtml(), reportHost));
                forecastSent.recordMorning(dayArt, u);
                try {
                    forecastHistory.append(
                            ZonedDateTime.now(BUENOS_AIRES),
                            CABA_LOCATION_ID,
                            FH_TG_MORNING,
                            u,
                            reportHost,
                            p.telegramText());
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Forecast history append failed (morning Telegram)", e);
                }
                LOG.info(() -> "Telegram forecast sent for CABA (morning, " + mode + ", updated " + u + ")");
                return false;
            }
            if (forecastSent.morningBulletinAlreadySentToday(dayArt, u)) {
                if ("startup".equals(mode)) {
                    LOG.info("Forecast catch-up at startup: morning bulletin already current from SMN (updated " + u + ").");
                }
                return false;
            }
            if ("startup".equals(mode)) {
                LOG.info("Forecast catch-up at startup: SMN still on prior bulletin for morning (updated \"" + u + "\"); scheduled poll will retry.");
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "CABA forecast fetch for Telegram failed (" + mode + ")", e);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "CABA forecast Telegram send failed (" + mode + ")", e);
            return true;
        }
    }

    private static boolean eveningForecastTelegramRound(
            SmnClient smn,
            TelegramNotifier telegramForecast,
            SentForecastLog forecastSent,
            LocalDate dayArt,
            String mode,
            ForecastDailyLog forecastHistory,
            String reportHost)
            throws InterruptedException {
        SmnClient.Station caba = cabaStation();
        try {
            SmnClient.ForecastPayload p = smn.fetchForecastPayload(caba);
            String u = p.updated();
            if (u.isBlank()) {
                LOG.fine("CABA forecast (" + mode + "): missing \"updated\", will retry soon");
                return true;
            }
            if (forecastSent.shouldSendEvening(dayArt, u)) {
                telegramForecast.sendHtml(forecastTelegramEnvelope(p.telegramHtml(), reportHost));
                forecastSent.recordEvening(dayArt, u);
                try {
                    forecastHistory.append(
                            ZonedDateTime.now(BUENOS_AIRES),
                            CABA_LOCATION_ID,
                            FH_TG_EVENING,
                            u,
                            reportHost,
                            p.telegramText());
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Forecast history append failed (evening Telegram)", e);
                }
                LOG.info(() -> "Telegram forecast sent for CABA (evening, " + mode + ", updated " + u + ")");
                return false;
            }
            if (forecastSent.eveningBulletinAlreadySentToday(dayArt, u)) {
                if ("startup".equals(mode)) {
                    LOG.info("Forecast catch-up at startup: evening bulletin already current from SMN (updated " + u + ").");
                }
                return false;
            }
            if ("startup".equals(mode)) {
                LOG.info("Forecast catch-up at startup: SMN still on prior bulletin for evening (updated \"" + u + "\"); scheduled poll will retry.");
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "CABA forecast fetch for Telegram failed (" + mode + ")", e);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "CABA forecast Telegram send failed (" + mode + ")", e);
            return true;
        }
    }

    private static SmnClient.Station cabaStation() {
        return new SmnClient.Station(CABA_LOCATION_ID, KNOWN_STATION_NAMES.get(CABA_LOCATION_ID));
    }

    private static boolean stationListIncludesCaba(List<SmnClient.Station> stations) {
        for (SmnClient.Station s : stations) {
            if (s.locationId() == CABA_LOCATION_ID) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shown at the bottom of condition messages as {@code (hostname)}. Order: {@link Config#reportHostLabel()}
     * ({@code smn.report.host} / {@code SMN_REPORT_HOST}), then {@code HOSTNAME} / {@code COMPUTERNAME}, then
     * JVM local host name.
     */
    private static String resolveReportHostLabel(Config config) {
        String v = config.reportHostLabel();
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        String env = firstNonBlank2(System.getenv("HOSTNAME"), System.getenv("COMPUTERNAME"));
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    private static String firstNonBlank2(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private static Path forecastHistoryDirFromEnv() {
        String raw = System.getenv("SMN_FORECAST_HISTORY_DIR");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_FORECAST_HISTORY_DIR);
    }

    private static Path forecastSentPathFromEnv() {
        String raw = System.getenv("SMN_FORECAST_SENT_FILE");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_FORECAST_SENT_FILE);
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
     * Logs every poll where SMN returned an observation we already sent (waiting for {@code date} / instant to
     * advance before mailing again).
     */
    private static void logConditionsCheckedNotUpdatedYet(SmnClient.Station station, SmnClient.Observation o) {
        ZonedDateTime obsArt = o.observationTime().withZoneSameInstant(BUENOS_AIRES);
        ZonedDateTime nowArt = ZonedDateTime.now(BUENOS_AIRES);
        LOG.info(() -> "Conditions check: "
                + station.label()
                + " — SMN observation still "
                + obsArt
                + " (not updated yet); local "
                + nowArt
                + " ART. Next send when JSON \"date\" advances.");
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
