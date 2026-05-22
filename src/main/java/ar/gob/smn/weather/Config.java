package ar.gob.smn.weather;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

final class Config {

    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUser;
    private final String smtpPassword;
    private final String fromAddress;
    private final boolean smtpStartTls;
    private final boolean smtpSsl;
    private final List<String> recipients;
    /** Nullable: when set, weather is also sent to these Telegram chats. */
    private final Telegram telegram;
    /**
     * Nullable forecast routing: either a different bot token + chats, or same token with different chat ids.
     * When {@code null}, {@link #telegramForForecast()} uses {@link #telegram()}.
     */
    private final Telegram telegramForecastConfig;
    /**
     * Nullable measures-summary routing (daily/weekly/monthly). When {@code null}, {@link #telegramForMeasuresSummaries()}
     * uses {@link #telegram()}.
     */
    private final Telegram telegramSummariesConfig;
    /**
     * Nullable forecast-validation routing (daily 08:00 ART). When {@code null}, {@link #telegramForValidation()} uses
     * {@link #telegram()}.
     */
    private final Telegram telegramValidationConfig;
    /**
     * Nullable: one-shot “app started” Telegram (own bot + chats, or mirror of {@link #telegram} when configured).
     * When {@code null}, no startup message is sent.
     */
    private final Telegram telegramStartupConfig;
    /**
     * Nullable: optional bot token used only to long-poll {@code getUpdates} and handle slash commands (e.g. {@code /hello}).
     * Does not require {@link #telegram()} or chat ids.
     */
    private final String telegramCommandsBotToken;
    /**
     * Nullable: bot used to {@code sendMessage} replies for {@code /current} (separate from the commands bot that
     * receives {@code getUpdates}).
     */
    private final String telegramCurrentConditionsBotToken;
    /** Nullable: {@code SMN_REPORT_HOST} / {@code smn.report.host} for message footer; else OS hostname. */
    private final String reportHostLabel;
    /**
     * Optional raw {@code Cookie} header value for {@code ws1.smn.gob.ar} (e.g. Cloudflare / session cookies from
     * DevTools). Env {@code SMN_WS_COOKIES} overrides {@code smn.ws.cookies} in the file.
     */
    private final String smnWsCookieHeader;
    /** Hourly tiepre open-data extrema job ({@code tiepre.extrema.enabled} / {@code TIEPRE_EXTREMA_ENABLED}). */
    private final boolean tiepreExtremaEnabled;
    /** Nullable bot + chats for tiepre extrema Telegram; when {@code null}, only email is sent (if enabled). */
    private final Telegram telegramTiepreExtremaConfig;
    /** When tiepre extrema is enabled: also email {@link #recipients()} (default true). */
    private final boolean tiepreExtremaSendEmail;
    /**
     * Tiepre station names excluded from min/max temp and max-wind ranking; still listed with own values when present in
     * tiepre. Default when unset: Antarctic bases + Base Carlini (see {@code tiepre.extrema.exclude.stations}).
     */
    private final List<String> tiepreExtremaExcludeStations;
    /**
     * When {@code true} (default), Telegram/email extrema messages include a section per excluded station (when present
     * in tiepre). When {@code false}, those locations are only omitted from the min/max/wind ranking — not listed in the
     * message. Does not change the SHA-256 fingerprint (dedupe still uses full tiepre data for excluded rows).
     */
    private final boolean tiepreExtremaExcludeStationsInMessage;
    /**
     * SMN {@code /v1/weather/location/{id}} ids for the conditions mail/Telegram loop (same order as polling). Default
     * CABA + Aeroparque; see {@code smn.location.ids} / {@code SMN_LOCATION_IDS}.
     */
    private final List<SmnClient.Station> smnWeatherLocations;
    /**
     * File listing extra {@code chat_id} values for condition-weather Telegram (merged on each send with
     * {@code telegram.chat.ids}). {@code null} when main Telegram is not configured.
     */
    private final Path telegramConditionsSubscriberChatsFile;

    private static final Map<Integer, String> SMN_KNOWN_LOCATION_LABELS = Map.of(
            4864, "Ciudad Autónoma de Buenos Aires",
            10821, "Aeroparque Buenos Aires");

