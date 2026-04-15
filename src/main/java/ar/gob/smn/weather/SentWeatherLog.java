package ar.gob.smn.weather;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persists observation keys already emailed ({@code locationId|instant}) so the same SMN snapshot
 * is not sent twice across restarts.
 */
final class SentWeatherLog {

    private final Path path;
    private final Set<String> sent;

    private SentWeatherLog(Path path, Set<String> sent) {
        this.path = path;
        this.sent = sent;
    }

    static SentWeatherLog open(Path path) throws IOException {
        Set<String> keys = new HashSet<>();
        if (Files.isRegularFile(path)) {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    keys.add(t);
                }
            }
        }
        return new SentWeatherLog(path, keys);
    }

    static String key(int locationId, Instant observationInstant) {
        return locationId + "|" + observationInstant;
    }

    /**
     * For each location id, the ART hour of the most recently mailed observation (by API {@code date} instant).
     */
    synchronized Map<Integer, ZonedDateTime> latestMailedObservationHourPerLocation(ZoneId zone) {
        Map<Integer, Instant> maxInstant = new HashMap<>();
        for (String key : sent) {
            int pipe = key.indexOf('|');
            if (pipe <= 0 || pipe >= key.length() - 1) {
                continue;
            }
            try {
                int locationId = Integer.parseInt(key.substring(0, pipe));
                Instant instant = Instant.parse(key.substring(pipe + 1));
                maxInstant.merge(locationId, instant, (a, b) -> a.isAfter(b) ? a : b);
            } catch (RuntimeException ignored) {
                // malformed line
            }
        }
        Map<Integer, ZonedDateTime> hours = new HashMap<>();
        for (Map.Entry<Integer, Instant> e : maxInstant.entrySet()) {
            hours.put(
                    e.getKey(),
                    e.getValue().atZone(zone).truncatedTo(ChronoUnit.HOURS));
        }
        return hours;
    }

    synchronized boolean contains(String key) {
        return sent.contains(key);
    }

    synchronized void append(String key) throws IOException {
        if (sent.contains(key)) {
            return;
        }
        sent.add(key);
        Files.writeString(
                path,
                key + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
