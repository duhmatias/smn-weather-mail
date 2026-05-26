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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Long-polls {@code getUpdates} for a dedicated bot token and handles slash commands (e.g. {@code /hello}).
 * Replies via {@code sendMessage} to the chat that issued the command. {@code /current} replies are sent with an optional
 * second bot token (user must {@code /start} that bot too). {@code /subscribe current} (24 h) or
 * {@code /subscribe current day-schedule} (no condition sends 00:00–05:59 ART), and {@code /unsubscribe current}, add,
 * update, or remove this chat in the conditions-weather file (same {@code telegram.bot.token} as config for
 * delivery). {@code /current} tries SMN open-data
 * {@code tiepre} text first, then {@code /v1/georef/location/search} and ws1 (same as the pronóstico page).
 */
final class TelegramBotCommandListener implements Runnable {

    private enum LocsCommand {
        /** Omitted: keep file row as-is for locs. */
        UNCHANGED,
        /** User asked {@code locs all|default} — use every {@code smn.location.ids} location. */
        USE_DEFAULT,
        /** User listed ids after {@code locs}. */
        SET
    }

    private static final class SubscribeCommand {
        enum Kind {
            NOT,
            INVALID,
            PLAIN,
            UPSERT
        }

        static final SubscribeCommand NOT = new SubscribeCommand(Kind.NOT);
        static final SubscribeCommand INVALID = new SubscribeCommand(Kind.INVALID);
        static final SubscribeCommand PLAIN = new SubscribeCommand(Kind.PLAIN);

        final Kind kind;
        final boolean hasDay;
        final LocsCommand locs;
        final Set<Integer> locIds;

        private SubscribeCommand(Kind kind) {
            this.kind = kind;
            this.hasDay = false;
            this.locs = LocsCommand.UNCHANGED;
            this.locIds = null;
        }

        private SubscribeCommand(boolean hasDay, LocsCommand locs, Set<Integer> locIds) {
            this.kind = Kind.UPSERT;
            this.hasDay = hasDay;
            this.locs = locs;
            this.locIds = locIds;
        }
    }

    private static final Logger LOG = Logger.getLogger(TelegramBotCommandListener.class.getName());
    private static final int LONG_POLL_TIMEOUT_SEC = 30;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Telegram message length safety margin below 4096 (single-message / fallback chunking). */
    private static final int MESSAGE_CHUNK = 3800;
    /** Pause between Telegram sends when posting multiple /current replies (flood control). */
    private static final int MS_BETWEEN_CURRENT_MESSAGES = 550;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String botToken;
    /** Same label as condition/forecast footers ({@code smn.report.host} / hostname). */
    private final String reportHost;
    /** Nullable: {@code sendMessage} for {@code /current} output. */
    private final String currentConditionsBotToken;
    private final SmnClient smn;
    /** Same order as conditions mail loop — primes ws1 before georef for {@code /current}. */
    private final List<SmnClient.Station> smnPrimeStations;
    /** {@code null} when main condition Telegram is not configured. */
    private final Path conditionsSubscriberChatsFile;
    /**
     * {@code telegram.chat.ids} for conditions (for de-dupe on subscribe). Empty when main Telegram is off.
     */
    private final List<String> conditionsBaseChatIds;
    /** For {@code /validation} command. */
    private final Path measuresDir;
    private final ForecastDaySnapshotLog snapshotLog;

    TelegramBotCommandListener(
            String botToken,
            String reportHost,
            String currentConditionsBotToken,
            SmnClient smn,
            List<SmnClient.Station> smnPrimeStations,
            Path conditionsSubscriberChatsFile,
            List<String> conditionsBaseChatIds,
            Path measuresDir,
            ForecastDaySnapshotLog snapshotLog) {
        this.botToken = botToken;
        this.reportHost = reportHost != null && !reportHost.isBlank() ? reportHost.trim() : "unknown-host";
        this.currentConditionsBotToken =
                currentConditionsBotToken != null && !currentConditionsBotToken.isBlank()
                        ? currentConditionsBotToken.trim()
                        : null;
        this.smn = smn;
        this.smnPrimeStations = smnPrimeStations != null ? List.copyOf(smnPrimeStations) : List.of();
        this.conditionsSubscriberChatsFile = conditionsSubscriberChatsFile;
        this.conditionsBaseChatIds = conditionsBaseChatIds != null ? List.copyOf(conditionsBaseChatIds) : List.of();
        this.measuresDir = measuresDir;
        this.snapshotLog = snapshotLog;
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
        SubscribeCommand sub = parseSubscribeCommand(text);
        if (sub.kind != SubscribeCommand.Kind.NOT) {
            handleSubscribeCurrent(chatId, sub);
            return;
        }
        if (isUnsubscribeCurrentCommand(text)) {
            handleUnsubscribeCurrent(chatId);
            return;
        }
        if (isCurrentCommand(text)) {
            handleCurrentCommand(chatId, text);
            return;
        }
        if (isCommand(text, "/validation")) {
            handleValidationCommand(chatId);
        }
    }