    private Config(
            String smtpHost,
            int smtpPort,
            String smtpUser,
            String smtpPassword,
            String fromAddress,
            boolean smtpStartTls,
            boolean smtpSsl,
            List<String> recipients,
            Telegram telegram,
            Telegram telegramForecastConfig,
            Telegram telegramSummariesConfig,
            Telegram telegramValidationConfig,
            Telegram telegramStartupConfig,
            String telegramCommandsBotToken,
            String telegramCurrentConditionsBotToken,
            boolean tiepreExtremaEnabled,
            Telegram telegramTiepreExtremaConfig,
            boolean tiepreExtremaSendEmail,
            List<String> tiepreExtremaExcludeStations,
            boolean tiepreExtremaExcludeStationsInMessage,
            List<SmnClient.Station> smnWeatherLocations,
            Path telegramConditionsSubscriberChatsFile,
            String reportHostLabel,
            String smnWsCookieHeader) {
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUser = smtpUser;
        this.smtpPassword = smtpPassword;
        this.fromAddress = fromAddress;
        this.smtpStartTls = smtpStartTls;
        this.smtpSsl = smtpSsl;
        this.recipients = recipients;
        this.telegram = telegram;
        this.telegramForecastConfig = telegramForecastConfig;
        this.telegramSummariesConfig = telegramSummariesConfig;
        this.telegramValidationConfig = telegramValidationConfig;
        this.telegramStartupConfig = telegramStartupConfig;
        this.telegramCommandsBotToken = telegramCommandsBotToken;
        this.telegramCurrentConditionsBotToken = telegramCurrentConditionsBotToken;
        this.tiepreExtremaEnabled = tiepreExtremaEnabled;
        this.telegramTiepreExtremaConfig = telegramTiepreExtremaConfig;
        this.tiepreExtremaSendEmail = tiepreExtremaSendEmail;
        this.tiepreExtremaExcludeStations = tiepreExtremaExcludeStations;
        this.tiepreExtremaExcludeStationsInMessage = tiepreExtremaExcludeStationsInMessage;
        this.smnWeatherLocations = smnWeatherLocations;
        this.telegramConditionsSubscriberChatsFile = telegramConditionsSubscriberChatsFile;
        this.reportHostLabel = reportHostLabel;
        this.smnWsCookieHeader = smnWsCookieHeader;
    }

    /** Optional Telegram mirror; {@code null} if {@code telegram.bot.token} / {@code TELEGRAM_BOT_TOKEN} unset. */
    static final class Telegram {
        private final String botToken;
        private final List<String> chatIds;

        Telegram(String botToken, List<String> chatIds) {
            this.botToken = botToken;
            this.chatIds = chatIds;
        }

        String botToken() {
            return botToken;
        }

        List<String> chatIds() {
            return chatIds;
        }
    }

    /**
     * Loads optional {@code config.properties} (or path in {@code SMN_CONFIG_FILE}), then fills gaps from
     * environment variables. For each setting, {@code MAIL_*} / {@code GMAIL_*} env vars override the file.
     */
    static Config load() {
        Properties fileProps = new Properties();
        Path configPath = configPath();
        if (Files.isRegularFile(configPath)) {
            try (Reader r = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                fileProps.load(r);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read config file: " + configPath.toAbsolutePath(), e);
            }
        }

        String host =
                firstNonBlank(System.getenv("MAIL_SMTP_HOST"), System.getenv("GMAIL_SMTP_HOST"), fileProps, "mail.smtp.host");
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(
                    "Set mail.smtp.host in config.properties or MAIL_SMTP_HOST (e.g. smtp-relay.brevo.com).");
        }
        host = host.trim();

        String user =
                firstNonBlank(System.getenv("MAIL_SMTP_USER"), System.getenv("GMAIL_SMTP_USER"), fileProps, "mail.smtp.user");
        if (user == null || user.isBlank()) {
            throw new IllegalStateException("Set mail.smtp.user in config or MAIL_SMTP_USER.");
        }
        user = user.trim();

