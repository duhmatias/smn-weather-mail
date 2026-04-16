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
    /** Nullable: {@code SMN_REPORT_HOST} / {@code smn.report.host} for message footer; else OS hostname. */
    private final String reportHostLabel;

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
            String reportHostLabel) {
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
        this.reportHostLabel = reportHostLabel;
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

        String reportHost = firstNonBlank(System.getenv("SMN_REPORT_HOST"), null, fileProps, "smn.report.host");
        if (reportHost != null) {
            reportHost = reportHost.trim();
            if (reportHost.isEmpty()) {
                reportHost = null;
            }
        }

        return new Config(host, port, user, password, from, startTls, ssl, recipients, telegram, telegramForecastConfig, reportHost);
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

    /** Nullable explicit label for {@code (host)} footer in condition messages. */
    String reportHostLabel() {
        return reportHostLabel;
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
                + "}";
    }
}
