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
import java.util.Arrays;
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
    /** Startup Telegram timestamps (Buenos Aires civil time). */
    private static final DateTimeFormatter STARTUP_ART_DISPLAY =
            DateTimeFormatter.ofPattern("dd-MM-yy HH:mm 'ART'", Locale.ROOT);
    private static final DateTimeFormatter STARTUP_ART_DAY = DateTimeFormatter.ofPattern("dd-MM-yy", Locale.ROOT);

    private static final String DEFAULT_SENT_LOG = "smn-weather-sent.txt";
    private static final String DEFAULT_FORECAST_SENT_FILE = "smn-forecast-sent.txt";
    private static final String DEFAULT_MEASURES_DIR = "smn-measures";
    private static final String DEFAULT_FORECAST_HISTORY_DIR = "smn-forecast-history";
    private static final String DEFAULT_MEASURES_SUMMARY_SENT = "smn-measures-summary-sent.txt";
    private static final String DEFAULT_FORECAST_VALIDATION_SENT = "smn-forecast-validation-sent.txt";
    private static final String DEFAULT_TIEPRE_EXTREMA_STATE_FILE = "smn-tiepre-extrema-state.txt";
    private static final String DEFAULT_TIEPRE_EXTREMA_HISTORY_DIR = "smn-tiepre-extrema-history";
    private static final String DEFAULT_TIEPRE_EXTREMA_DAILY_SENT = "smn-tiepre-extrema-daily-sent.txt";
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

    public static void main(String[] args) throws Exception {
        if (wantsTiepreExtremaCli(args)) {
            runTiepreExtremaCli();
            return;
        }
        String cliCurrent = parseCurrentCliQuery(args);
        if (cliCurrent != null) {
            if (cliCurrent.isEmpty()) {
                System.err.println(
                        "Usage (same config.properties / env as the service):"
                                + System.lineSeparator()
                                + "  ./run.sh current=Salta"
                                + System.lineSeparator()
                                + "  java -Dcurrent=Salta -jar target/smn-weather-mail-<version>.jar"
                                + System.lineSeparator()
                                + "  JAR=$(ls -t target/smn-weather-mail-*.jar | head -n1); java -cp \"$JAR:target/lib/*\" "
                                + "ar.gob.smn.weather.WeatherMailApplication current=Salta"
                                + System.lineSeparator()
                                + "  ./run.sh extreme   (or: add extreme, tiepre-extrema — one-shot tiepre extrema Telegram/email; "
                                + "requires tiepre.extrema.enabled)"
                                + System.lineSeparator()
                                + "(Do not put *.jar inside double quotes — the shell must expand the glob.)");
                System.exit(2);
            }
            runCurrentCli(cliCurrent);
            return;
        }

        Config config = Config.load();
        SmnClient smn = new SmnClient(config.smnWsCookieHeader());
        MailSender mail = new MailSender(config);
        TelegramNotifier telegramConditions =
                config.telegram() != null
                        ? new TelegramNotifier(config.telegram(), config.telegramConditionsSubscriberChatsFile())
                        : null;
        TelegramNotifier telegramForecastValidation =
                config.telegramForValidation() != null
                        ? new TelegramNotifier(config.telegramForValidation())
                        : null;
        TelegramNotifier telegramForecast =
                config.telegramForForecast() != null ? new TelegramNotifier(config.telegramForForecast()) : null;
        TelegramNotifier telegramMeasuresSummary =
                config.telegramForMeasuresSummaries() != null
                        ? new TelegramNotifier(config.telegramForMeasuresSummaries())
                        : null;

        List<SmnClient.Station> stations = config.smnWeatherLocations();
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
        Path tiepreExtremaStatePath = tiepreExtremaStatePathFromEnv();
        Path tiepreExtremaHistoryDir = tiepreExtremaHistoryDirFromEnv();
        TiepreExtremaHistoryLog tiepreExtremaHistory = new TiepreExtremaHistoryLog(tiepreExtremaHistoryDir);
        Path tiepreExtremaDailySentPath = tiepreExtremaDailySentPathFromEnv();
        long[] tiepreExtremaLastHourSlot = {-1L};
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
                + " (08:00 ART previous day vs all forecast JSON snapshots logged for that day)");
        LOG.info(() -> "Historical month archive: state "
                + historicalArchiveStatePath.toAbsolutePath()
                + " | zip dir "
                + historicalArchiveZipDir.toAbsolutePath()
                + " (ART: on/after 1st, zip month now−2 under measures + forecast-history, delete originals)");
        LOG.info(() -> "Recipients: " + config.recipients() + " | zone: " + BUENOS_AIRES + " | poll: " + POLL_INTERVAL
                + " (2m through :19, 5m from :20 if hourly bulletin still pending) | pause between stations: "
                + SLEEP_BETWEEN_STATIONS);
        if (config.tiepreExtremaEnabled()) {
            LOG.info(
                    () -> "Tiepre extrema (:30 ART hourly, open-data tiepre file): state "
                            + tiepreExtremaStatePath.toAbsolutePath()
                            + " | history dir "
                            + tiepreExtremaHistoryDir.toAbsolutePath()
                            + " (YYYY/MM/<day>.txt) | daily 08:00 ART dedupe "
                            + tiepreExtremaDailySentPath.toAbsolutePath()
                            + " | Telegram chats="
                            + (config.telegramForTiepreExtrema() != null
                                    ? config.telegramForTiepreExtrema().chatIds().size()
                                    : 0)
                            + " | email="
                            + config.tiepreExtremaSendEmail());
        }
        if (telegramForecastValidation != null) {
            boolean valDedicated = config.telegramValidationConfig() != null;
            LOG.info(() -> "Telegram (forecast validation): "
                    + config.telegramForValidation().chatIds().size()
                    + " chat(s)"
                    + (valDedicated
                            ? " — telegram.validation.bot.token / TELEGRAM_VALIDATION_BOT_TOKEN"
                            : " — same bot as conditions (telegram.bot.token)"));
        }
        if (telegramConditions != null) {
            LOG.info(() -> "Telegram (conditions): " + config.telegram().chatIds().size() + " chat(s) in config"
                    + (config.telegramConditionsSubscriberChatsFile() != null
                            ? " + subscriber file " + config.telegramConditionsSubscriberChatsFile()
                            : ""));
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
        if (config.telegramStartup() != null) {
            Config.Telegram su = config.telegramStartup();
            boolean otherBot =
                    config.telegram() == null || !su.botToken().equals(config.telegram().botToken());
            LOG.info(() -> "Telegram (startup message): "
                    + su.chatIds().size()
                    + " chat(s)"
                    + (otherBot
                            ? " — telegram.startup.bot.token / TELEGRAM_STARTUP_BOT_TOKEN"
                            : " — same bot as conditions (telegram.bot.token)"));
        }

        String reportHost = resolveReportHostLabel(config);
        LOG.info(() -> "Condition messages host footer: " + reportHost + " (smn.report.host / SMN_REPORT_HOST / HOSTNAME)");

        trySendStartupTelegram(
                config,
                reportHost,
                sent,
                forecastSent,
                forecastDaySnapshotLog,
                forecastValidationSentPath,
                stations);

        String commandsBotToken = config.telegramCommandsBotToken();
        if (commandsBotToken != null && !commandsBotToken.isBlank()) {
            Thread cmdThread =
                    new Thread(
                            new TelegramBotCommandListener(
                                    commandsBotToken,
                                    reportHost,
                                    config.telegramCurrentConditionsBotToken(),
                                    smn,
                                    config.smnWeatherLocations(),
                                    config.telegramConditionsSubscriberChatsFile(),
                                    config.telegram() != null ? config.telegram().chatIds() : List.of()),
                            "telegram-commands");
            cmdThread.setDaemon(true);
            cmdThread.start();
            LOG.info(
                    "Telegram command listener: on (getUpdates /hello; /subscribe|unsubscribe current [day-schedule]; /current → buscador SMN si"
                            + " telegram.current.bot.token está definido)");
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
                telegramForecastValidation,
                reportHost);

        tryCatchUpTiepreExtremaDailySummaryOnStartup(
                config, mail, tiepreExtremaHistory, tiepreExtremaDailySentPath, reportHost);

        HistoricalMonthArchive.validateStateOnStartup(historicalArchiveStatePath, BUENOS_AIRES);
        tryHistoricalMonthArchive(measuresDir, forecastHistoryDir, historicalArchiveStatePath, historicalArchiveZipDir);

        while (true) {
            tryHistoricalMonthArchive(measuresDir, forecastHistoryDir, historicalArchiveStatePath, historicalArchiveZipDir);
            tryTiepreExtremaHalfHourly(
                    config,
                    smn,
                    mail,
                    tiepreExtremaStatePath,
                    tiepreExtremaHistory,
                    reportHost,
                    tiepreExtremaLastHourSlot);
            tryTiepreExtremaDailySummary(
                    config, mail, tiepreExtremaHistory, tiepreExtremaDailySentPath, reportHost);
            trySendMeasuresSummaries(
                    measuresDir, measuresSummarySentPath, stations, config, mail, telegramMeasuresSummary, reportHost);
            trySendForecastValidation(
                    measuresDir,
                    forecastDaySnapshotLog,
                    forecastValidationSentPath,
                    configIncludesCaba,
                    config,
                    mail,
                    telegramForecastValidation,
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
                            telegramConditions.sendConditionHtml(bodyTelegram, station.locationId());
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
            LOG.info("Forecast catch-up at startup: skipped (Telegram off or CABA not in smn.location.ids / SMN_LOCATION_IDS).");
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
        return new SmnClient.Station(CABA_LOCATION_ID, "Ciudad Autónoma de Buenos Aires");
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
     * Non-{@code null} when the user wants a one-shot “current conditions” run instead of the long-running service:
     * {@code -Dcurrent=…} or program args {@code current=…} / {@code current …}.
     */
    private static String parseCurrentCliQuery(String[] args) {
        String prop = System.getProperty("current");
        if (prop != null) {
            return prop.trim();
        }
        if (args.length >= 1) {
            if (args[0].startsWith("current=")) {
                return args[0].substring("current=".length()).trim();
            }
            if ("current".equalsIgnoreCase(args[0])) {
                if (args.length < 2) {
                    return "";
                }
                return String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            }
        }
        return null;
    }

    /**
     * One-shot tiepre extrema send (same payload as the hourly job): {@code extreme}, {@code tiepre-extrema},
     * {@code add extreme}, or {@code -Dextreme=true}. Dedupe vs last fingerprint is skipped so a send is always
     * attempted; fingerprint is still updated after a successful send.
     */
    private static boolean wantsTiepreExtremaCli(String[] args) {
        String prop = System.getProperty("extreme");
        if (prop != null && !prop.isBlank()) {
            String p = prop.trim();
            if (p.equalsIgnoreCase("true") || "1".equals(p) || p.equalsIgnoreCase("yes")) {
                return true;
            }
        }
        if (args.length >= 1) {
            String a0 = args[0].trim();
            if ("extreme".equalsIgnoreCase(a0)
                    || "extremes".equalsIgnoreCase(a0)
                    || "tiepre-extrema".equalsIgnoreCase(a0)
                    || "tiepre_extrema".equalsIgnoreCase(a0)) {
                return true;
            }
            if (args.length >= 2
                    && "add".equalsIgnoreCase(args[0].trim())
                    && "extreme".equalsIgnoreCase(args[1].trim())) {
                return true;
            }
        }
        return false;
    }

    private static void runTiepreExtremaCli() throws Exception {
        Config config = Config.load();
        if (!config.tiepreExtremaEnabled()) {
            System.err.println(
                    "Set tiepre.extrema.enabled=true (and telegram.tiepre.extrema.chat.ids / mail) in config, or "
                            + "TIEPRE_EXTREMA_ENABLED=1.");
            System.exit(2);
        }
        if (config.telegramForTiepreExtrema() == null && !config.tiepreExtremaSendEmail()) {
            System.err.println(
                    "Tiepre extrema CLI needs at least one outlet: configure telegram.tiepre.extrema.chat.ids or set "
                            + "tiepre.extrema.email=true with mail.to recipients.");
            System.exit(2);
        }
        LOG.info("CLI tiepre extrema: one-shot (fingerprint dedupe skipped; state file updated after successful send)");
        SmnClient smn = new SmnClient(config.smnWsCookieHeader());
        MailSender mail = new MailSender(config);
        Path statePath = tiepreExtremaStatePathFromEnv();
        TiepreExtremaHistoryLog historyLog = new TiepreExtremaHistoryLog(tiepreExtremaHistoryDirFromEnv());
        String reportHost = resolveReportHostLabel(config);
        TiepreExtremaDeliver d =
                deliverTiepreExtremaOnce(config, smn, mail, statePath, historyLog, reportHost, true);
        switch (d) {
            case SENT:
                System.out.println("Tiepre extrema: sent (Telegram and/or email).");
                return;
            case SKIPPED_UNCHANGED:
                // Not used when ignoreDedupe=true
                System.out.println("Tiepre extrema: skipped unchanged.");
                return;
            case NO_TIEPRE_BODY:
                System.err.println("Tiepre extrema: SMN open-data tiepre file missing or empty.");
                System.exit(1);
                return;
            case NO_TEMPERATURE_DATA:
                System.err.println("Tiepre extrema: no numeric temperatures in tiepre file.");
                System.exit(1);
                return;
            case IO_ERROR:
                System.err.println("Tiepre extrema: I/O error (see logs).");
                System.exit(1);
                return;
            case SEND_FAILED:
                System.err.println("Tiepre extrema: Telegram and/or email send failed (see logs).");
                System.exit(1);
                return;
            case INTERRUPTED:
                System.err.println("Tiepre extrema: interrupted.");
                System.exit(130);
                break;
            default:
                System.err.println("Tiepre extrema: unexpected outcome " + d);
                System.exit(1);
        }
    }

    private enum TiepreExtremaDeliver {
        SENT,
        SKIPPED_UNCHANGED,
        NO_TIEPRE_BODY,
        NO_TEMPERATURE_DATA,
        IO_ERROR,
        SEND_FAILED,
        INTERRUPTED
    }

    /**
     * Fetches tiepre, computes extrema, optionally compares to {@code statePath} fingerprint, sends Telegram/email,
     * writes fingerprint on full success.
     *
     * @param ignoreDedupe when {@code true} (CLI), skip reading state and never return {@link TiepreExtremaDeliver#SKIPPED_UNCHANGED}.
     */
    private static TiepreExtremaDeliver deliverTiepreExtremaOnce(
            Config config,
            SmnClient smn,
            MailSender mail,
            Path statePath,
            TiepreExtremaHistoryLog historyLog,
            String reportHost,
            boolean ignoreDedupe) {
        Optional<String> bodyOpt;
        try {
            bodyOpt = smn.fetchTieprePlainText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TiepreExtremaDeliver.INTERRUPTED;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Tiepre extrema: tiepre fetch failed: " + e.getMessage(), e);
            return TiepreExtremaDeliver.IO_ERROR;
        }
        if (bodyOpt.isEmpty()) {
            return TiepreExtremaDeliver.NO_TIEPRE_BODY;
        }
        List<SmnClient.Observation> all = TiepreOpenData.allObservations(bodyOpt.get());
        ZonedDateTime art = ZonedDateTime.now(BUENOS_AIRES);
        Optional<TiepreExtremaHourly.Snapshot> snapOpt =
                TiepreExtremaHourly.compute(all, config.tiepreExtremaExcludeStations(), art.toInstant());
        if (snapOpt.isEmpty()) {
            return TiepreExtremaDeliver.NO_TEMPERATURE_DATA;
        }
        TiepreExtremaHourly.Snapshot snap = snapOpt.get();
        String fp = TiepreExtremaHourly.fingerprint(snap);
        if (!ignoreDedupe) {
            Optional<String> prev;
            try {
                prev = TiepreExtremaState.readLastFingerprint(statePath);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Tiepre extrema: cannot read state file: " + e.getMessage(), e);
                return TiepreExtremaDeliver.IO_ERROR;
            }
            if (prev.isPresent() && prev.get().equals(fp)) {
                return TiepreExtremaDeliver.SKIPPED_UNCHANGED;
            }
        }

        Config.Telegram tg = config.telegramForTiepreExtrema();
        boolean needTg = tg != null;
        boolean needMail = config.tiepreExtremaSendEmail();
        boolean tgOk = !needTg;
        if (needTg) {
            try {
                new TelegramNotifier(tg)
                        .sendHtml(
                                TiepreExtremaHourly.formatTelegramHtml(
                                        snap,
                                        BUENOS_AIRES,
                                        reportHost,
                                        config.tiepreExtremaExcludeStationsInMessage()));
                tgOk = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TiepreExtremaDeliver.INTERRUPTED;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Tiepre extrema: Telegram send failed: " + e.getMessage(), e);
                tgOk = false;
            }
        }
        boolean mailOk = !needMail;
        if (needMail) {
            try {
                String subj =
                        "Datos extremos horarios — "
                                + art.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm 'ART'", Locale.ROOT));
                mail.send(
                        config.recipients(),
                        subj,
                        TiepreExtremaHourly.formatEmailHtml(
                                snap, BUENOS_AIRES, reportHost, config.tiepreExtremaExcludeStationsInMessage()));
                mailOk = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TiepreExtremaDeliver.INTERRUPTED;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Tiepre extrema: email send failed: " + e.getMessage(), e);
                mailOk = false;
            }
        }
        if (!tgOk || !mailOk) {
            return TiepreExtremaDeliver.SEND_FAILED;
        }
        try {
            TiepreExtremaState.writeFingerprint(statePath, fp);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Tiepre extrema: cannot write state file: " + e.getMessage(), e);
            return TiepreExtremaDeliver.IO_ERROR;
        }
        if (historyLog != null) {
            try {
                historyLog.append(snap, art.toInstant());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Tiepre extrema: history append failed: " + e.getMessage(), e);
            }
        }
        return TiepreExtremaDeliver.SENT;
    }

    /** Loads config, runs the same logic as Telegram {@code /current}, prints to stdout, exits (via {@code main}). */
    private static void runCurrentCli(String query) throws IOException, InterruptedException {
        Config config = Config.load();
        LOG.info("CLI current: query=«" + query + "» (single run; no mail/Telegram service)");
        SmnClient smn = new SmnClient(config.smnWsCookieHeader());
        String reportHost = resolveReportHostLabel(config);
        CurrentConditionsQuery.Result r =
                CurrentConditionsQuery.run(query, smn, reportHost, config.smnWeatherLocations());
        System.out.println(r.allText());
        LOG.info("CLI current: finished");
    }

    private static String formatStartupZonedArt(ZonedDateTime z) {
        return z.withZoneSameInstant(BUENOS_AIRES).format(STARTUP_ART_DISPLAY);
    }

    /** Minimal HTML escape for Telegram {@code parse_mode=HTML} dynamic segments. */
    private static String escapeTelegramHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Strip startup {@code <b>} tags and reverse {@link #escapeTelegramHtml} for a plain-text Telegram retry. */
    private static String startupHtmlToPlain(String html) {
        if (html == null) {
            return "";
        }
        String s = html.replace("<b>", "").replace("</b>", "");
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    /**
     * Sends a short HTML status to {@link Config#telegramStartup()} when set (env {@code TELEGRAM_STARTUP_BOT_TOKEN}
     * + {@code TELEGRAM_STARTUP_CHAT_IDS} or {@code telegram.startup.*} in config): version, host, last forecast log,
     * last CABA Telegram bulletin, last mailed observations per station, and whether today’s validation key is present.
     */
    private static void trySendStartupTelegram(
            Config config,
            String reportHost,
            SentWeatherLog sent,
            SentForecastLog forecastSent,
            ForecastDaySnapshotLog forecastDaySnapshotLog,
            Path forecastValidationSentPath,
            List<SmnClient.Station> stations) {
        Config.Telegram t = config.telegramStartup();
        if (t == null) {
            LOG.info(
                    "Startup Telegram: off — no startup routing configured. Options: (1) telegram.startup.chat.ids / "
                            + "TELEGRAM_STARTUP_CHAT_IDS, (2) telegram.startup.use.main.chats=true / "
                            + "TELEGRAM_STARTUP_USE_MAIN_CHATS=1 to reuse condition Telegram chats, (3) dedicated "
                            + "telegram.startup.bot.token. Main telegram.chat.ids alone does not send startup.");
            return;
        }
        LOG.info(() -> "Startup Telegram: sending one message to " + t.chatIds().size() + " chat id(s).");
        String version = AppVersion.implementationVersion();
        String nl = "\n";
        StringBuilder msg = new StringBuilder();
        msg.append("<b>App started</b>").append(nl);
        msg.append("<b>version</b> ").append(escapeTelegramHtml(version)).append(nl);
        msg.append("<b>host</b> ").append(escapeTelegramHtml(reportHost)).append(nl);

        try {
            Optional<ZonedDateTime> snap = forecastDaySnapshotLog.latestSnapshotWrittenArt();
            msg.append("<b>Última descarga guardada (forecast-day-snapshots.log):</b> ")
                    .append(escapeTelegramHtml(
                            snap.map(WeatherMailApplication::formatStartupZonedArt).orElse("ninguna aún")))
                    .append(nl);
        } catch (IOException e) {
            msg.append("<b>Última descarga guardada (forecast-day-snapshots.log):</b> error — ")
                    .append(escapeTelegramHtml(e.getMessage()))
                    .append(nl);
        }

        String bulletinLine = forecastSent.startupSummarySpanish();
        String bulletinPrefix = "Último pronóstico CABA (Telegram): ";
        if (bulletinLine.startsWith(bulletinPrefix)) {
            msg.append("<b>Último pronóstico CABA (Telegram):</b> ")
                    .append(escapeTelegramHtml(bulletinLine.substring(bulletinPrefix.length())))
                    .append(nl);
        } else {
            msg.append(escapeTelegramHtml(bulletinLine)).append(nl);
        }

        if (stations.isEmpty()) {
            msg.append("<b>Últimas condiciones enviadas por correo (obs. SMN):</b> ")
                    .append(escapeTelegramHtml("sin estaciones (smn.location.ids / SMN_LOCATION_IDS)."))
                    .append(nl);
        } else {
            StringBuilder cur = new StringBuilder();
            for (SmnClient.Station s : stations) {
                if (cur.length() > 0) {
                    cur.append(" | ");
                }
                Optional<Instant> ins = sent.latestMailedObservationInstant(s.locationId());
                if (ins.isPresent()) {
                    String ts = formatStartupZonedArt(ins.get().atZone(BUENOS_AIRES));
                    cur.append(s.locationId()).append(" ").append(ts);
                } else {
                    cur.append(s.locationId()).append(" ninguna aún");
                }
            }
            msg.append("<b>Últimas condiciones enviadas por correo (obs. SMN):</b> ")
                    .append(escapeTelegramHtml(cur.toString()))
                    .append(nl);
        }

        LocalDate todayArt = ZonedDateTime.now(BUENOS_AIRES).toLocalDate();
        msg.append("<b>Validación pronóstico vs observado (ART hoy ")
                .append(escapeTelegramHtml(todayArt.format(STARTUP_ART_DAY)))
                .append("):</b> ");
        try {
            MeasuresSummarySentLog v = MeasuresSummarySentLog.open(forecastValidationSentPath);
            if (v.contains("VALIDATION|" + todayArt)) {
                msg.append(escapeTelegramHtml("ya enviada hoy"));
            } else {
                msg.append(escapeTelegramHtml("aún no enviada hoy (habitual 08:00–08:29 ART)"));
            }
        } catch (IOException e) {
            msg.append(escapeTelegramHtml("no se pudo leer "))
                    .append(escapeTelegramHtml(forecastValidationSentPath.getFileName().toString()))
                    .append(escapeTelegramHtml(": "))
                    .append(escapeTelegramHtml(e.getMessage()));
        }

        String htmlBody = msg.toString();
        boolean delivered = false;
        try {
            new TelegramNotifier(t).sendHtml(htmlBody);
            LOG.info("Startup Telegram: message sent (telegram.startup.* / TELEGRAM_STARTUP_*).");
            delivered = true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Startup Telegram HTML send failed: " + e.getMessage(), e);
            try {
                new TelegramNotifier(t).sendPlainText(startupHtmlToPlain(htmlBody));
                LOG.info("Startup Telegram: delivered as plain text after HTML failure (check bold/parse_mode).");
                delivered = true;
            } catch (IOException e2) {
                LOG.log(Level.WARNING, "Startup Telegram plain-text retry also failed: " + e2.getMessage(), e2);
            } catch (InterruptedException e2) {
                Thread.currentThread().interrupt();
                LOG.log(Level.WARNING, "Startup Telegram plain-text retry interrupted", e2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "Startup Telegram HTML send interrupted", e);
        }
        if (!delivered) {
            LOG.severe(
                    "Startup Telegram: startup message was not delivered ("
                            + t.chatIds().size()
                            + " chat id(s) configured; telegram.startup.* / TELEGRAM_STARTUP_*). See WARNING logs above.");
        }
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

    private static Path tiepreExtremaStatePathFromEnv() {
        String raw = System.getenv("SMN_TIEPRE_EXTREMA_STATE_FILE");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_TIEPRE_EXTREMA_STATE_FILE);
    }

    private static Path tiepreExtremaHistoryDirFromEnv() {
        String raw = System.getenv("SMN_TIEPRE_EXTREMA_HISTORY_DIR");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_TIEPRE_EXTREMA_HISTORY_DIR);
    }

    private static Path tiepreExtremaDailySentPathFromEnv() {
        String raw = System.getenv("SMN_TIEPRE_EXTREMA_DAILY_SENT_FILE");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of(DEFAULT_TIEPRE_EXTREMA_DAILY_SENT);
    }

    /**
     * Once per ART clock hour from minute 30 onward: scan SMN open-data tiepre (same source as {@code /current}),
     * compute Argentina-wide max/min temperature and strongest numeric wind, then Telegram and/or email unless the
     * snapshot matches the last sent fingerprint.
     */
    private static void tryTiepreExtremaHalfHourly(
            Config config,
            SmnClient smn,
            MailSender mail,
            Path statePath,
            TiepreExtremaHistoryLog historyLog,
            String reportHost,
            long[] lastHourEpochSlot) {
        if (!config.tiepreExtremaEnabled()) {
            return;
        }
        ZonedDateTime art = ZonedDateTime.now(BUENOS_AIRES);
        if (art.getMinute() < 30) {
            return;
        }
        long slot = art.toInstant().getEpochSecond() / 3600L;
        if (slot == lastHourEpochSlot[0]) {
            return;
        }

        TiepreExtremaDeliver d =
                deliverTiepreExtremaOnce(config, smn, mail, statePath, historyLog, reportHost, false);
        switch (d) {
            case SENT:
                LOG.info("Tiepre extrema hourly: sent (fingerprint updated)");
                lastHourEpochSlot[0] = slot;
                return;
            case SKIPPED_UNCHANGED:
                LOG.info("Tiepre extrema: snapshot unchanged vs last send — skip");
                lastHourEpochSlot[0] = slot;
                return;
            case NO_TIEPRE_BODY:
                LOG.warning("Tiepre extrema: open-data tiepre file missing or empty");
                lastHourEpochSlot[0] = slot;
                return;
            case NO_TEMPERATURE_DATA:
                LOG.info("Tiepre extrema: no numeric temperatures in tiepre file");
                lastHourEpochSlot[0] = slot;
                return;
            case IO_ERROR:
            case SEND_FAILED:
            case INTERRUPTED:
            default:
                return;
        }
    }

    /**
     * Daily 08:00–08:29 ART tiepre extrema summary for the previous Buenos Aires calendar day. Reads
     * {@link TiepreExtremaHistoryLog} for that day, picks the absolute max temp, min temp and max wind across all hourly
     * records, sends Telegram (when {@link Config#telegramForTiepreExtrema()} is set) and email (when
     * {@link Config#tiepreExtremaSendEmail()} and there are recipients), records dedupe key {@code TIEPRE_DAILY|<day>}.
     */
    private static void tryTiepreExtremaDailySummary(
            Config config,
            MailSender mail,
            TiepreExtremaHistoryLog historyLog,
            Path dailySentPath,
            String reportHost) {
        if (!config.tiepreExtremaEnabled()) {
            return;
        }
        ZonedDateTime nowArt = ZonedDateTime.now(BUENOS_AIRES);
        if (!measuresSummaryScheduledPollWindow(nowArt.toLocalTime())) {
            return;
        }
        try {
            MeasuresSummarySentLog sent = MeasuresSummarySentLog.open(dailySentPath);
            runTiepreExtremaDailySummaryForToday(
                    config, mail, historyLog, sent, reportHost, "scheduled");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Tiepre extrema daily sent log unreadable: " + dailySentPath, e);
        }
    }

    /** After 08:00 ART on startup: send yesterday's tiepre extrema summary if not already in {@code dailySentPath}. */
    private static void tryCatchUpTiepreExtremaDailySummaryOnStartup(
            Config config,
            MailSender mail,
            TiepreExtremaHistoryLog historyLog,
            Path dailySentPath,
            String reportHost) {
        if (!config.tiepreExtremaEnabled()) {
            return;
        }
        ZonedDateTime nowArt = ZonedDateTime.now(BUENOS_AIRES);
        if (nowArt.toLocalTime().isBefore(LocalTime.of(8, 0))) {
            LOG.info(
                    "Tiepre extrema daily catch-up at startup: before 08:00 ART — skipping; scheduled send in 08:00–08:29 window.");
            return;
        }
        try {
            MeasuresSummarySentLog sent = MeasuresSummarySentLog.open(dailySentPath);
            runTiepreExtremaDailySummaryForToday(
                    config, mail, historyLog, sent, reportHost, "startup catch-up");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Tiepre extrema daily sent log unreadable at startup: " + dailySentPath, e);
        }
    }

    private static void runTiepreExtremaDailySummaryForToday(
            Config config,
            MailSender mail,
            TiepreExtremaHistoryLog historyLog,
            MeasuresSummarySentLog sent,
            String reportHost,
            String logMode) {
        LocalDate today = ZonedDateTime.now(BUENOS_AIRES).toLocalDate();
        String key = "TIEPRE_DAILY|" + today;
        if (sent.contains(key)) {
            return;
        }
        LocalDate dataDay = today.minusDays(1);
        List<TiepreExtremaHistoryLog.Record> records;
        try {
            records = historyLog.readDay(dataDay);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Tiepre extrema daily summary: read failed for " + dataDay, e);
            return;
        }
        if (records.isEmpty()) {
            LOG.info(() -> "Tiepre extrema daily summary: no history rows for "
                    + dataDay
                    + " ("
                    + historyLog.dayFile(dataDay).toAbsolutePath()
                    + ") — skip without recording dedupe; will retry on next loop / restart.");
            return;
        }
        TiepreExtremaHistoryLog.DailyExtremes extremes =
                TiepreExtremaHistoryLog.pickDailyExtremes(records);
        if (extremes.isEmpty()) {
            LOG.info(() -> "Tiepre extrema daily summary: no parseable extremes in history for " + dataDay + " — skip.");
            return;
        }
        String subject = TiepreExtremaDailyMessages.subject(dataDay);
        String emailHtml =
                TiepreExtremaDailyMessages.formatEmailHtml(dataDay, extremes, BUENOS_AIRES, reportHost);
        String telegramHtml =
                TiepreExtremaDailyMessages.formatTelegramHtml(dataDay, extremes, BUENOS_AIRES, reportHost);
        boolean delivered = sendTiepreExtremaDailyMailTelegram(
                config, mail, subject, emailHtml, telegramHtml, reportHost);
        if (delivered) {
            try {
                sent.record(key);
                if ("startup catch-up".equals(logMode)) {
                    LOG.info(() -> "Tiepre extrema daily catch-up at startup: recorded " + key);
                } else {
                    LOG.info(() -> "Tiepre extrema daily summary sent (" + logMode + "): " + key);
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to record tiepre extrema daily key", e);
            }
        }
    }

    private static boolean sendTiepreExtremaDailyMailTelegram(
            Config config,
            MailSender mail,
            String subject,
            String emailHtml,
            String telegramHtml,
            String reportHost) {
        boolean needTg = config.telegramForTiepreExtrema() != null;
        boolean needMail = config.tiepreExtremaSendEmail() && !config.recipients().isEmpty();
        if (!needTg && !needMail) {
            LOG.fine("Tiepre extrema daily summary: no Telegram and email disabled — nothing to send.");
            return false;
        }
        boolean tgOk = !needTg;
        if (needTg) {
            try {
                new TelegramNotifier(config.telegramForTiepreExtrema()).sendHtml(telegramHtml);
                tgOk = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Tiepre extrema daily summary Telegram failed: " + e.getMessage(), e);
                tgOk = false;
            }
        }
        boolean mailOk = !needMail;
        if (needMail) {
            try {
                String plain = TiepreExtremaDailyMessages.telegramAsPlain(telegramHtml);
                String h = reportHost != null && !reportHost.isBlank() ? reportHost.trim() : "unknown";
                String plainEmail = plain.isBlank() ? "(" + h + ")" : plain;
                mail.sendHtmlWithPlain(config.recipients(), subject, plainEmail, emailHtml);
                mailOk = true;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Tiepre extrema daily summary email failed: " + e.getMessage(), e);
                mailOk = false;
            }
        }
        return tgOk && mailOk;
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
            TelegramNotifier telegramForecastValidation,
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
                    telegramForecastValidation,
                    reportHost,
                    "startup catch-up");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Forecast validation sent log unreadable at startup: " + validationSentPath, e);
        }
    }

    /**
     * Daily 08:00–08:29 ART: compare yesterday’s CABA measures (min/max, rain) with the <b>last</b> SMN JSON snapshot
     * from each <i>prior</i> ART day that still included that calendar day in {@code forecast} (see
     * {@link ForecastDaySnapshotLog#findLastSnapshotPerPriorDay}).
     */
    private static void trySendForecastValidation(
            Path measuresDir,
            ForecastDaySnapshotLog snapshotLog,
            Path validationSentPath,
            boolean configIncludesCaba,
            Config config,
            MailSender mail,
            TelegramNotifier telegramForecastValidation,
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
                    telegramForecastValidation,
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
            TelegramNotifier telegramForecastValidation,
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
            List<ForecastDaySnapshotLog.SnapshotRow> forecastSnapshots =
                    snapshotLog.findLastSnapshotPerPriorDay(dataDay, CABA_LOCATION_ID);
            String subj = ForecastValidationMessages.subject(dataDay);
            String html =
                    ForecastValidationMessages.buildEmailHtml(
                            caba.label(), dataDay, observed, observedRain, forecastSnapshots, reportHost);
            String tg =
                    ForecastValidationMessages.buildTelegramHtml(
                            caba.label(), dataDay, observed, observedRain, forecastSnapshots, reportHost);
            if (sendForecastValidationMailTelegram(config, mail, telegramForecastValidation, subj, html, tg, reportHost)) {
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
            TelegramNotifier telegramForecastValidation,
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
        if (telegramForecastValidation != null && telegramHtml != null && !telegramHtml.isBlank()) {
            try {
                telegramForecastValidation.sendHtml(telegramHtml.trim());
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
                        Optional<MeasuresSummaryMessages.TempPeriod> agg = MeasuresSummaryMessages.aggregateTemps(rows);
                        if (agg.isPresent()) {
                            int rainDays = MeasuresHistoryReader.countRainDays(rows);
                            MeasuresHistoryReader.WindMax windMax =
                                    MeasuresHistoryReader.maxWindWithDirection(rows).orElse(null);
                            sections.add(
                                    MeasuresSummaryMessages.formatSectionMonthly(
                                            st.label(), y, mv, agg.get(), rainDays, windMax));
                            tg.append(
                                    MeasuresSummaryMessages.telegramSectionMonthly(
                                            st.label(), y, mv, agg.get(), rainDays, windMax));
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

}