        String password = resolveSmtpPassword(fileProps);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Set mail.smtp.password in config or MAIL_SMTP_PASSWORD.");
        }
        password = password.trim();

        String portStr =
                firstNonBlank(System.getenv("MAIL_SMTP_PORT"), System.getenv("GMAIL_SMTP_PORT"), fileProps, "mail.smtp.port");
        int port = portStr == null || portStr.isBlank() ? 587 : Integer.parseInt(portStr.trim());

        String fromEnv = System.getenv("MAIL_FROM");
        String from = firstNonBlank(fromEnv, null, fileProps, "mail.from");
        if (from == null || from.isBlank()) {
            from = user;
        } else {
            from = from.trim();
        }

        boolean sslExplicit = parseBool(firstNonBlank(System.getenv("MAIL_SMTP_SSL"), null, fileProps, "mail.smtp.ssl"));
        boolean ssl = sslExplicit || port == 465;
        String startTlsRaw = firstNonBlank(System.getenv("MAIL_SMTP_STARTTLS"), null, fileProps, "mail.smtp.starttls");
        boolean startTls = !ssl && parseBoolOrDefault(startTlsRaw, true);

        List<String> recipients = parseRecipients(fileProps);
        Telegram telegram = parseTelegram(fileProps);
        Telegram telegramForecastConfig = parseForecastTelegram(fileProps, telegram);
        Telegram telegramSummariesConfig = parseSummariesTelegram(fileProps, telegram);
        Telegram telegramValidationConfig = parseValidationTelegram(fileProps, telegram);
        Telegram telegramStartupConfig = parseStartupTelegram(fileProps, telegram);

        boolean tiepreExtremaEnabled =
                parseBool(firstNonBlank(System.getenv("TIEPRE_EXTREMA_ENABLED"), null, fileProps, "tiepre.extrema.enabled"));
        Telegram telegramTiepreExtrema = parseTiepreExtremaTelegram(fileProps, telegram);
        boolean tiepreExtremaSendEmail =
                parseBoolOrDefault(
                        firstNonBlank(System.getenv("TIEPRE_EXTREMA_EMAIL"), null, fileProps, "tiepre.extrema.email"),
                        true);
        List<String> tiepreExtremaExcludeStations = parseTiepreExtremaExcludeStations(fileProps);
        boolean tiepreExtremaExcludeStationsInMessage =
                parseBoolOrDefault(
                        firstNonBlank(
                                System.getenv("TIEPRE_EXTREMA_EXCLUDE_STATIONS_IN_MESSAGE"),
                                null,
                                fileProps,
                                "tiepre.extrema.exclude.stations.in.message"),
                        true);
        if (tiepreExtremaEnabled) {
            boolean canEmail = tiepreExtremaSendEmail && !recipients.isEmpty();
            if (telegramTiepreExtrema == null && !canEmail) {
                throw new IllegalStateException(
                        "tiepre.extrema.enabled is true: configure telegram.tiepre.extrema.chat.ids (or "
                                + "TELEGRAM_TIEPRE_EXTREMA_CHAT_IDS) and/or set tiepre.extrema.email=true with mail.to "
                                + "recipients.");
            }
        }

        String commandsToken =
                firstNonBlank(System.getenv("TELEGRAM_COMMANDS_BOT_TOKEN"), null, fileProps, "telegram.commands.bot.token");
        if (commandsToken != null && !commandsToken.isBlank()) {
            commandsToken = normalizeTelegramBotToken(commandsToken.trim());
        } else {
            commandsToken = null;
        }

        String currentBotToken =
                firstNonBlank(System.getenv("TELEGRAM_CURRENT_BOT_TOKEN"), null, fileProps, "telegram.current.bot.token");
        if (currentBotToken != null && !currentBotToken.isBlank()) {
            currentBotToken = normalizeTelegramBotToken(currentBotToken.trim());
        } else {
            currentBotToken = null;
        }

        String reportHost = firstNonBlank(System.getenv("SMN_REPORT_HOST"), null, fileProps, "smn.report.host");
        if (reportHost != null) {
            reportHost = reportHost.trim();
            if (reportHost.isEmpty()) {
                reportHost = null;
            }
        }

        String smnWsCookieHeader = firstNonBlank(System.getenv("SMN_WS_COOKIES"), null, fileProps, "smn.ws.cookies");
        if (smnWsCookieHeader != null) {
            smnWsCookieHeader = smnWsCookieHeader.trim();
            if (smnWsCookieHeader.isEmpty()) {
                smnWsCookieHeader = null;
            }
        }

        List<SmnClient.Station> smnWeatherLocations = parseSmnWeatherLocations(fileProps);
        Path telegramConditionsSubscriberChatsFile =
                telegram != null ? resolveTelegramConditionsSubscriberChatsFile(fileProps) : null;

        return new Config(
                host,
                port,
                user,
                password,
                from,
                startTls,
                ssl,
                recipients,
                telegram,
                telegramForecastConfig,
                telegramSummariesConfig,
                telegramValidationConfig,
                telegramStartupConfig,
                commandsToken,
                currentBotToken,
                tiepreExtremaEnabled,
                telegramTiepreExtrema,
                tiepreExtremaSendEmail,
                tiepreExtremaExcludeStations,
                tiepreExtremaExcludeStationsInMessage,
                smnWeatherLocations,
                telegramConditionsSubscriberChatsFile,
                reportHost,
                smnWsCookieHeader);
    }

    private static Path resolveTelegramConditionsSubscriberChatsFile(Properties fileProps) {
        String custom =
                firstNonBlank(
                        System.getenv("SMN_TELEGRAM_CONDITIONS_SUBSCRIBER_FILE"),
                        null,
                        fileProps,
                        "telegram.conditions.subscriber.chats.file");
        Path configPath = configPath();
        Path baseDir = configPath.toAbsolutePath().getParent();
        if (baseDir == null) {
            baseDir = Path.of(".");
        }
        if (custom != null && !custom.isBlank()) {
            Path p = Path.of(custom.trim());
            return p.isAbsolute() ? p : baseDir.resolve(p);
        }
        return baseDir.resolve("smn-telegram-conditions-subscriber-chats.txt");
    }

    /**
     * Forecast bulletins: optional {@code telegram.forecast.bot.token} / {@code TELEGRAM_FORECAST_BOT_TOKEN}
     * with {@code telegram.forecast.chat.ids}, or same bot as {@link #telegram()} with only different chat ids.
     */
    private static Telegram parseForecastTelegram(Properties fileProps, Telegram main) {
        if (main == null) {
            return null;
        }
        String forecastToken =
                firstNonBlank(System.getenv("TELEGRAM_FORECAST_BOT_TOKEN"), null, fileProps, "telegram.forecast.bot.token");
        String forecastChats =
                firstNonBlank(System.getenv("TELEGRAM_FORECAST_CHAT_IDS"), null, fileProps, "telegram.forecast.chat.ids");

        if (forecastToken != null && !forecastToken.isBlank()) {
            forecastToken = normalizeTelegramBotToken(forecastToken.trim());
            if (forecastChats == null || forecastChats.isBlank()) {
                throw new IllegalStateException(
                        "telegram.forecast.bot.token / TELEGRAM_FORECAST_BOT_TOKEN is set; add telegram.forecast.chat.ids "
                                + "(recipient user or group id(s) — not the bot's id from the token) or TELEGRAM_FORECAST_CHAT_IDS.");
            }
            List<String> ids = splitCommaIds(forecastChats);
            if (ids.isEmpty()) {
                throw new IllegalStateException("telegram.forecast.chat.ids must list at least one chat id.");
            }
            return new Telegram(forecastToken, Collections.unmodifiableList(ids));
        }

        if (forecastChats != null && !forecastChats.isBlank()) {
            List<String> ids = splitCommaIds(forecastChats);
            if (ids.isEmpty()) {
                return null;
            }
            return new Telegram(main.botToken(), Collections.unmodifiableList(ids));
        }

        return null;
    }

    /**
     * Optional bot + chats for measures summaries (daily/weekly/monthly). Env {@code TELEGRAM_SUMMARIES_BOT_TOKEN} /
     * {@code TELEGRAM_SUMMARIES_CHAT_IDS} or {@code telegram.summaries.bot.token} / {@code telegram.summaries.chat.ids}.
     * If only chat ids are set, uses {@code telegram.bot.token} as the bot (requires main Telegram to be configured).
     */
    private static Telegram parseSummariesTelegram(Properties fileProps, Telegram main) {
        String token =
                firstNonBlank(System.getenv("TELEGRAM_SUMMARIES_BOT_TOKEN"), null, fileProps, "telegram.summaries.bot.token");
        String chats =
                firstNonBlank(System.getenv("TELEGRAM_SUMMARIES_CHAT_IDS"), null, fileProps, "telegram.summaries.chat.ids");

        if (token != null && !token.isBlank()) {
            token = normalizeTelegramBotToken(token.trim());
            if (chats == null || chats.isBlank()) {
                throw new IllegalStateException(
                        "telegram.summaries.bot.token / TELEGRAM_SUMMARIES_BOT_TOKEN is set; add telegram.summaries.chat.ids "
                                + "or TELEGRAM_SUMMARIES_CHAT_IDS (numeric chat id(s), comma-separated).");
            }
            List<String> ids = splitCommaIds(chats);
            if (ids.isEmpty()) {
                throw new IllegalStateException("telegram.summaries.chat.ids must list at least one chat id.");
            }
            return new Telegram(token, Collections.unmodifiableList(ids));
        }

        if (chats != null && !chats.isBlank()) {
            if (main == null) {
                throw new IllegalStateException(
                        "telegram.summaries.chat.ids is set but no bot token: set telegram.summaries.bot.token or telegram.bot.token.");
            }
            List<String> ids = splitCommaIds(chats);
            if (ids.isEmpty()) {
                return null;
            }
            return new Telegram(main.botToken(), Collections.unmodifiableList(ids));
        }

        return null;
    }

    /**
     * Optional bot + chats for forecast-vs-observed validation (08:00 ART). Env {@code TELEGRAM_VALIDATION_BOT_TOKEN} /
     * {@code TELEGRAM_VALIDATION_CHAT_IDS} or {@code telegram.validation.bot.token} / {@code telegram.validation.chat.ids}.
     * If only chat ids are set, uses {@code telegram.bot.token} as the bot (requires main Telegram to be configured).
     */
    private static Telegram parseValidationTelegram(Properties fileProps, Telegram main) {
        String token =
                firstNonBlank(System.getenv("TELEGRAM_VALIDATION_BOT_TOKEN"), null, fileProps, "telegram.validation.bot.token");
        String chats =
                firstNonBlank(System.getenv("TELEGRAM_VALIDATION_CHAT_IDS"), null, fileProps, "telegram.validation.chat.ids");

        if (token != null && !token.isBlank()) {
            token = normalizeTelegramBotToken(token.trim());
            if (chats == null || chats.isBlank()) {
                throw new IllegalStateException(
                        "telegram.validation.bot.token / TELEGRAM_VALIDATION_BOT_TOKEN is set; add telegram.validation.chat.ids "
                                + "or TELEGRAM_VALIDATION_CHAT_IDS (numeric chat id(s), comma-separated).");
            }
            List<String> ids = splitCommaIds(chats);
            if (ids.isEmpty()) {
                throw new IllegalStateException("telegram.validation.chat.ids must list at least one chat id.");
            }
            return new Telegram(token, Collections.unmodifiableList(ids));
        }

        if (chats != null && !chats.isBlank()) {
            if (main == null) {
                throw new IllegalStateException(
                        "telegram.validation.chat.ids is set but no bot token: set telegram.validation.bot.token or telegram.bot.token.");
            }
            List<String> ids = splitCommaIds(chats);
            if (ids.isEmpty()) {
                return null;
            }
            return new Telegram(main.botToken(), Collections.unmodifiableList(ids));
        }

        return null;
    }

    /**
     * Optional bot + chats for hourly tiepre extrema Telegram. Env {@code TELEGRAM_TIEPRE_EXTREMA_BOT_TOKEN} /
     * {@code TELEGRAM_TIEPRE_EXTREMA_CHAT_IDS} or {@code telegram.tiepre.extrema.bot.token} /
     * {@code telegram.tiepre.extrema.chat.ids}. If only chat ids are set, uses {@link #telegram()}’s bot token.
     */
    private static Telegram parseTiepreExtremaTelegram(Properties fileProps, Telegram main) {
        String token =
                firstNonBlank(
                        System.getenv("TELEGRAM_TIEPRE_EXTREMA_BOT_TOKEN"),
                        null,
                        fileProps,
                        "telegram.tiepre.extrema.bot.token");
        String chats =
                firstNonBlank(
                        System.getenv("TELEGRAM_TIEPRE_EXTREMA_CHAT_IDS"),
                        null,
                        fileProps,
                        "telegram.tiepre.extrema.chat.ids");

        if (token != null && !token.isBlank()) {
            token = normalizeTelegramBotToken(token.trim());
            if (chats == null || chats.isBlank()) {
                throw new IllegalStateException(
                        "telegram.tiepre.extrema.bot.token / TELEGRAM_TIEPRE_EXTREMA_BOT_TOKEN is set; add "
                                + "telegram.tiepre.extrema.chat.ids or TELEGRAM_TIEPRE_EXTREMA_CHAT_IDS.");
            }
            List<String> ids = splitCommaIds(chats);
            if (ids.isEmpty()) {
                throw new IllegalStateException("telegram.tiepre.extrema.chat.ids must list at least one chat id.");
            }
            return new Telegram(token, Collections.unmodifiableList(ids));
        }

        if (chats != null && !chats.isBlank()) {
            if (main == null) {
                throw new IllegalStateException(
                        "telegram.tiepre.extrema.chat.ids is set but no bot token: set telegram.tiepre.extrema.bot.token "
                                + "or telegram.bot.token.");
            }
            List<String> ids = splitCommaIds(chats);
            if (ids.isEmpty()) {
                throw new IllegalStateException(
                        "telegram.tiepre.extrema.chat.ids / TELEGRAM_TIEPRE_EXTREMA_CHAT_IDS is set but contains no valid id.");
            }
            return new Telegram(main.botToken(), Collections.unmodifiableList(ids));
        }

        return null;
    }

    /**
     * Optional bot + chats for a single startup notification (“App started”, version, host, diagnostics). Sent with
     * Telegram HTML ({@code parse_mode=HTML}) for bold labels. Env {@code TELEGRAM_STARTUP_BOT_TOKEN} /
     * {@code TELEGRAM_STARTUP_CHAT_IDS} or {@code telegram.startup.bot.token} / {@code telegram.startup.chat.ids}.
     * If only startup chat ids are set, uses {@link #telegram()}’s bot token (requires main Telegram to be configured).
     * Alternatively {@code telegram.startup.use.main.chats=true} / {@code TELEGRAM_STARTUP_USE_MAIN_CHATS=1} reuses the
     * same bot and chats as condition Telegram ({@link #telegram()}).
     */
    private static Telegram parseStartupTelegram(Properties fileProps, Telegram main) {
        String token =
                firstNonBlank(System.getenv("TELEGRAM_STARTUP_BOT_TOKEN"), null, fileProps, "telegram.startup.bot.token");
        String chats =
                firstNonBlank(System.getenv("TELEGRAM_STARTUP_CHAT_IDS"), null, fileProps, "telegram.startup.chat.ids");

        if (token != null && !token.isBlank()) {
            token = normalizeTelegramBotToken(token.trim());
            if (chats == null || chats.isBlank()) {
                throw new IllegalStateException(
                        "telegram.startup.bot.token / TELEGRAM_STARTUP_BOT_TOKEN is set; add telegram.startup.chat.ids "
                                + "or TELEGRAM_STARTUP_CHAT_IDS (numeric chat id(s), comma-separated).");
            }
            List<String> ids = splitCommaIds(chats);
            if (ids.isEmpty()) {
                throw new IllegalStateException("telegram.startup.chat.ids must list at least one chat id.");
            }
            return new Telegram(token, Collections.unmodifiableList(ids));
        }

        if (chats != null && !chats.isBlank()) {
            if (main == null) {
                throw new IllegalStateException(
                        "telegram.startup.chat.ids is set but no bot token: set telegram.startup.bot.token or telegram.bot.token.");
            }
            List<String> ids = splitCommaIds(chats);
            if (ids.isEmpty()) {
                throw new IllegalStateException(
                        "telegram.startup.chat.ids / TELEGRAM_STARTUP_CHAT_IDS is set but contains no valid id after "
                                + "splitting on commas (check for typos or only commas/spaces).");
            }
            return new Telegram(main.botToken(), Collections.unmodifiableList(ids));
        }

        String mirrorMain =
                firstNonBlank(
                        System.getenv("TELEGRAM_STARTUP_USE_MAIN_CHATS"),
                        null,
                        fileProps,
                        "telegram.startup.use.main.chats");
        if (parseBool(mirrorMain)) {
            if (main == null) {
                throw new IllegalStateException(
                        "telegram.startup.use.main.chats / TELEGRAM_STARTUP_USE_MAIN_CHATS is true but main Telegram is "
                                + "not configured (set telegram.bot.token and telegram.chat.ids, or TELEGRAM_BOT_TOKEN "
                                + "and TELEGRAM_CHAT_IDS).");
            }
            List<String> mainIds = main.chatIds();
            if (mainIds == null || mainIds.isEmpty()) {
                throw new IllegalStateException(
                        "telegram.startup.use.main.chats is true but telegram.chat.ids has no recipients.");
            }
            return new Telegram(main.botToken(), Collections.unmodifiableList(mainIds));
        }

        return null;
    }

    private static List<String> splitCommaIds(String raw) {
        List<String> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                ids.add(s);
            }
        }
        return ids;
    }

    private static Telegram parseTelegram(Properties fileProps) {
        String token =
                firstNonBlank(System.getenv("TELEGRAM_BOT_TOKEN"), null, fileProps, "telegram.bot.token");
        String rawIds =
                firstNonBlank(System.getenv("TELEGRAM_CHAT_IDS"), null, fileProps, "telegram.chat.ids");
        if (token == null || token.isBlank()) {
            return null;
        }
        token = normalizeTelegramBotToken(token.trim());
        if (rawIds == null || rawIds.isBlank()) {
            throw new IllegalStateException(
                    "telegram.bot.token is set; add telegram.chat.ids (comma-separated) or TELEGRAM_CHAT_IDS.");
        }
        List<String> ids = new ArrayList<>();
        for (String part : rawIds.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                ids.add(s);
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("telegram.chat.ids / TELEGRAM_CHAT_IDS must list at least one id.");
        }
        return new Telegram(token, Collections.unmodifiableList(ids));
    }

    /**
     * Trims quotes/BOM and removes a mistaken leading {@code bot} before the numeric id (we already use {@code /bot&lt;token&gt;/} in the URL).
     */
    private static String normalizeTelegramBotToken(String token) {
        if (token.startsWith("\uFEFF")) {
            token = token.substring(1).trim();
        }
        if (token.length() >= 2) {
            char a = token.charAt(0);
            char b = token.charAt(token.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                token = token.substring(1, token.length() - 1).trim();
            }
        }
        String lower = token.toLowerCase(Locale.ROOT);
        while (lower.startsWith("bot")
                && token.length() > 3
                && Character.isDigit(token.charAt(3))) {
            token = token.substring(3).trim();
            lower = token.toLowerCase(Locale.ROOT);
        }
        return token;
    }

    /**
     * {@code MAIL_SMTP_PASSWORD} / {@code GMAIL_SMTP_PASSWORD} are always plaintext. File value {@code ENC1:...}
     * is decrypted with {@code SMN_MASTER_PASSWORD}; otherwise the file value is used as plaintext.
     */
    private static String resolveSmtpPassword(Properties fileProps) {
        String env = firstNonBlank(System.getenv("MAIL_SMTP_PASSWORD"), System.getenv("GMAIL_SMTP_PASSWORD"), null, null);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        if (fileProps == null) {
            return null;
        }
        String file = fileProps.getProperty("mail.smtp.password");
        if (file == null || file.isBlank()) {
            return null;
        }
        file = file.trim();
        if (PropertySecretCipher.isWrapped(file)) {
            String master = System.getenv("SMN_MASTER_PASSWORD");
            if (master == null || master.isBlank()) {
                throw new IllegalStateException(
                        "mail.smtp.password is ENC1-encrypted; set SMN_MASTER_PASSWORD to decrypt it.");
            }
            try {
                return PropertySecretCipher.decrypt(file, master);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Could not decrypt mail.smtp.password (wrong SMN_MASTER_PASSWORD?)", e);
            }
        }
        return file;
    }

    private static Path configPath() {
        String raw = System.getenv("SMN_CONFIG_FILE");
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim());
        }
        return Path.of("config.properties");
    }

    /**
     * Comma-separated {@code /v1/weather/location/{id}} ids for the conditions loop and /current API priming. Env
     * {@code SMN_LOCATION_IDS} overrides the file. Default: {@code 4864,10821}.
     */
    private static List<SmnClient.Station> parseSmnWeatherLocations(Properties fileProps) {
        String raw = firstNonBlank(System.getenv("SMN_LOCATION_IDS"), null, fileProps, "smn.location.ids");
        if (raw == null || raw.isBlank()) {
            raw = "4864,10821";
        }
        List<SmnClient.Station> list = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("smn.location.ids / SMN_LOCATION_IDS: invalid integer: " + part, e);
            }
            String label = SMN_KNOWN_LOCATION_LABELS.getOrDefault(id, "Ubicación SMN " + id);
            list.add(new SmnClient.Station(id, label));
        }
        if (list.isEmpty()) {
            throw new IllegalStateException("smn.location.ids / SMN_LOCATION_IDS produced no stations: " + raw);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * Comma-separated tiepre station names to skip in extrema ranking. Env {@code TIEPRE_EXTREMA_EXCLUDE_STATIONS}
     * overrides the file; when the key is present in the file (even empty), that value is used. When both are absent,
     * uses the built-in default list (Antarctic bases + Base Carlini).
     */
    private static List<String> parseTiepreExtremaExcludeStations(Properties fileProps) {
        String env = System.getenv("TIEPRE_EXTREMA_EXCLUDE_STATIONS");
        if (env != null) {
            return parseCommaSeparatedStations(env);
        }
        if (fileProps != null) {
            String f = fileProps.getProperty("tiepre.extrema.exclude.stations");
            if (f != null) {
                return parseCommaSeparatedStations(f);
            }
        }
        return defaultTiepreExtremaExcludeStations();
    }

    private static List<String> parseCommaSeparatedStations(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> defaultTiepreExtremaExcludeStations() {
        return List.of(
                "Base Marambio",
                "Base Belgrano II",
                "Base Belgrano",
                "Base Esperanza",
                "Base San Martín",
                "Base Carlini");
    }

    private static List<String> parseRecipients(Properties fileProps) {
        String raw = System.getenv("MAIL_TO");
        if (raw == null || raw.isBlank()) {
            raw = fileProps.getProperty("mail.to");
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Set mail.to in config.properties (comma-separated) or MAIL_TO.");
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("mail.to / MAIL_TO must list at least one address.");
        }
        return Collections.unmodifiableList(out);
    }

    private static String firstNonBlank(String envA, String envB, Properties fileProps, String fileKey) {
        if (envA != null && !envA.isBlank()) {
            return envA.trim();
        }
        if (envB != null && !envB.isBlank()) {
            return envB.trim();
        }
        if (fileProps != null) {
            String f = fileProps.getProperty(fileKey);
            if (f != null && !f.isBlank()) {
                return f.trim();
            }
        }
        return null;
    }

    private static boolean parseBool(String v) {
        return v != null && (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("1") || v.equalsIgnoreCase("yes"));
    }

    private static boolean parseBoolOrDefault(String v, boolean defaultValue) {
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        if (v.equalsIgnoreCase("false") || v.equalsIgnoreCase("0") || v.equalsIgnoreCase("no")) {
            return false;
        }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("1") || v.equalsIgnoreCase("yes")) {
            return true;
        }
        return defaultValue;
    }

    List<String> recipients() {
        return recipients;
    }

    Telegram telegram() {
        return telegram;
    }

    /**
     * Bot + chats for forecast bulletin Telegram sends. When no forecast-specific config exists, returns
     * {@link #telegram()}.
     */
    Telegram telegramForForecast() {
        return telegramForecastConfig != null ? telegramForecastConfig : telegram;
    }

    /** Nullable when forecast uses the same token and chats as current conditions. */
    Telegram telegramForecastConfig() {
        return telegramForecastConfig;
    }

    /** Nullable when measures summaries use the same routing as {@link #telegram()}. */
    Telegram telegramSummariesConfig() {
        return telegramSummariesConfig;
    }

    /**
     * Bot + chats for measures summary Telegram (daily/weekly/monthly). Uses dedicated config when set, otherwise
     * {@link #telegram()}.
     */
    Telegram telegramForMeasuresSummaries() {
        return telegramSummariesConfig != null ? telegramSummariesConfig : telegram;
    }

    /** Nullable when validation uses the same routing as {@link #telegram()}. */
    Telegram telegramValidationConfig() {
        return telegramValidationConfig;
    }

    /**
     * Bot + chats for forecast validation Telegram (08:00 ART). Uses dedicated config when set, otherwise
     * {@link #telegram()}.
     */
    Telegram telegramForValidation() {
        return telegramValidationConfig != null ? telegramValidationConfig : telegram;
    }

    /**
     * Optional bot + chats for the startup notification. {@code null} when unset — no message is sent.
     */
    Telegram telegramStartup() {
        return telegramStartupConfig;
    }

    boolean tiepreExtremaEnabled() {
        return tiepreExtremaEnabled;
    }

    /** Nullable: tiepre extrema hourly Telegram. */
    Telegram telegramForTiepreExtrema() {
        return telegramTiepreExtremaConfig;
    }

    boolean tiepreExtremaSendEmail() {
        return tiepreExtremaSendEmail;
    }

    /**
     * Station names excluded from tiepre extrema min/max temperature and max-wind ranking (comma list in config). Empty
     * list means no exclusions. When the property/env is unset, returns the default Antarctic bases + Base Carlini.
     */
    List<String> tiepreExtremaExcludeStations() {
        return tiepreExtremaExcludeStations;
    }

    /**
     * When {@code true}, excluded stations (when present in tiepre) are still shown in Telegram/email extrema messages.
     * When {@code false}, they are only removed from the ranking, not listed in the message. Default {@code true}.
     */
    boolean tiepreExtremaExcludeStationsInMessage() {
        return tiepreExtremaExcludeStationsInMessage;
    }

    /**
     * Bot token for optional {@link TelegramBotCommandListener} ({@code getUpdates}). {@code null} when unset.
     */
    String telegramCommandsBotToken() {
        return telegramCommandsBotToken;
    }

    /**
     * Bot token for {@code /current} replies ({@code sendMessage}). {@code null} when unset.
     */
    String telegramCurrentConditionsBotToken() {
        return telegramCurrentConditionsBotToken;
    }

    /** Nullable explicit label for {@code (host)} footer in condition messages. */
    String reportHostLabel() {
        return reportHostLabel;
    }

    /**
     * Optional {@code Cookie} header for SMN {@code ws1} API (see {@link SmnClient}). {@code null} when unset.
     */
    String smnWsCookieHeader() {
        return smnWsCookieHeader;
    }

    /**
     * SMN location ids for current-conditions email/Telegram polling and for priming {@code /current} (see
     * {@code smn.location.ids} / {@code SMN_LOCATION_IDS}).
     */
    List<SmnClient.Station> smnWeatherLocations() {
        return smnWeatherLocations;
    }

    /**
     * Where extra condition-Telegram {@code chat_id} lines are stored for {@code /subscribe current} / {@code
     * /unsubscribe current} (merged on each
     * send with {@code telegram.chat.ids}). {@code null} when main Telegram is not configured.
     */
    Path telegramConditionsSubscriberChatsFile() {
        return telegramConditionsSubscriberChatsFile;
    }

    String smtpHost() {
        return smtpHost;
    }

    int smtpPort() {
        return smtpPort;
    }

    String smtpUser() {
        return smtpUser;
    }

    String smtpPassword() {
        return smtpPassword;
    }

    String fromAddress() {
        return fromAddress;
    }

    boolean smtpStartTls() {
        return smtpStartTls;
    }

    boolean smtpSsl() {
        return smtpSsl;
    }

    @Override
    public String toString() {
        return "Config{smtpHost='" + smtpHost + "', smtpPort=" + smtpPort
                + ", smtpUser='" + smtpUser + "', fromAddress='" + fromAddress + "'"
                + ", recipients=" + recipients.size()
                + ", telegram=" + (telegram != null ? "on(" + telegram.chatIds().size() + " chats)" : "off")
                + ", forecast="
                + (telegram != null && telegramForecastConfig != null
                        ? (telegramForecastConfig.botToken().equals(telegram.botToken()) ? "otherChats" : "otherBot")
                        : "same")
                + ", summaries="
                + (telegramSummariesConfig == null
                        ? "same"
                        : (telegram != null
                                        && telegramSummariesConfig.botToken().equals(telegram.botToken())
                                ? "otherChats"
                                : "otherBot"))
                + ", validation="
                + (telegramValidationConfig == null
                        ? "same"
                        : (telegram != null
                                        && telegramValidationConfig.botToken().equals(telegram.botToken())
                                ? "otherChats"
                                : "otherBot"))
                + ", startup="
                + (telegramStartupConfig == null
                        ? "off"
                        : (telegram != null
                                        && telegramStartupConfig.botToken().equals(telegram.botToken())
                                ? "otherChats"
                                : "otherBot"))
                + ", commandsBot="
                + (telegramCommandsBotToken != null ? "on" : "off")
                + ", currentBot="
                + (telegramCurrentConditionsBotToken != null ? "on" : "off")
                + ", smnLocations="
                + smnWeatherLocations.size()
                + ", condSubChatsFile="
                + (telegramConditionsSubscriberChatsFile != null
                        ? telegramConditionsSubscriberChatsFile.toString()
                        : "n/a")
                + ", tiepreExtrema="
                + (tiepreExtremaEnabled
                        ? ("on(tg="
                                + (telegramTiepreExtremaConfig != null ? telegramTiepreExtremaConfig.chatIds().size() : 0)
                                + ",mail="
                                + tiepreExtremaSendEmail
                                + ",tiepreExclude="
                                + tiepreExtremaExcludeStations.size()
                                + ",tiepreExcludeInMsg="
                                + tiepreExtremaExcludeStationsInMessage
                                + ")")
                        : "off")
                + "}";
    }
}