    private void handleSubscribeCurrent(String chatId, SubscribeCommand cmd) {
        if (cmd.kind == SubscribeCommand.Kind.INVALID) {
            try {
                sendMessage(
                        chatId,
                        "Uso:\n"
                                + "• /subscribe current — suscribir (mismas ubicaciones que smn.location.ids en config)\n"
                                + "• /subscribe current day-schedule — nada de avisos 00:00–05:59 ART (Buenos_Aires)\n"
                                + "• /subscribe current locs 4864,10821 — solo esas ids SMN (CABA=4864, AEP=10821)\n"
                                + "• /subscribe current day-schedule locs 4864\n"
                                + "• /subscribe current locs all (o default) — quitar el filtro locs, todo lo de config\n"
                                + "Ids de ejemplo: "
                                + smnStationsReference());
            } catch (IOException | InterruptedException e) {
                logSendFailure(e);
            }
            return;
        }
        if (conditionsSubscriberChatsFile == null) {
            try {
                sendMessage(
                        chatId,
                        "Condiciones por Telegram no está configurado: hace falta telegram.bot.token y"
                                + " telegram.chat.ids en config (o TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_IDS).");
            } catch (IOException | InterruptedException e) {
                logSendFailure(e);
            }
            return;
        }
        try {
            if (cmd.kind == SubscribeCommand.Kind.PLAIN) {
                boolean added =
                        TelegramConditionsSubscriberChats.appendIfAbsent(
                                conditionsSubscriberChatsFile, chatId, conditionsBaseChatIds);
                if (added) {
                    sendMessage(
                            chatId,
                            "Listo: este chat (id "
                                    + chatId
                                    + ") recibirá avisos de condición (mismas estaciones que smn.location.ids). Asegurate"
                                    + " de /start con el bot principal (telegram.bot.token).");
                } else {
                    sendMessage(
                            chatId,
                            "Ya estabas en la lista (config o en "
                                    + conditionsSubscriberChatsFile.getFileName()
                                    + "). Otras: day-schedule, locs id… (manda /subscribe con algo inválido para el texto de ayuda).");
                }
                return;
            }
            TelegramConditionsSubscriberChats.SubscriberRow old =
                    TelegramConditionsSubscriberChats.findRow(conditionsSubscriberChatsFile, chatId);
            boolean dayF = (old != null && old.daySchedule()) || cmd.hasDay;
            Set<Integer> locF;
            if (cmd.locs == LocsCommand.UNCHANGED) {
                locF = old == null ? null : old.locFilter();
            } else if (cmd.locs == LocsCommand.USE_DEFAULT) {
                locF = null;
            } else {
                locF = new HashSet<>(cmd.locIds);
            }
            TelegramConditionsSubscriberChats.SubscriberRow nw =
                    new TelegramConditionsSubscriberChats.SubscriberRow(chatId, dayF, locF);
            boolean changed =
                    TelegramConditionsSubscriberChats.writeSubscriberRow(conditionsSubscriberChatsFile, nw);
            if (changed) {
                StringBuilder msg = new StringBuilder("Listo, guardado en " + conditionsSubscriberChatsFile.getFileName() + ".\n");
                msg.append(serializeSettingsSummary(nw));
                sendMessage(chatId, msg.toString());
            } else {
                sendMessage(
                        chatId,
                        "No hubo cambios (la fila era igual: "
                                + TelegramConditionsSubscriberChats.serializeRow(nw)
                                + ").");
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Telegram /subscribe current: " + e.getMessage(), e);
            try {
                sendMessage(chatId, "No se pudo guardar la suscripción: " + e.getMessage());
            } catch (IOException | InterruptedException e2) {
                logSendFailure(e2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String smnStationsReference() {
        if (smnPrimeStations.isEmpty()) {
            return "(smn.location.ids en config).";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < smnPrimeStations.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            SmnClient.Station s = smnPrimeStations.get(i);
            sb.append(s.label()).append(" id=").append(s.locationId());
        }
        return sb.toString() + ".";
    }

    private static String serializeSettingsSummary(TelegramConditionsSubscriberChats.SubscriberRow nw) {
        StringBuilder s = new StringBuilder();
        if (nw.locFilter() == null) {
            s.append("Ubicaciones: todas las de smn.location.ids (config / SMN_LOCATION_IDS).");
        } else {
            s.append("Solo smn avisos de ubicación: ").append(nw.locFilter()).append(" (ids SMN).");
        }
        s.append("\n");
        if (nw.daySchedule()) {
            s.append("Noche: sin avisos 00:00–05:59 ART (Buenos_Aires).");
        } else {
            s.append("Día/24 h: se envía también de noche.");
        }
        return s.toString();
    }

    private void handleUnsubscribeCurrent(String chatId) {
        if (conditionsSubscriberChatsFile == null) {
            try {
                sendMessage(
                        chatId,
                        "Condiciones por Telegram no está configurado: hace falta telegram.bot.token y"
                                + " telegram.chat.ids en config (o TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_IDS).");
            } catch (IOException | InterruptedException e) {
                logSendFailure(e);
            }
            return;
        }
        try {
            TelegramConditionsSubscriberChats.UnsubscribeResult r =
                    TelegramConditionsSubscriberChats.unsubscribeFromFile(
                            conditionsSubscriberChatsFile, chatId, conditionsBaseChatIds);
            if (r == TelegramConditionsSubscriberChats.UnsubscribeResult.REMOVED) {
                boolean alsoInConfig =
                        conditionsBaseChatIds.stream()
                                .anyMatch(s -> s != null && s.trim().equals(chatId));
                String msg =
                        "Listo: se quitó el id "
                                + chatId
                                + " del archivo de suscriptores ("
                                + conditionsSubscriberChatsFile.getFileName()
                                + ").";
                if (alsoInConfig) {
                    msg +=
                            " Este id sigue en telegram.chat.ids: mientras esté, el bot"
                                    + " principal puede seguir enviando condiciones a este chat; quitá el id de la"
                                    + " config si no lo querés.";
                }
                sendMessage(chatId, msg);
            } else if (r == TelegramConditionsSubscriberChats.UnsubscribeResult.NOT_IN_FILE_BUT_IN_CONFIG) {
                sendMessage(
                        chatId,
                        "Ese id solo está en telegram.chat.ids, no en el archivo de suscriptores. No se puede quitar con"
                                + " el bot: editá la config o TELEGRAM_CHAT_IDS y sacá ese chat id.");
            } else {
                sendMessage(
                        chatId,
                        "Ese id no estaba en el archivo de suscriptores; no había nada que quitar. Si nunca usaste"
                                + " /subscribe current, nunca se agregó acá.");
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Telegram /unsubscribe current: " + e.getMessage(), e);
            try {
                sendMessage(chatId, "No se pudo actualizar la desuscripción: " + e.getMessage());
            } catch (IOException | InterruptedException e2) {
                logSendFailure(e2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleCurrentCommand(String chatId, String messageText) {
        if (currentConditionsBotToken == null) {
            try {
                sendMessage(
                        chatId,
                        "Configure telegram.current.bot.token (env TELEGRAM_CURRENT_BOT_TOKEN) para enviar el resultado con"
                                + " el bot de respuesta.");
            } catch (IOException | InterruptedException e) {
                logSendFailure(e);
            }
            return;
        }
        String first = firstToken(messageText);
        String query =
                messageText.length() > first.length() ? messageText.substring(first.length()).trim() : "";
        if (query.isEmpty()) {
            try {
                sendMessage(
                        chatId,
                        "Uso: /current texto — misma búsqueda que el sitio SMN (georef), por subcadena en nombre/localidad/"
                                + "provincia.");
            } catch (IOException | InterruptedException e) {
                logSendFailure(e);
            }
            return;
        }
        LOG.info("/current chatId=" + chatId + " query=«" + query + "»");
        final CurrentConditionsQuery.Result r;
        try {
            r = CurrentConditionsQuery.run(query, smn, reportHost, smnPrimeStations);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "SMN /current query failed: " + e.getMessage(), e);
            try {
                sendMessage(chatId, "No se pudo consultar el buscador o el tiempo SMN: " + e.getMessage());
            } catch (IOException | InterruptedException e2) {
                logSendFailure(e2);
            }
            return;
        }
        try {
            List<String> chunks = r.chunks();
            if (chunks.size() == 1) {
                sendLongAsBot(currentConditionsBotToken, chatId, chunks.get(0));
            } else {
                for (int i = 0; i < chunks.size(); i++) {
                    postSendMessage(currentConditionsBotToken, chatId, chunks.get(i));
                    if (i < chunks.size() - 1) {
                        sleepMs(MS_BETWEEN_CURRENT_MESSAGES);
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            } else {
                LOG.log(Level.WARNING, "Telegram /current send failed: " + e.getMessage(), e);
            }
        }
    }

    private void handleValidationCommand(String chatId) {
        if (measuresDir == null || snapshotLog == null) {
            try {
                sendMessage(chatId, "Forecast validation is not configured.");
            } catch (IOException | InterruptedException e) {
                logSendFailure(e);
            }
            return;
        }
        LOG.info("/validation chatId=" + chatId);
        try {
            java.time.ZoneId artZone = java.time.ZoneId.of("America/Argentina/Buenos_Aires");
            java.time.LocalDate today = java.time.ZonedDateTime.now(artZone).toLocalDate();
            java.time.LocalDate dataDay = today.minusDays(1);

            // CABA station (locationId 4864)
            int cabaLocationId = 4864;
            String cabaLabel = "Capital Federal";

            List<MeasuresHistoryReader.MeasureRow> rows =
                    MeasuresHistoryReader.readDay(measuresDir, dataDay, cabaLocationId);
            Optional<MeasuresSummaryMessages.TempPeriod> observed = MeasuresSummaryMessages.aggregateTemps(rows);

            boolean observedRain = false;
            for (MeasuresHistoryReader.MeasureRow r : rows) {
                if (MeasuresHistoryReader.looksLikeRain(r.conditions())) {
                    observedRain = true;
                    break;
                }
            }

            List<ForecastDaySnapshotLog.SnapshotRow> forecastSnapshots =
                    snapshotLog.findLastSnapshotPerPriorDay(dataDay, cabaLocationId);

            String tg = ForecastValidationMessages.buildTelegramHtml(
                    cabaLabel, dataDay, observed, observedRain, forecastSnapshots, reportHost);

            sendLongAsBot(botToken, chatId, tg, true);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "/validation failed: " + e.getMessage(), e);
            try {
                sendMessage(chatId, "Could not generate validation: " + e.getMessage());
            } catch (IOException | InterruptedException e2) {
                logSendFailure(e2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepMs(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void logSendFailure(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        LOG.log(Level.WARNING, "Telegram command reply failed: " + e.getMessage(), e);
    }

    private static boolean isCurrentCommand(String messageText) {
        String first = firstToken(messageText);
        int at = first.indexOf('@');
        String base = at > 0 ? first.substring(0, at) : first;
        return "/current".equals(base);
    }

    /**
     * @see #handleSubscribeCurrent(String, SubscribeCommand) for full syntax: {@code /subscribe current} [ {@code
     *     day-schedule} | {@code locs} … ].
     */
    private static SubscribeCommand parseSubscribeCommand(String messageText) {
        if (!isCommand(messageText, "/subscribe")) {
            return SubscribeCommand.NOT;
        }
        String rest1 = afterFirstToken(messageText);
        if (rest1.isEmpty()) {
            return SubscribeCommand.NOT;
        }
        String t2 = firstToken(rest1);
        int at2 = t2.indexOf('@');
        String base2 = at2 > 0 ? t2.substring(0, at2) : t2;
        if (!"current".equalsIgnoreCase(base2)) {
            return SubscribeCommand.NOT;
        }
        String rest2 = afterFirstToken(rest1).trim();
        if (rest2.isEmpty()) {
            return SubscribeCommand.PLAIN;
        }
        List<String> words = new ArrayList<>();
        for (String w : rest2.split("\\s+")) {
            if (!w.isBlank()) {
                words.add(w);
            }
        }
        if (words.isEmpty()) {
            return SubscribeCommand.PLAIN;
        }
        List<String> w2 = new ArrayList<>();
        boolean hasDay = false;
        for (String w : words) {
            String b = stripAtBotSuffix(w);
            if ("day-schedule".equalsIgnoreCase(b)) {
                hasDay = true;
            } else {
                w2.add(b);
            }
        }
        if (w2.isEmpty()) {
            return new SubscribeCommand(hasDay, LocsCommand.UNCHANGED, null);
        }
        if (w2.size() < 2) {
            return SubscribeCommand.INVALID;
        }
        if (!"locs".equalsIgnoreCase(w2.get(0))) {
            return SubscribeCommand.INVALID;
        }
        String l1 = w2.get(1);
        if (l1.equalsIgnoreCase("all") || l1.equalsIgnoreCase("default")) {
            if (w2.size() > 2) {
                return SubscribeCommand.INVALID;
            }
            return new SubscribeCommand(hasDay, LocsCommand.USE_DEFAULT, null);
        }
        Set<Integer> locs = new TreeSet<>();
        for (int j = 1; j < w2.size(); j++) {
            for (String p : w2.get(j).split(",")) {
                if (p.isBlank()) {
                    continue;
                }
                try {
                    locs.add(Integer.parseInt(p.trim()));
                } catch (NumberFormatException e) {
                    return SubscribeCommand.INVALID;
                }
            }
        }
        if (locs.isEmpty()) {
            return SubscribeCommand.INVALID;
        }
        return new SubscribeCommand(hasDay, LocsCommand.SET, locs);
    }

    private static String stripAtBotSuffix(String t) {
        int at = t.indexOf('@');
        if (at > 0) {
            return t.substring(0, at);
        }
        return t;
    }

    private static String afterFirstToken(String text) {
        int i = text.indexOf(' ');
        if (i < 0) {
            return "";
        }
        return text.substring(i + 1).trim();
    }

    private static boolean isUnsubscribeCurrentCommand(String messageText) {
        return isCommandWithSubcommand(messageText, "/unsubscribe", "current");
    }

    /**
     * {@code /command subcommand} (optional {@code @BotName} on the second token), e.g. {@code /subscribe current}.
     */
    private static boolean isCommandWithSubcommand(String messageText, String command, String sub) {
        if (!isCommand(messageText, command)) {
            return false;
        }
        String first = firstToken(messageText);
        String rest = messageText.length() > first.length() ? messageText.substring(first.length()).trim() : "";
        if (rest.isEmpty()) {
            return false;
        }
        String second = firstToken(rest);
        int at = second.indexOf('@');
        String base = at > 0 ? second.substring(0, at) : second;
        return sub.equalsIgnoreCase(base);
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
        postSendMessage(botToken, chatId, text);
    }

    private void sendLongAsBot(String token, String chatId, String text) throws IOException, InterruptedException {
        sendLongAsBot(token, chatId, text, false);
    }

    private void sendLongAsBot(String token, String chatId, String text, boolean html) throws IOException, InterruptedException {
        List<String> parts = chunkForTelegram(text, MESSAGE_CHUNK);
        for (int i = 0; i < parts.size(); i++) {
            postSendMessage(token, chatId, parts.get(i), html);
            if (i + 1 < parts.size()) {
                Thread.sleep(400);
            }
        }
    }

    private static List<String> chunkForTelegram(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLen, text.length());
            if (end < text.length()) {
                int br = text.lastIndexOf("\n\n", end);
                if (br <= start) {
                    br = text.lastIndexOf('\n', end);
                }
                if (br > start + maxLen / 4) {
                    end = br;
                }
            }
            String part = text.substring(start, end).trim();
            if (!part.isEmpty()) {
                out.add(part);
            }
            start = end;
        }
        if (out.isEmpty() && !text.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    private void postSendMessage(String token, String chatId, String text) throws IOException, InterruptedException {
        postSendMessage(token, chatId, text, false);
    }

    private void postSendMessage(String token, String chatId, String text, boolean html) throws IOException, InterruptedException {
        String form =
                "chat_id="
                        + enc(chatId)
                        + "&text="
                        + enc(text)
                        + (html ? "&parse_mode=HTML" : "")
                        + "&disable_web_page_preview=true";
        URI uri = telegramMethodUri(token, "sendMessage");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .timeout(Duration.ofSeconds(60))
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

    private URI telegramMethodUri(String token, String method) throws IOException {
        try {
            return new URI("https", "api.telegram.org", "/bot" + token + "/" + method, null, null);
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
