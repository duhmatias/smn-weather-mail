package ar.gob.smn.weather;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private static final String DEFAULT_MEASURES_SUMMARY_SENT = "smn-measures-summary-sent.txt";
    private static final String DEFAULT_FORECAST_VALIDATION_SENT = "smn-forecast-validation-sent.txt";
    private static final String DEFAULT_HISTORICAL_ARCHIVE_SENT = "smn-historical-archive-sent.txt";
    private static final String DEFAULT_HISTORICAL_ARCHIVE_ZIP_DIR = "smn-historical-archives";
    private static final String FH_SRC_EMAIL = "OBS_EMAIL";
    private static final String FH_TG_MORNING = "TG_MORNING";
    private static final String FH_TG_AFTERNOON = "TG_AFTERNOON";
    private static final String FH_TG_EVENING = "TG_EVENING";
    /** CABA — only this location gets forecast in email and scheduled forecast Telegram. */
    private static final int CABA_LOCATION_ID = 4864;
    /**
     * Combined conditions + forecast (CABA): append the next calendar day’s slice only when ART local time is
     * {@code >=} this value (17:00 through 23:59). From midnight (12:00 AM) through 16:59, only the observation day
     * is included; the following-day block is not sent in that window.
     */
    private static final LocalTime COMBINED_FORECAST_NEXT_DAY_FROM_ART = LocalTime.of(17, 0);
    /**
     * Scheduled CABA forecast Telegram: poll for the morning bulletin from this ART time until noon (SMN often
     * posts the morning refresh at or after ~5:30).
     */
    private static final LocalTime MORNING_FORECAST_POLL_START_ART = LocalTime.of(5, 30);
    /**
     * Scheduled CABA forecast Telegram: poll for the evening bulletin from this ART time onward (SMN often posts
     * the evening refresh at or after ~17:30).
     */
    private static final LocalTime EVENING_FORECAST_POLL_START_ART = LocalTime.of(17, 30);
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
                config.telegramForForecast() != null ? new TelegramNotifier(config.telegramForForecast()) : null;
        TelegramNotifier telegramMeasuresSummary =
                config.telegramForMeasuresSummaries() != null
                        ? new TelegramNotifier(config.telegramForMeasuresSummaries())
                        : null;

        List<SmnClient.Station> stations = stationsFromEnv();
        Path sentLogPath = sentLogPathFromEnv();
        SentWeatherLog sent = SentWeatherLog.open(sentLogPath);
        Path forecastSentPath = forecastSentPathFromEnv();
        SentForecastLog forecastSent = SentForecastLog.open(forecastSentPath);
        Map<Integer, ZonedDateTime> lastMailedObsHourArt =
                new HashMap<>(sent.latestMailedObservationHourPerLocation(BUENOS_AIRES));
        Path measuresDir = measuresDirFromEnv();
        Path measuresSummarySentPath = measuresSummarySentPathFromEnv();
        MeasuresDailyLog measures = new MeasuresDailyLog(measuresDir);
        Path forecastHistoryDir = forecastHistoryDirFromEnv();
        ForecastDailyLog forecastHistory = new ForecastDailyLog(forecastHistoryDir);
        ForecastDaySnapshotLog forecastDaySnapshotLog =
                new ForecastDaySnapshotLog(forecastHistoryDir.resolve("forecast-day-snapshots.log"));
        Path forecastValidationSentPath = forecastValidationSentPathFromEnv();
        Path historicalArchiveStatePath = historicalArchiveStatePathFromEnv();
        Path historicalArchiveZipDir = historicalArchiveZipDirFromEnv();
        boolean configIncludesCaba = stationListIncludesCaba(stations);

        LOG.info(() -> "Stations: " + stations);
        LOG.info(() -> "Sent log (dedupe): " + sentLogPath.toAbsolutePath());
        LOG.info(() -> "Forecast Telegram dedupe: " + forecastSentPath.toAbsolutePath());
        LOG.info(() -> "Measures dir: " + measuresDir.toAbsolutePath() + " (YYYY/MM/<day>.txt)");
        LOG.info(() -> "Measures summary dedupe: " + measuresSummarySentPath.toAbsolutePath()
                + " (daily 08:00 ART previous day; Mon 08:00 previous week; 1st 08:00 previous month)");
        LOG.info(() -> "Forecast history dir: " + forecastHistoryDir.toAbsolutePath() + " (YYYY/MM/<day>.txt)");
        LOG.info(() -> "Forecast day snapshots: "
                + forecastHistoryDir.resolve("forecast-day-snapshots.log").toAbsolutePath());
        LOG.info(() -> "Forecast validation dedupe: " + forecastValidationSentPath.toAbsolutePath()
                + " (08:00 ART previous day vs first forecast JSON logged)");
        LOG.info(() -> "Historical month archive: state "
                + historicalArchiveStatePath.toAbsolutePath()
                + " | zip dir "
                + historicalArchiveZipDir.toAbsolutePath()
                + " (ART: on/after 1st, zip month now−2 under measures + forecast-history, delete originals)");
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
        if (telegramMeasuresSummary != null) {
            boolean dedicated = config.telegramSummariesConfig() != null;
            LOG.info(() -> "Telegram (measures summaries): "
                    + config.telegramForMeasuresSummaries().chatIds().size()
                    + " chat(s)"
                    + (dedicated
                            ? " — telegram.summaries.bot.token / TELEGRAM_SUMMARIES_BOT_TOKEN"
                            : " — same bot as conditions (telegram.bot.token)"));
        }

        String reportHost = resolveReportHostLabel(config);
        LOG.info(() -> "Condition messages host footer: " + reportHost + " (smn.report.host / SMN_REPORT_HOST / HOSTNAME)");

        String commandsBotToken = config.telegramCommandsBotToken();
        if (commandsBotToken != null && !commandsBotToken.isBlank()) {
            Thread cmdThread =
                    new Thread(new TelegramBotCommandListener(commandsBotToken, reportHost), "telegram-commands");
            cmdThread.setDaemon(true);
            cmdThread.start();
            LOG.info("Telegram command listener: on (getUpdates /hello → world! + host line)");
        }

        tryCatchUpMissingForecastTelegramOnStartup(
                smn,
                telegramForecast,
                forecastSent,
                configIncludesCaba,
                forecastHistory,
                forecastDaySnapshotLog,
                reportHost);

        tryCatchUpMeasuresSummariesOnStartup(
                measuresDir, measuresSummarySentPath, stations, config, mail, telegramMeasuresSummary, reportHost);

        tryCatchUpForecastValidationOnStartup(
                measuresDir,
                forecastDaySnapshotLog,
                forecastValidationSentPath,
                configIncludesCaba,
                config,
                mail,
                telegramConditions,
                reportHost);

        HistoricalMonthArchive.validateStateOnStartup(historicalArchiveStatePath, BUENOS_AIRES);
        tryHistoricalMonthArchive(measuresDir, forecastHistoryDir, historicalArchiveStatePath, historicalArchiveZipDir);

        while (true) {
            tryHistoricalMonthArchive(measuresDir, forecastHistoryDir, historicalArchiveStatePath, historicalArchiveZipDir);
            trySendMeasuresSummaries(
                    measuresDir, measuresSummarySentPath, stations, config, mail, telegramMeasuresSummary, reportHost);
            trySendForecastValidation(
                    measuresDir,
                    forecastDaySnapshotLog,
                    forecastValidationSentPath,
                    configIncludesCaba,
                    config,
                    mail,
                    telegramConditions,
                    reportHost);
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
                    String forecastUpdatedRaw = null;
                    if (station.locationId() == CABA_LOCATION_ID) {
                        try {
                            forecastPayload = smn.fetchForecastPayload(station);
                            String u = forecastPayload.updated();
                            forecastUpdatedRaw = u != null && !u.isBlank() ? u.trim() : null;
                            LocalDate obsDay =
                                    o.observationTime().withZoneSameInstant(BUENOS_AIRES).toLocalDate();
                            // 17:00–23:59 ART: include obsDay + next day; 00:00–16:59 ART: obsDay only (after midnight, no
                            // following-day slice until 5pm).
                            boolean afterFivePmArt =
                                    !ZonedDateTime.now(BUENOS_AIRES)
                                            .toLocalTime()
                                            .isBefore(COMBINED_FORECAST_NEXT_DAY_FROM_ART);
                            LocalDate secondForecastDay = afterFivePmArt ? obsDay.plusDays(1) : null;
                            forecastBlock =
                                    ForecastFormatter.formatForCalendarDays(
                                            forecastPayload.forecastJson(), obsDay, secondForecastDay);
                            forecastTgDays =
                                    TelegramForecastFormatter.formatTelegramHtmlDaySlices(
                                            forecastPayload.forecastJson(), obsDay, secondForecastDay);
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
                            forecastUpdatedRaw,
                            MailBodyFormatter.BodyTarget.HTML_EMAIL);
                    String bodyTelegram = MailBodyFormatter.formatSingle(
                            o,
                            forecastBlock,
                            forecastTgDays,
                            true,
                            station.locationId(),
                            reportHost,
                            forecastUpdatedRaw,
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
                            appendForecastDaySnapshots(
                                    forecastDaySnapshotLog,
                                    obsArt,
                                    forecastPayload.forecastJson(),
                                    forecastPayload.updated());
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
                            smn,
                            telegramForecast,
                            forecastSent,
                            configIncludesCaba,
                            forecastHistory,
                            forecastDaySnapshotLog,
                            reportHost);
            Thread.sleep(PollCadence.resolve(fastForecastPoll, stations, lastMailedObsHourArt).sleepMillis());
        }
    }

    /**
     * Cadence between full poll cycles (all stations + optional forecast round). Explicit states replace nested
     * conditionals for the “hourly bulletin not in yet” window in ART.
     */
    private enum PollCadence {
        /** CABA forecast morning / afternoon / evening windows: poll every minute until SMN advances {@code updated}. */
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
     * From {@link #MORNING_FORECAST_POLL_START_ART} until noon ART, ensure today's morning bulletin is in
     * {@link SentForecastLog}; from noon until {@link #EVENING_FORECAST_POLL_START_ART}, optionally an afternoon
     * refresh; from {@link #EVENING_FORECAST_POLL_START_ART} onward, today's evening bulletin. Before the morning
     * window opens, catch-up is skipped.
     */
    private static void tryCatchUpMissingForecastTelegramOnStartup(
            SmnClient smn,
            TelegramNotifier telegramForecast,
            SentForecastLog forecastSent,
            boolean configIncludesCaba,
            ForecastDailyLog forecastHistory,
            ForecastDaySnapshotLog forecastDaySnapshotLog,
            String reportHost) {
        if (telegramForecast == null || !configIncludesCaba) {
            LOG.info("Forecast catch-up at startup: skipped (Telegram off or CABA not in SMN_LOCATION_IDS).");
            return;
        }
        ZonedDateTime art = ZonedDateTime.now(BUENOS_AIRES);
        LocalDate dayArt = art.toLocalDate();
        LocalTime t = art.toLocalTime();
        if (t.isBefore(MORNING_FORECAST_POLL_START_ART) && t.isBefore(LocalTime.NOON)) {
            LOG.info(
                    "Forecast catch-up at startup: before "
                            + MORNING_FORECAST_POLL_START_ART
                            + " ART — skipping until morning window.");
            return;
        }
        boolean beforeNoon = t.isBefore(LocalTime.NOON);
        boolean eveningWindowOpen = !t.isBefore(EVENING_FORECAST_POLL_START_ART);
        try {
            if (beforeNoon) {
                if (forecastSent.hasMorningRecordedFor(dayArt)) {
                    LOG.info("Forecast catch-up at startup: today's morning bulletin already recorded for " + dayArt + ".");
                    return;
                }
                LOG.info("Forecast catch-up at startup: today's morning bulletin not on file — attempting fetch.");
                morningForecastTelegramRound(
                        smn,
                        telegramForecast,
                        forecastSent,
                        dayArt,
                        "startup",
                        forecastHistory,
                        forecastDaySnapshotLog,
                        reportHost);
            } else if (!eveningWindowOpen) {
                if (!forecastSent.hasMorningRecordedFor(dayArt)) {
                    LOG.info(
                            "Forecast catch-up at startup: today's morning bulletin not on file — attempting fetch (before evening window).");
                    morningForecastTelegramRound(
                            smn,
                            telegramForecast,
                            forecastSent,
                            dayArt,
                            "startup",
                            forecastHistory,
                            forecastDaySnapshotLog,
                            reportHost);
                } else {
                    LOG.info("Forecast catch-up at startup: afternoon window — checking for newer bulletin than morning.");
                    afternoonForecastTelegramRound(
                            smn,
                            telegramForecast,
                            forecastSent,
                            dayArt,
                            "startup",
                            forecastHistory,
                            forecastDaySnapshotLog,
                            reportHost);
                }
            } else {
                if (forecastSent.hasEveningRecordedFor(dayArt)) {
                    LOG.info("Forecast catch-up at startup: today's evening bulletin already recorded for " + dayArt + ".");
                    return;
                }
                LOG.info("Forecast catch-up at startup: today's evening bulletin not on file — attempting fetch.");
                eveningForecastTelegramRound(
                        smn,
                        telegramForecast,
                        forecastSent,
                        dayArt,
                        "startup",
                        forecastHistory,
                        forecastDaySnapshotLog,
                        reportHost);
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
            ForecastDaySnapshotLog forecastDaySnapshotLog,
            String reportHost)
            throws InterruptedException {
        if (telegramForecast == null || !configIncludesCaba) {
            return false;
        }
        ZonedDateTime art = ZonedDateTime.now(BUENOS_AIRES);
        LocalTime t = art.toLocalTime();
        LocalDate dayArt = art.toLocalDate();
        boolean morningWindow =
                !t.isBefore(MORNING_FORECAST_POLL_START_ART) && t.isBefore(LocalTime.NOON);
        boolean afternoonWindow =
                !t.isBefore(LocalTime.NOON) && t.isBefore(EVENING_FORECAST_POLL_START_ART);
        boolean eveningWindow = !t.isBefore(EVENING_FORECAST_POLL_START_ART);
        if (!morningWindow && !afternoonWindow && !eveningWindow) {
            return false;
        }
        if (morningWindow) {
            return morningForecastTelegramRound(
                    smn,
                    telegramForecast,
                    forecastSent,
                    dayArt,
                    "scheduled",
                    forecastHistory,
                    forecastDaySnapshotLog,
                    reportHost);
        }
        if (afternoonWindow) {
            return afternoonForecastTelegramRound(
                    smn,
                    telegramForecast,
                    forecastSent,
                    dayArt,
                    "scheduled",
                    forecastHistory,
                    forecastDaySnapshotLog,
                    reportHost);
        }
        return eveningForecastTelegramRound(
                smn,
                telegramForecast,
                forecastSent,
                dayArt,
                "scheduled",
                forecastHistory,
                forecastDaySnapshotLog,
                reportHost);
    }

    private static boolean morningForecastTelegramRound(
            SmnClient smn,
            TelegramNotifier telegramForecast,
            SentForecastLog forecastSent,
            LocalDate dayArt,
            String mode,
            ForecastDailyLog forecastHistory,
            ForecastDaySnapshotLog forecastDaySnapshotLog,
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
                boolean morningUpdate = forecastSent.hasMorningRecordedFor(dayArt);
                telegramForecast.sendHtml(forecastTelegramEnvelope(p.telegramHtml(), reportHost));
                forecastSent.recordMorning(dayArt, u);
                try {
                    ZonedDateTime sentArt = ZonedDateTime.now(BUENOS_AIRES);
                    forecastHistory.append(
                            sentArt,
                            CABA_LOCATION_ID,
                            FH_TG_MORNING,
                            u,
                            reportHost,
                            p.telegramText());
                    appendForecastDaySnapshots(
                            forecastDaySnapshotLog, sentArt, p.forecastJson(), p.updated());
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Forecast history append failed (morning Telegram)", e);
                }
                String slot = morningUpdate ? "morning update" : "morning";
                LOG.info(() -> "Telegram forecast sent for CABA (" + slot + ", " + mode + ", updated " + u + ")");
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

    private static boolean afternoonForecastTelegramRound(
            SmnClient smn,
            TelegramNotifier telegramForecast,
            SentForecastLog forecastSent,
            LocalDate dayArt,
            String mode,
            ForecastDailyLog forecastHistory,
            ForecastDaySnapshotLog forecastDaySnapshotLog,
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
            if (forecastSent.shouldSendAfternoonUpdate(dayArt, u)) {
                telegramForecast.sendHtml(forecastTelegramEnvelope(p.telegramHtml(), reportHost));
                forecastSent.recordAfternoon(dayArt, u);
                try {
                    ZonedDateTime sentArt = ZonedDateTime.now(BUENOS_AIRES);
                    forecastHistory.append(
                            sentArt,
                            CABA_LOCATION_ID,
                            FH_TG_AFTERNOON,
                            u,
                            reportHost,
                            p.telegramText());
                    appendForecastDaySnapshots(
                            forecastDaySnapshotLog, sentArt, p.forecastJson(), p.updated());
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Forecast history append failed (afternoon Telegram)", e);
                }
                LOG.info(() -> "Telegram forecast sent for CABA (afternoon update, " + mode + ", updated " + u + ")");
                return false;
            }
            if (forecastSent.afternoonSlotSettled(dayArt, u)) {
                if ("startup".equals(mode)) {
                    LOG.info(
                            "Forecast catch-up at startup: afternoon slot settled (same as morning or latest afternoon, updated "
                                    + u
                                    + ").");
                }
                return false;
            }
            if ("startup".equals(mode)) {
                LOG.info(
                        "Forecast catch-up at startup: SMN bulletin not yet advanced for afternoon (updated \""
                                + u
                                + "\"); scheduled poll will retry.");
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
            ForecastDaySnapshotLog forecastDaySnapshotLog,
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
                    ZonedDateTime sentArt = ZonedDateTime.now(BUENOS_AIRES);
                    forecastHistory.append(
                            sentArt,
                            CABA_LOCATION_ID,
                            FH_TG_EVENING,
                            u,
                            reportHost,
                            p.telegramText());
                    appendForecastDaySnapshots(
                            forecastDaySnapshotLog, sentArt, p.forecastJson(), p.updated());
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

    private static Path measuresSummarySentPathFromEnv() {
        String raw = System.getenv("SMN_MEASURES_SUMMARY_SENT_FILE");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_MEASURES_SUMMARY_SENT);
    }

    private static Path forecastValidationSentPathFromEnv() {
        String raw = System.getenv("SMN_FORECAST_VALIDATION_SENT_FILE");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_FORECAST_VALIDATION_SENT);
    }

    private static Path historicalArchiveStatePathFromEnv() {
        String raw = System.getenv("SMN_HISTORICAL_ARCHIVE_STATE_FILE");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_HISTORICAL_ARCHIVE_SENT);
    }

    private static Path historicalArchiveZipDirFromEnv() {
        String raw = System.getenv("SMN_HISTORICAL_ARCHIVE_ZIP_DIR");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_HISTORICAL_ARCHIVE_ZIP_DIR);
    }

    private static void tryHistoricalMonthArchive(
            Path measuresDir, Path forecastHistoryDir, Path stateFile, Path zipOutputDir) {
        try {
            HistoricalMonthArchive.tryProcess(measuresDir, forecastHistoryDir, stateFile, zipOutputDir, BUENOS_AIRES);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Historical month archive failed: " + e.getMessage(), e);
        }
    }

    /** Appends per-day rows from SMN JSON whenever we log a CABA forecast (email or Telegram). */
    private static void appendForecastDaySnapshots(
            ForecastDaySnapshotLog snapLog,
            ZonedDateTime writtenArt,
            String forecastJson,
            String smnUpdated) {
        try {
            snapLog.append(writtenArt, CABA_LOCATION_ID, forecastJson, smnUpdated != null ? smnUpdated : "");
        } catch (IOException e) {
            LOG.log(Level.FINE, "Forecast day snapshot log: " + e.getMessage(), e);
        }
    }

    private static boolean measuresSummaryScheduledPollWindow(LocalTime lt) {
        return lt.getHour() == 8 && lt.getMinute() <= 29;
    }

    /**
     * After 08:00 ART on startup: send daily / weekly / monthly summaries if their dedupe key for today is not yet in
     * {@code smn-measures-summary-sent.txt} (same keys as the main loop). Before 08:00 ART, skips — the scheduled
     * window will run later.
     */
    private static void tryCatchUpMeasuresSummariesOnStartup(
            Path measuresDir,
            Path summarySentPath,
            List<SmnClient.Station> stations,
            Config config,
            MailSender mail,
            TelegramNotifier telegramMeasuresSummary,
            String reportHost) {
        ZonedDateTime nowArt = ZonedDateTime.now(BUENOS_AIRES);
        LocalTime t = nowArt.toLocalTime();
        if (t.isBefore(LocalTime.of(8, 0))) {
            LOG.info(
                    "Measures summary catch-up at startup: before 08:00 ART — skipping; scheduled send in 08:00–08:29 window.");
            return;
        }
        try {
            MeasuresSummarySentLog sent = MeasuresSummarySentLog.open(summarySentPath);
            runMeasuresSummariesForToday(
                    measuresDir, sent, stations, config, mail, telegramMeasuresSummary, reportHost, "startup catch-up");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Measures summary sent log unreadable at startup: " + summarySentPath, e);
        }
    }

    /**
     * After 08:00 ART on startup: send forecast-vs-observed validation for yesterday if the key for today is not in
     * the dedupe file (same window policy as measures summaries).
     */
    private static void tryCatchUpForecastValidationOnStartup(
            Path measuresDir,
            ForecastDaySnapshotLog snapshotLog,
            Path validationSentPath,
            boolean configIncludesCaba,
            Config config,
            MailSender mail,
            TelegramNotifier telegramConditions,
            String reportHost) {
        if (!configIncludesCaba) {
            return;
        }
        ZonedDateTime nowArt = ZonedDateTime.now(BUENOS_AIRES);
        if (nowArt.toLocalTime().isBefore(LocalTime.of(8, 0))) {
            LOG.info(
                    "Forecast validation catch-up at startup: before 08:00 ART — skipping; scheduled send in 08:00–08:29 window.");
            return;
        }
        try {
            MeasuresSummarySentLog sent = MeasuresSummarySentLog.open(validationSentPath);
            runForecastValidationForToday(
                    measuresDir,
                    snapshotLog,
                    sent,
                    config,
                    mail,
                    telegramConditions,
                    reportHost,
                    "startup catch-up");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Forecast validation sent log unreadable at startup: " + validationSentPath, e);
        }
    }

    /**
     * Daily 08:00–08:29 ART: compare yesterday’s CABA measures (min/max, rain) with the earliest SMN JSON snapshot
     * that included that calendar day (see {@link ForecastDaySnapshotLog}).
     */
    private static void trySendForecastValidation(
            Path measuresDir,
            ForecastDaySnapshotLog snapshotLog,
            Path validationSentPath,
            boolean configIncludesCaba,
            Config config,
            MailSender mail,
            TelegramNotifier telegramConditions,
            String reportHost) {
        if (!configIncludesCaba) {
            return;
        }
        ZonedDateTime nowArt = ZonedDateTime.now(BUENOS_AIRES);
        LocalTime lt = nowArt.toLocalTime();
        if (!measuresSummaryScheduledPollWindow(lt)) {
            return;
        }
        try {
            MeasuresSummarySentLog sent = MeasuresSummarySentLog.open(validationSentPath);
            runForecastValidationForToday(
                    measuresDir,
                    snapshotLog,
                    sent,
                    config,
                    mail,
                    telegramConditions,
                    reportHost,
                    "scheduled");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Forecast validation sent log unreadable: " + validationSentPath, e);
        }
    }

    private static void runForecastValidationForToday(
            Path measuresDir,
            ForecastDaySnapshotLog snapshotLog,
            MeasuresSummarySentLog sent,
            Config config,
            MailSender mail,
            TelegramNotifier telegramConditions,
            String reportHost,
            String logMode) {
        LocalDate today = ZonedDateTime.now(BUENOS_AIRES).toLocalDate();
        String key = "VALIDATION|" + today;
        if (sent.contains(key)) {
            return;
        }
        LocalDate dataDay = today.minusDays(1);
        SmnClient.Station caba = cabaStation();
        try {
            List<MeasuresHistoryReader.MeasureRow> rows =
                    MeasuresHistoryReader.readDay(measuresDir, dataDay, CABA_LOCATION_ID);
            Optional<MeasuresSummaryMessages.TempPeriod> observed = MeasuresSummaryMessages.aggregateTemps(rows);
            boolean observedRain = false;
            for (MeasuresHistoryReader.MeasureRow r : rows) {
                if (MeasuresHistoryReader.looksLikeRain(r.conditions())) {
                    observedRain = true;
                    break;
                }
            }
            Optional<ForecastDaySnapshotLog.SnapshotRow> firstForecast =
                    snapshotLog.findFirstSnapshot(dataDay, CABA_LOCATION_ID);
            String subj = ForecastValidationMessages.subject(dataDay);
            String html =
                    ForecastValidationMessages.buildEmailHtml(
                            caba.label(), dataDay, observed, observedRain, firstForecast, reportHost);
            String tg =
                    ForecastValidationMessages.buildTelegramHtml(
                            caba.label(), dataDay, observed, observedRain, firstForecast, reportHost);
            if (sendForecastValidationMailTelegram(config, mail, telegramConditions, subj, html, tg, reportHost)) {
                try {
                    sent.record(key);
                    if ("startup catch-up".equals(logMode)) {
                        LOG.info(() -> "Forecast validation catch-up at startup: recorded " + key);
                    }
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to record forecast validation key", e);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Forecast validation failed for " + dataDay + ": " + e.getMessage(), e);
        }
    }

    private static boolean sendForecastValidationMailTelegram(
            Config config,
            MailSender mail,
            TelegramNotifier telegramConditions,
            String subject,
            String htmlEmail,
            String telegramHtml,
            String reportHost) {
        String plain =
                MeasuresSummaryMessages.telegramContentAsPlain(telegramHtml == null ? "" : telegramHtml.trim());
        String h = reportHost != null && !reportHost.isBlank() ? reportHost.trim() : "unknown";
        String plainEmail = plain.isEmpty() ? "(" + h + ")" : plain;
        try {
            mail.sendHtmlWithPlain(config.recipients(), subject, plainEmail, htmlEmail);
            LOG.info(() -> "Forecast validation email sent (HTML + plain): " + subject);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Forecast validation email failed: " + subject, e);
            return false;
        }
        if (telegramConditions != null && telegramHtml != null && !telegramHtml.isBlank()) {
            try {
                telegramConditions.sendHtml(telegramHtml.trim());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Forecast validation Telegram failed: " + subject, e);
            }
        }
        return true;
    }

    /**
     * Email + Telegram (conditions bot) summaries from {@link MeasuresDailyLog} files: previous day (daily 08:00
     * ART), previous calendar week Mon–Sun (Mondays 08:00), previous month min/max (1st of month 08:00).
     */
    private static void trySendMeasuresSummaries(
            Path measuresDir,
            Path summarySentPath,
            List<SmnClient.Station> stations,
            Config config,
            MailSender mail,
            TelegramNotifier telegramMeasuresSummary,
            String reportHost) {
        ZonedDateTime nowArt = ZonedDateTime.now(BUENOS_AIRES);
        LocalTime lt = nowArt.toLocalTime();
        if (!measuresSummaryScheduledPollWindow(lt)) {
            return;
        }
        try {
            MeasuresSummarySentLog sent = MeasuresSummarySentLog.open(summarySentPath);
            runMeasuresSummariesForToday(
                    measuresDir, sent, stations, config, mail, telegramMeasuresSummary, reportHost, "scheduled");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Measures summary sent log unreadable: " + summarySentPath, e);
        }
    }

    /**
     * Sends any due summary for {@code today} (ART) if the corresponding {@code DAILY|}/{@code WEEKLY|}/{@code MONTHLY|}
     * key is not in {@code sent}. Caller ensures time policy (startup vs poll window). Records keys only after
     * successful email.
     */
    private static void runMeasuresSummariesForToday(
            Path measuresDir,
            MeasuresSummarySentLog sent,
            List<SmnClient.Station> stations,
            Config config,
            MailSender mail,
            TelegramNotifier telegramMeasuresSummary,
            String reportHost,
            String logMode) {
        LocalDate today = ZonedDateTime.now(BUENOS_AIRES).toLocalDate();
        String dailyKey = "DAILY|" + today;
        if (!sent.contains(dailyKey)) {
            LocalDate dataDay = today.minusDays(1);
            String subj = MeasuresSummaryMessages.dailySubject(dataDay);
            List<String> sections = new ArrayList<>();
            StringBuilder tg = new StringBuilder();
            tg.append(MeasuresSummaryMessages.telegramTitleBold(subj));
            for (SmnClient.Station st : stations) {
                try {
                    List<MeasuresHistoryReader.MeasureRow> rows =
                            MeasuresHistoryReader.readDay(measuresDir, dataDay, st.locationId());
                    Optional<MeasuresSummaryMessages.TempPeriod> agg = MeasuresSummaryMessages.aggregateTemps(rows);
                    if (agg.isPresent()) {
                        sections.add(MeasuresSummaryMessages.formatSectionDaily(st.label(), dataDay, agg.get()));
                        tg.append(MeasuresSummaryMessages.telegramSectionDaily(st.label(), dataDay, agg.get()));
                    } else {
                        sections.add(
                                MeasuresSummaryMessages.sectionNoDataHtml(st.label(), dataDay.toString()));
                        tg.append(MeasuresSummaryMessages.telegramSectionNoData(st.label(), dataDay.toString()));
                    }
                } catch (IOException e) {
                    LOG.log(Level.FINE, "Measures read failed for daily summary " + st, e);
                    sections.add(MeasuresSummaryMessages.sectionNoDataHtml(st.label(), dataDay.toString()));
                    tg.append(MeasuresSummaryMessages.telegramSectionNoData(st.label(), dataDay.toString()));
                }
            }
            tg.append(MeasuresSummaryMessages.telegramHostFooter(reportHost));
            String html = MeasuresSummaryMessages.wrapEmail(subj, sections, reportHost);
            if (sendMeasuresSummaryMailTelegram(
                    config, mail, telegramMeasuresSummary, subj, html, tg.toString(), reportHost)) {
                try {
                    sent.record(dailyKey);
                    logMeasuresSummaryKeyRecorded(logMode, dailyKey);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to record daily measures summary key", e);
                }
            }
        }
        if (today.getDayOfWeek() == DayOfWeek.MONDAY) {
            String weeklyKey = "WEEKLY|" + today;
            if (!sent.contains(weeklyKey)) {
                LocalDate weekEnd = today.minusDays(1);
                LocalDate weekStart = weekEnd.minusDays(6);
                String subj = MeasuresSummaryMessages.weeklySubject(weekStart, weekEnd);
                List<String> sections = new ArrayList<>();
                StringBuilder tg = new StringBuilder();
                tg.append(MeasuresSummaryMessages.telegramTitleBold(subj));
                for (SmnClient.Station st : stations) {
                    try {
                        List<MeasuresHistoryReader.MeasureRow> rows =
                                MeasuresHistoryReader.readInclusive(measuresDir, weekStart, weekEnd, st.locationId());
                        Optional<MeasuresSummaryMessages.TempPeriod> agg = MeasuresSummaryMessages.aggregateTemps(rows);
                        if (agg.isPresent()) {
                            int rainDays = MeasuresHistoryReader.countRainDays(rows);
                            MeasuresHistoryReader.WindMax windMax =
                                    MeasuresHistoryReader.maxWindWithDirection(rows).orElse(null);
                            sections.add(
                                    MeasuresSummaryMessages.formatSectionWeekly(
                                            st.label(), weekStart, weekEnd, agg.get(), rainDays, windMax));
                            tg.append(
                                    MeasuresSummaryMessages.telegramSectionWeekly(
                                            st.label(), weekStart, weekEnd, agg.get(), rainDays, windMax));
                        } else {
                            sections.add(
                                    MeasuresSummaryMessages.sectionNoDataHtml(
                                            st.label(), weekStart + " – " + weekEnd));
                            tg.append(
                                    MeasuresSummaryMessages.telegramSectionNoData(
                                            st.label(), weekStart + " – " + weekEnd));
                        }
                    } catch (IOException e) {
                        LOG.log(Level.FINE, "Measures read failed for weekly summary " + st, e);
                        sections.add(
                                MeasuresSummaryMessages.sectionNoDataHtml(
                                        st.label(), weekStart + " – " + weekEnd));
                        tg.append(
                                MeasuresSummaryMessages.telegramSectionNoData(
                                        st.label(), weekStart + " – " + weekEnd));
                    }
                }
                tg.append(MeasuresSummaryMessages.telegramHostFooter(reportHost));
                String html = MeasuresSummaryMessages.wrapEmail(subj, sections, reportHost);
                if (sendMeasuresSummaryMailTelegram(
                        config, mail, telegramMeasuresSummary, subj, html, tg.toString(), reportHost)) {
                    try {
                        sent.record(weeklyKey);
                        logMeasuresSummaryKeyRecorded(logMode, weeklyKey);
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "Failed to record weekly measures summary key", e);
                    }
                }
            }
        }
        if (today.getDayOfMonth() == 1) {
            String monthlyKey = "MONTHLY|" + today;
            if (!sent.contains(monthlyKey)) {
                YearMonth prevYm = YearMonth.from(today).minusMonths(1);
                LocalDate mStart = prevYm.atDay(1);
                LocalDate mEnd = prevYm.atEndOfMonth();
                int y = prevYm.getYear();
                int mv = prevYm.getMonthValue();
                String subj = MeasuresSummaryMessages.monthlySubject(y, mv);
                List<String> sections = new ArrayList<>();
                StringBuilder tg = new StringBuilder();
                tg.append(MeasuresSummaryMessages.telegramTitleBold(subj));
                for (SmnClient.Station st : stations) {
                    try {
                        List<MeasuresHistoryReader.MeasureRow> rows =
                                MeasuresHistoryReader.readInclusive(measuresDir, mStart, mEnd, st.locationId());
                        Optional<Double> minM = MeasuresSummaryMessages.monthMinTemp(rows);
                        Optional<Double> maxM = MeasuresSummaryMessages.monthMaxTemp(rows);
                        if (minM.isPresent() && maxM.isPresent()) {
                            sections.add(
                                    MeasuresSummaryMessages.formatSectionMonthly(
                                            st.label(), y, mv, minM.get(), maxM.get()));
                            tg.append(
                                    MeasuresSummaryMessages.telegramSectionMonthly(
                                            st.label(), y, mv, minM.get(), maxM.get()));
                        } else {
                            sections.add(
                                    MeasuresSummaryMessages.sectionNoDataHtml(
                                            st.label(), mv + "/" + y));
                            tg.append(MeasuresSummaryMessages.telegramSectionNoData(st.label(), mv + "/" + y));
                        }
                    } catch (IOException e) {
                        LOG.log(Level.FINE, "Measures read failed for monthly summary " + st, e);
                        sections.add(MeasuresSummaryMessages.sectionNoDataHtml(st.label(), mv + "/" + y));
                        tg.append(MeasuresSummaryMessages.telegramSectionNoData(st.label(), mv + "/" + y));
                    }
                }
                tg.append(MeasuresSummaryMessages.telegramHostFooter(reportHost));
                String html = MeasuresSummaryMessages.wrapEmail(subj, sections, reportHost);
                if (sendMeasuresSummaryMailTelegram(
                        config, mail, telegramMeasuresSummary, subj, html, tg.toString(), reportHost)) {
                    try {
                        sent.record(monthlyKey);
                        logMeasuresSummaryKeyRecorded(logMode, monthlyKey);
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "Failed to record monthly measures summary key", e);
                    }
                }
            }
        }
    }

    private static void logMeasuresSummaryKeyRecorded(String logMode, String key) {
        if (!"startup catch-up".equals(logMode)) {
            return;
        }
        LOG.info(() -> "Measures summary catch-up at startup: recorded " + key);
    }

    private static boolean sendMeasuresSummaryMailTelegram(
            Config config,
            MailSender mail,
            TelegramNotifier telegramMeasuresSummary,
            String subject,
            String htmlEmail,
            String telegramHtml,
            String reportHost) {
        String plain =
                MeasuresSummaryMessages.telegramContentAsPlain(telegramHtml == null ? "" : telegramHtml.trim());
        String h = reportHost != null && !reportHost.isBlank() ? reportHost.trim() : "unknown";
        String plainEmail = plain.isEmpty() ? "(" + h + ")" : plain;
        try {
            mail.sendHtmlWithPlain(config.recipients(), subject, plainEmail, htmlEmail);
            LOG.info(() -> "Measures summary email sent (HTML + plain): " + subject);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Measures summary email failed: " + subject, e);
            return false;
        }
        if (telegramMeasuresSummary != null && telegramHtml != null && !telegramHtml.isBlank()) {
            try {
                telegramMeasuresSummary.sendHtml(telegramHtml.trim());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Measures summary Telegram failed: " + subject, e);
            }
        }
        return true;
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
