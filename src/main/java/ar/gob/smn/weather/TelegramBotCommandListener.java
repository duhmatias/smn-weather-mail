package ar.gob.smn.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Long-polls {@code getUpdates} for a dedicated bot token and handles slash commands (e.g. {@code /hello}).
 * Replies via {@code sendMessage} to the chat that issued the command. Optional {@code /image} uses a second bot token
 * to {@code sendPhoto} (user must have started that bot too).
 */
final class TelegramBotCommandListener implements Runnable {

    private static final Logger LOG = Logger.getLogger(TelegramBotCommandListener.class.getName());
    private static final int LONG_POLL_TIMEOUT_SEC = 30;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String botToken;
    /** Same label as condition/forecast footers ({@code smn.report.host} / hostname). */
    private final String reportHost;
    /** Nullable: delivery bot for {@code /image}. */
    private final String imageDeliveryBotToken;
    /** Nullable: direct SMN image URL override. */
    private final String smnTopesCentroImageUrl;
    private final SmnTopesCentroImageFetcher topesFetcher = new SmnTopesCentroImageFetcher();
    private final TelegramPhotoSender photoSender = new TelegramPhotoSender();

    TelegramBotCommandListener(String botToken, String reportHost, String imageDeliveryBotToken, String smnTopesCentroImageUrl) {
        this.botToken = botToken;
        this.reportHost = reportHost != null && !reportHost.isBlank() ? reportHost.trim() : "unknown-host";
        this.imageDeliveryBotToken = imageDeliveryBotToken != null && !imageDeliveryBotToken.isBlank()
                ? imageDeliveryBotToken.trim()
                : null;
        this.smnTopesCentroImageUrl = smnTopesCentroImageUrl != null && !smnTopesCentroImageUrl.isBlank()
                ? smnTopesCentroImageUrl.trim()
                : null;
    }

    @Override
    public void run() {
        long nextOffset = 0;
        LOG.info("Telegram command listener started (getUpdates long poll)");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                URI uri = getUpdatesUri(nextOffset);
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .GET()
                        .timeout(Duration.ofSeconds(LONG_POLL_TIMEOUT_SEC + 15))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    LOG.log(Level.WARNING, "Telegram getUpdates HTTP " + res.statusCode() + ": " + res.body());
                    sleepQuiet(Duration.ofSeconds(5));
                    continue;
                }
                String body = res.body();
                JsonNode root = JSON.readTree(body);
                if (!root.path("ok").asBoolean(false)) {
                    LOG.log(Level.WARNING, "Telegram getUpdates not ok: " + body);
                    sleepQuiet(Duration.ofSeconds(5));
                    continue;
                }
                JsonNode results = root.path("result");
                long maxId = -1;
                if (results.isArray()) {
                    for (JsonNode u : results) {
                        long updateId = u.path("update_id").asLong(-1);
                        if (updateId >= 0) {
                            maxId = Math.max(maxId, updateId);
                        }
                        handleUpdate(u);
                    }
                }
                if (maxId >= 0) {
                    nextOffset = maxId + 1;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("Telegram command listener interrupted");
                break;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Telegram command listener error: " + e.getMessage(), e);
                sleepQuiet(Duration.ofSeconds(5));
            }
        }
    }

    private void handleUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode() || message.isNull()) {
            return;
        }
        String text = message.path("text").asText("");
        if (text.isEmpty()) {
            return;
        }
        String chatId = message.path("chat").path("id").asText("");
        if (chatId.isEmpty()) {
            return;
        }
        if (isCommand(text, "/hello")) {
            try {
                sendMessage(chatId, "world!\n(" + reportHost + ")");
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.log(Level.WARNING, "Telegram /hello reply failed: " + e.getMessage(), e);
            }
            return;
        }
        if (isCommand(text, "/image")) {
            if (imageDeliveryBotToken == null) {
                try {
                    sendMessage(
                            chatId,
                            "Configure telegram.image.bot.token (env TELEGRAM_IMAGE_BOT_TOKEN) para enviar la imagen con un"
                                    + " segundo bot.");
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    LOG.log(Level.WARNING, "Telegram /image hint failed: " + e.getMessage(), e);
                }
                return;
            }
            Path tmp = null;
            try {
                tmp = topesFetcher.downloadToTempFile(smnTopesCentroImageUrl);
                String name = tmp.getFileName().toString();
                photoSender.sendPhoto(
                        imageDeliveryBotToken,
                        chatId,
                        tmp,
                        name,
                        "Topes nubosos — zona centro (SMN)\n" + reportHost);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.log(Level.WARNING, "Telegram /image failed: " + e.getMessage(), e);
                try {
                    sendMessage(chatId, "No se pudo enviar la imagen: " + e.getMessage());
                } catch (IOException | InterruptedException e2) {
                    if (e2 instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    LOG.log(Level.WARNING, "Telegram /image error reply failed: " + e2.getMessage(), e2);
                }
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                }
            }
        }
    }

    /** Matches {@code /hello} or {@code /hello@BotName}. */
    private static boolean isCommand(String messageText, String command) {
        String first = firstToken(messageText);
        if (!first.startsWith(command)) {
            return false;
        }
        if (first.length() == command.length()) {
            return true;
        }
        return first.length() > command.length() && first.charAt(command.length()) == '@';
    }

    private static String firstToken(String text) {
        int i = text.indexOf(' ');
        String t = i < 0 ? text : text.substring(0, i);
        return t.trim();
    }

    private void sendMessage(String chatId, String text) throws IOException, InterruptedException {
        String form =
                "chat_id="
                        + enc(chatId)
                        + "&text="
                        + enc(text)
                        + "&disable_web_page_preview=true";
        URI uri = telegramMethodUri("sendMessage");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Telegram sendMessage HTTP " + res.statusCode() + ": " + res.body());
        }
        String body = res.body();
        if (body == null || !body.contains("\"ok\":true")) {
            throw new IOException("Telegram sendMessage API error: " + body);
        }
    }

    private URI getUpdatesUri(long offset) throws IOException {
        try {
            String q = "timeout=" + LONG_POLL_TIMEOUT_SEC + "&offset=" + offset;
            return new URI("https", "api.telegram.org", "/bot" + botToken + "/getUpdates", q, null);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid bot token for getUpdates URL", e);
        }
    }

    private URI telegramMethodUri(String method) throws IOException {
        try {
            return new URI("https", "api.telegram.org", "/bot" + botToken + "/" + method, null, null);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid bot token for URL path", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void sleepQuiet(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
