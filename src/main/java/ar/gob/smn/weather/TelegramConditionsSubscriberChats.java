package ar.gob.smn.weather;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Appends to and reads a line-based list of extra Telegram {@code chat_id} values for condition-weather
 * {@link TelegramNotifier} sends (merged with {@code telegram.chat.ids} from config). Lines are {@code id},
 * {@code id;locs=4864,10821}, {@code id;day-schedule}, and combinations, e.g. {@code id;locs=4864;day-schedule}. When
 * {@code locs=…} is omitted, the subscriber gets every location in config {@code smn.location.ids} (same as main
 * chats). With {@code locs=}, only those SMN location ids are messaged.
 */
final class TelegramConditionsSubscriberChats {

    private static final Pattern CHAT_ID = Pattern.compile("-?\\d+");
    private static final String LINE_SUFFIX_DAY_SCHEDULE = "day-schedule";
    private static final String LOCS_KEY = "locs";
    private static final Object SUBSCRIBE_IO_LOCK = new Object();

    /** One subscriber row: {@code locFilter} null = use all locations from {@code smn.location.ids}. */
    static final class SubscriberRow {
        private final String chatId;
        private final boolean daySchedule;
        /**
         * When non-null, only these SMN location ids. {@code null} = same set as the process-wide poll (config).
         * Empty is disallowed.
         */
        private final Set<Integer> locFilter;

        SubscriberRow(String chatId, boolean daySchedule, Set<Integer> locFilter) {
            this.chatId = chatId;
            this.daySchedule = daySchedule;
            this.locFilter = locFilter;
        }

        String chatId() {
            return chatId;
        }

        boolean daySchedule() {
            return daySchedule;
        }

        /** @return null if this row wants every polled location, else a non-empty set. */
        Set<Integer> locFilter() {
            return locFilter;
        }
    }

    /** Result of {@link #unsubscribeFromFile(Path, String, List)}. */
    enum UnsubscribeResult {
        REMOVED,
        NOT_IN_FILE_BUT_IN_CONFIG,
        NOT_IN_FILE_NOT_IN_CONFIG
    }

    private TelegramConditionsSubscriberChats() {}

