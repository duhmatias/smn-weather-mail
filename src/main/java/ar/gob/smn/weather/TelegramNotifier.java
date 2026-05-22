package ar.gob.smn.weather;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Sends plain-text messages via the Telegram Bot API (same content as the weather email). */
final class TelegramNotifier {

    private static final Logger LOG = Logger.getLogger(TelegramNotifier.class.getName());
    private static final int TELEGRAM_TEXT_LIMIT = 4096;
    private static final ZoneId CONDITIONS_QUIET_TZ = ZoneId.of("America/Argentina/Buenos_Aires");

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String botToken;
    private final List<String> baseChatIds;
    /** If non-{@code null}, merged on each send with {@link #baseChatIds} (see {@code /subscribe current} / {@code
     * /unsubscribe current}). */
    private final Path conditionsSubscriberChatsFile;

    TelegramNotifier(Config.Telegram telegram) {
        this(telegram, null);
    }

    /**
     * @param conditionsSubscriberChatsFile optional file of extra {@code chat_id} lines (same bot as {@code telegram});
     *     read on every send
     */
    TelegramNotifier(Config.Telegram telegram, Path conditionsSubscriberChatsFile) {
        this.botToken = telegram.botToken();
        this.baseChatIds = telegram.chatIds();
        this.conditionsSubscriberChatsFile = conditionsSubscriberChatsFile;
    }

    void sendWeatherMessage(String emailSubject, String emailBody) throws IOException, InterruptedException {
        sendPlainText(emailSubject + "\n\n" + emailBody);
    }

    /** Single body (e.g. formatted forecast) without subject/body pairing. */
    void sendPlainText(String text) throws IOException, InterruptedException {
        if (text.length() > TELEGRAM_TEXT_LIMIT) {
            text = text.substring(0, TELEGRAM_TEXT_LIMIT - 3) + "...";
        }
        for (String chatId : recipientChatIds()) {
            sendToChat(chatId.trim(), text, false);
        }
    }

    /** {@code parse_mode=HTML} — caller must escape dynamic text (e.g. {@code &lt;} for {@code <}). */
    void sendHtml(String html) throws IOException, InterruptedException {
        if (html.length() > TELEGRAM_TEXT_LIMIT) {
            html = html.substring(0, TELEGRAM_TEXT_LIMIT - 3) + "...";
        }
        for (String chatId : recipientChatIds()) {
            sendToChat(chatId.trim(), html, true);
        }
    }

    /**
     * Like {@link #sendHtml(String)} for condition bulletins, but only sends to subscribers that want this
     * {@code locationId} (file line {@code locs=…} or all locations when the line has no locs; config ids always
     * receive). Subject to day-schedule quiet hours.
     */
    void sendConditionHtml(String html, int locationId) throws IOException, InterruptedException {
        if (html.length() > TELEGRAM_TEXT_LIMIT) {
            html = html.substring(0, TELEGRAM_TEXT_LIMIT - 3) + "...";
        }
        List<String> ids;
        try {
            ids = conditionRecipientChatIds(locationId);
        } catch (IOException e) {
            LOG.log(
                    Level.WARNING,
                    "Condition Telegram recipient list failed, sending without loc= filter: " + e.getMessage(),
                    e);
            sendHtml(html);
            return;
        }
        for (String chatId : ids) {
            sendToChat(chatId.trim(), html, true);
        }
    }

