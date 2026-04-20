package ar.gob.smn.weather;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** Dedupe file for scheduled measures summaries (daily / weekly / monthly at 8:00 ART). */
final class MeasuresSummarySentLog {

    private final Path path;
    private final Set<String> keys;

    private MeasuresSummarySentLog(Path path, Set<String> keys) {
        this.path = path;
        this.keys = keys;
    }

    static MeasuresSummarySentLog open(Path path) throws IOException {
        Set<String> keys = new HashSet<>();
        if (Files.isRegularFile(path)) {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    keys.add(t);
                }
            }
        }
        return new MeasuresSummarySentLog(path, keys);
    }

    synchronized boolean contains(String key) {
        return keys.contains(key);
    }

    synchronized void record(String key) throws IOException {
        if (keys.contains(key)) {
            return;
        }
        keys.add(key);
        Files.writeString(
                path,
                key + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
