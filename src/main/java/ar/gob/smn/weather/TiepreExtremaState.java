package ar.gob.smn.weather;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Persists the last tiepre-extrema fingerprint so unchanged snapshots are not re-sent. */
final class TiepreExtremaState {

    private TiepreExtremaState() {}

    static Optional<String> readLastFingerprint(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        String line = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (line.isEmpty()) {
            return Optional.empty();
        }
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl).trim();
        }
        return line.isEmpty() ? Optional.empty() : Optional.of(line);
    }

    static void writeFingerprint(Path path, String fingerprint) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, fingerprint + '\n', StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