    private List<String> conditionRecipientChatIds(int locationId) throws IOException {
        if (conditionsSubscriberChatsFile == null) {
            return recipientChatIds();
        }
        List<String> merged =
                TelegramConditionsSubscriberChats.mergeBaseWithFile(baseChatIds, conditionsSubscriberChatsFile);
        Set<String> daySchedule =
                TelegramConditionsSubscriberChats.readDayScheduleChatIds(conditionsSubscriberChatsFile);
        Map<String, Set<Integer>> locRestrict =
                TelegramConditionsSubscriberChats.readLocationFilterForFileSubscribers(
                        conditionsSubscriberChatsFile);
        Set<String> base = new HashSet<>();
        for (String b : baseChatIds) {
            if (b != null) {
                base.add(b.trim());
            }
        }
        List<String> out = new ArrayList<>(merged.size());
        for (String raw : merged) {
            String c = raw.trim();
            if (daySchedule.contains(c) && isInOvernightQuietWindowArt()) {
                LOG.fine(() -> "Telegram conditions: skip chat " + c + " (day-schedule, 00:00–05:59 ART quiet)");
                continue;
            }
            if (base.contains(c)) {
                out.add(c);
                continue;
            }
            Set<Integer> need = locRestrict.get(c);
            if (need != null && !need.contains(locationId)) {
                LOG.fine(() -> "Telegram conditions: skip chat " + c + " (locs= does not include " + locationId + ")");
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private List<String> recipientChatIds() {
        if (conditionsSubscriberChatsFile == null) {
            return baseChatIds;
        }
        try {
            // no per-station filter (non-conditions or callers that are not a single location)
            return conditionRecipientChatIdsUnfiltered();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read conditions subscriber chats file: " + e.getMessage(), e);
            return baseChatIds;
        }
    }

    /** Merged list with day-schedule filter only (no per-location file filter). */
    private List<String> conditionRecipientChatIdsUnfiltered() throws IOException {
        List<String> merged =
                TelegramConditionsSubscriberChats.mergeBaseWithFile(baseChatIds, conditionsSubscriberChatsFile);
        Set<String> daySchedule =
                TelegramConditionsSubscriberChats.readDayScheduleChatIds(conditionsSubscriberChatsFile);
        if (daySchedule.isEmpty() || !isInOvernightQuietWindowArt()) {
            return merged;
        }
        List<String> out = new ArrayList<>(merged.size());
        for (String chatId : merged) {
            String c = chatId.trim();
            if (daySchedule.contains(c)) {
                LOG.fine(() -> "Telegram conditions: skip chat " + c + " (day-schedule, 00:00–05:59 ART quiet)");
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /**
     * Overnight quiet for {@code ;day-schedule} subscribers: local clock 00:00 through 05:59 in
     * America/Argentina/Buenos_Aires — no condition bulletins in that window.
     */
    private static boolean isInOvernightQuietWindowArt() {
        int hour = ZonedDateTime.now(CONDITIONS_QUIET_TZ).getHour();
        return hour >= 0 && hour < 6;
    }

    private void sendToChat(String chatId, String text, boolean html) throws IOException, InterruptedException {
        String form =
                "chat_id="
                        + enc(chatId)
                        + "&text="
                        + enc(text)
                        + (html ? "&parse_mode=HTML" : "")
                        + "&disable_web_page_preview=true";
        URI uri = telegramMethodUri("sendMessage");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) {
            throw new IOException(
                    "Telegram HTTP 404: bot token not recognized. Copy the token from @BotFather exactly "
                            + "(format 123456789:AAH…). Do not put \"bot\" before it in config — the URL already "
                            + "includes bot. Response: "
                            + res.body());
        }
        if (res.statusCode() == 403) {
            String b = res.body();
            if (b != null && b.contains("bots can't send messages to bots")) {
                throw new IOException(
                        "Telegram HTTP 403: chat_id "
                                + chatId
                                + " is a bot account. Use your personal user id (open your bot in Telegram, "
                                + "press Start, then get updates from getUpdates or @userinfobot). "
                                + "Response: "
                                + b);
            }
            throw new IOException(
                    "Telegram HTTP 403: bot cannot message this chat (wrong chat_id, user blocked the bot, "
                            + "or user never pressed Start). Response: "
                            + b);
        }
        if (res.statusCode() == 400) {
            String b = res.body() != null ? res.body() : "";
            if (b.contains("chat not found")) {
                throw new IOException(
                        "Telegram HTTP 400 (chat not found) for chat_id="
                                + chatId
                                + ". That id is invalid for this bot, or no conversation exists yet. "
                                + "Fix: open this bot in Telegram from the account or group you want, press Start "
                                + "(or add the bot to the group), then set chat id from "
                                + "https://api.telegram.org/bot<token>/getUpdates (look at message.chat.id). "
                                + "Groups/supergroups/channels often use negative ids. API response: "
                                + b);
            }
            throw new IOException(
                    "Telegram HTTP 400 (bad request) for chat_id=" + chatId + ": " + b);
        }
        if (res.statusCode() != 200) {
            throw new IOException("Telegram HTTP " + res.statusCode() + ": " + res.body());
        }
        String body = res.body();
        if (body == null || !body.contains("\"ok\":true")) {
            throw new IOException("Telegram API error: " + body);
        }
        LOG.fine(() -> "Telegram message sent to chat " + chatId);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Path {@code /bot&lt;token&gt;/&lt;method&gt;} via multi-arg URI so ':' in the token stays valid. */
    private URI telegramMethodUri(String method) throws IOException {
        try {
            return new URI(
                    "https",
                    "api.telegram.org",
                    "/bot" + botToken + "/" + method,
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid telegram.bot.token for URL path", e);
        }
    }
}