    static String dataLineChatId(String line) {
        if (line == null) {
            return null;
        }
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) {
            return null;
        }
        int semi = t.indexOf(';');
        String head = semi < 0 ? t : t.substring(0, semi).trim();
        if (CHAT_ID.matcher(head).matches()) {
            return head;
        }
        if (CHAT_ID.matcher(t).matches()) {
            return t;
        }
        return null;
    }

    static SubscriberRow parseSubscriberRow(String line) {
        if (line == null) {
            return null;
        }
        String id = dataLineChatId(line);
        if (id == null) {
            return null;
        }
        String t = line.trim();
        if (!t.contains(";")) {
            return new SubscriberRow(id, false, null);
        }
        boolean day = false;
        Set<Integer> locs = null;
        for (String seg : t.split(";", -1)) {
            String s = seg.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.equals(id)) {
                continue;
            }
            if (s.equalsIgnoreCase(LINE_SUFFIX_DAY_SCHEDULE)) {
                day = true;
                continue;
            }
            String sLow = s.toLowerCase();
            if (sLow.startsWith(LOCS_KEY + "=") || sLow.startsWith(LOCS_KEY + ":")) {
                int eq = s.indexOf('=');
                if (eq < 0) {
                    eq = s.indexOf(':');
                }
                String list = s.substring(eq + 1).trim();
                if (list.isEmpty()) {
                    return null;
                }
                Set<Integer> parsed = parseLocsList(list);
                if (parsed == null) {
                    return null;
                }
                locs = parsed;
                continue;
            }
            return null;
        }
        return new SubscriberRow(id, day, locs);
    }

    private static Set<Integer> parseLocsList(String list) {
        String[] parts = list.split("[,;\\s]+");
        Set<Integer> out = new TreeSet<>();
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                int v = Integer.parseInt(s);
                out.add(v);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableSet(out);
    }

    static String serializeRow(SubscriberRow row) {
        String id = row.chatId();
        StringBuilder sb = new StringBuilder(id);
        if (row.locFilter() != null) {
            sb.append(';');
            sb.append(LOCS_KEY);
            sb.append('=');
            boolean first = true;
            for (int x : new TreeSet<>(row.locFilter())) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(x);
            }
        }
        if (row.daySchedule()) {
            sb.append(';').append(LINE_SUFFIX_DAY_SCHEDULE);
        }
        return sb.toString();
    }

    static List<SubscriberRow> readAllRows(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        List<SubscriberRow> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String id = dataLineChatId(line);
            if (id == null) {
                continue;
            }
            SubscriberRow r = parseSubscriberRow(line);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    static List<String> readIds(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String id = dataLineChatId(line);
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    static Set<String> readDayScheduleChatIds(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (SubscriberRow r : readAllRows(file)) {
            if (r.daySchedule()) {
                out.add(r.chatId());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Chats that restrict by location: map value is the allowed SMN location ids. Chats <i>not</i> in the map (file
     * row without {@code locs=}) get every id from config {@code smn.location.ids}.
     */
    static java.util.Map<String, Set<Integer>> readLocationFilterForFileSubscribers(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return java.util.Map.of();
        }
        java.util.HashMap<String, Set<Integer>> m = new java.util.HashMap<>();
        for (SubscriberRow r : readAllRows(file)) {
            if (r.locFilter() != null) {
                m.put(r.chatId(), r.locFilter());
            }
        }
        return Collections.unmodifiableMap(m);
    }

    static List<String> mergeBaseWithFile(List<String> base, Path file) throws IOException {
        List<String> fromFile = readIds(file);
        if (fromFile.isEmpty()) {
            return base;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>(base);
        for (String id : fromFile) {
            set.add(id);
        }
        return List.copyOf(set);
    }

    static SubscriberRow findRow(Path file, String chatId) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String from = dataLineChatId(line);
            if (from != null && from.equals(chatId.trim())) {
                return parseSubscriberRow(line);
            }
        }
        return null;
    }

    static boolean appendIfAbsent(Path file, String chatId, List<String> alreadyInConfig) throws IOException {
        if (file == null) {
            return false;
        }
        String id = chatId == null ? "" : chatId.trim();
        if (id.isEmpty() || !CHAT_ID.matcher(id).matches()) {
            throw new IOException("Invalid chat_id for subscribe: " + chatId);
        }
        for (String c : alreadyInConfig) {
            if (c != null && c.trim().equals(id)) {
                return false;
            }
        }
        Path abs = file.toAbsolutePath();
        synchronized (SUBSCRIBE_IO_LOCK) {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String fromLine = dataLineChatId(line);
                    if (id.equals(fromLine)) {
                        return false;
                    }
                }
            } else {
                Path parent = abs.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            }
            Files.writeString(
                    file,
                    id + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
        return true;
    }

    static boolean writeSubscriberRow(Path file, SubscriberRow row) throws IOException {
        if (file == null) {
            return false;
        }
        String id = row.chatId();
        if (id.isEmpty() || !CHAT_ID.matcher(id).matches()) {
            throw new IOException("Invalid chat_id: " + id);
        }
        String newLine = serializeRow(row);
        Path abs = file.toAbsolutePath();
        synchronized (SUBSCRIBE_IO_LOCK) {
            if (!Files.isRegularFile(file)) {
                Path parent = abs.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            }
            List<String> original =
                    Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
            List<String> out = new ArrayList<>();
            for (String line : original) {
                String fromId = dataLineChatId(line);
                if (id.equals(fromId)) {
                    continue;
                }
                out.add(line);
            }
            out.add(newLine);
            String newBody = out.isEmpty() ? "" : String.join("\n", out) + "\n";
            String oldBody =
                    Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
            if (newBody.equals(oldBody)) {
                return false;
            }
            Files.writeString(
                    file,
                    newBody,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        }
    }

    /**
     * Day-schedule only, preserving other fields from an existing line (or new row).
     *
     * @deprecated in favor of {@link #writeSubscriberRow(Path, SubscriberRow)}; kept for minimal call-site churn
     */
    static boolean upsertDayScheduleInFile(Path file, String chatId) throws IOException {
        if (file == null) {
            return false;
        }
        String id = chatId == null ? "" : chatId.trim();
        SubscriberRow existing = findRow(file, id);
        if (existing == null) {
            return writeSubscriberRow(file, new SubscriberRow(id, true, null));
        }
        return writeSubscriberRow(
                file, new SubscriberRow(id, true, existing.locFilter()));
    }

    static UnsubscribeResult unsubscribeFromFile(Path file, String chatId, List<String> configChatIds)
            throws IOException {
        if (file == null) {
            throw new IOException("subscriber file path is null");
        }
        String id = chatId == null ? "" : chatId.trim();
        if (id.isEmpty() || !CHAT_ID.matcher(id).matches()) {
            throw new IOException("Invalid chat_id for unsubscribe: " + chatId);
        }
        synchronized (SUBSCRIBE_IO_LOCK) {
            if (!Files.isRegularFile(file)) {
                if (inConfigList(id, configChatIds)) {
                    return UnsubscribeResult.NOT_IN_FILE_BUT_IN_CONFIG;
                }
                return UnsubscribeResult.NOT_IN_FILE_NOT_IN_CONFIG;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(lines.size());
            boolean removed = false;
            for (String line : lines) {
                String fromId = dataLineChatId(line);
                if (id.equals(fromId)) {
                    removed = true;
                    continue;
                }
                out.add(line);
            }
            if (!removed) {
                if (inConfigList(id, configChatIds)) {
                    return UnsubscribeResult.NOT_IN_FILE_BUT_IN_CONFIG;
                }
                return UnsubscribeResult.NOT_IN_FILE_NOT_IN_CONFIG;
            }
            String body = out.isEmpty() ? "" : String.join("\n", out) + "\n";
            Files.writeString(
                    file,
                    body,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return UnsubscribeResult.REMOVED;
        }
    }

    private static boolean inConfigList(String id, List<String> configChatIds) {
        for (String c : configChatIds) {
            if (c != null && c.trim().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
