package ar.gob.smn.weather;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * On/after the first day of each month, zips the calendar month from <em>two months earlier</em> under
 * {@code smn-measures} and {@code smn-forecast-history} ({@code YYYY/MM/}), then deletes those directories.
 * Example: on June 1 (or later if catch-up), archives April ({@code .../2026/04/}).
 */
final class HistoricalMonthArchive {

    private static final Logger LOG = Logger.getLogger(HistoricalMonthArchive.class.getName());

    private HistoricalMonthArchive() {}

    /**
     * If the archive for {@code YearMonth.now(zone).minusMonths(2)} is not yet recorded and the date is on or after
     * the first day when that archive is due, zips month folders (if any data exists) and removes originals.
     * If there is nothing to archive, records {@code ARCHIVED_EMPTY|…} so we do not retry forever.
     */
    static void tryProcess(
            Path measuresDir,
            Path forecastHistoryDir,
            Path stateFile,
            Path zipOutputDir,
            ZoneId zone)
            throws IOException {
        LocalDate today = LocalDate.now(zone);
        YearMonth target = YearMonth.from(today.minusMonths(2));
        LocalDate earliestRun = target.plusMonths(2).atDay(1);
        if (today.isBefore(earliestRun)) {
            return;
        }

        String keyArchived = "ARCHIVED|" + target;
        String keyEmpty = "ARCHIVED_EMPTY|" + target;

        ArchiveState state = ArchiveState.read(stateFile);
        if (state.contains(keyArchived) || state.contains(keyEmpty)) {
            return;
        }

        Path monthMeasures = measuresDir
                .resolve(String.format(Locale.ROOT, "%04d", target.getYear()))
                .resolve(String.format(Locale.ROOT, "%02d", target.getMonthValue()));
        Path monthForecast = forecastHistoryDir
                .resolve(String.format(Locale.ROOT, "%04d", target.getYear()))
                .resolve(String.format(Locale.ROOT, "%02d", target.getMonthValue()));

        boolean hasMeasures = directoryHasFiles(monthMeasures);
        boolean hasForecast = directoryHasFiles(monthForecast);

        if (!hasMeasures && !hasForecast) {
            LOG.info(() -> "Historical archive: no data for " + target + " under measures or forecast-history — skipping.");
            state.appendLine(keyEmpty);
            state.write(stateFile);
            return;
        }

        Files.createDirectories(zipOutputDir);
        String zipName =
                String.format(Locale.ROOT, "smn-history-%04d-%02d.zip", target.getYear(), target.getMonthValue());
        Path zipPath = zipOutputDir.resolve(zipName);

        if (Files.isRegularFile(zipPath)) {
            LOG.warning(() -> "Historical archive: zip already exists, replacing: " + zipPath.toAbsolutePath());
            Files.delete(zipPath);
        }

        try (OutputStream fos = Files.newOutputStream(zipPath, StandardOpenOption.CREATE_NEW);
                ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
            if (hasMeasures) {
                zipDirectory(zos, monthMeasures, "smn-measures");
            }
            if (hasForecast) {
                zipDirectory(zos, monthForecast, "smn-forecast-history");
            }
        }

        if (hasMeasures) {
            deleteTree(monthMeasures);
        }
        if (hasForecast) {
            deleteTree(monthForecast);
        }

        state.appendLine(keyArchived);
        state.write(stateFile);
        LOG.info(() -> "Historical archive: wrote " + zipPath.toAbsolutePath() + " and removed month dirs for " + target);
    }

    private static boolean directoryHasFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return false;
        }
        Path[] found = new Path[1];
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        found[0] = file;
                        return FileVisitResult.TERMINATE;
                    }
                });
        return found[0] != null;
    }

    private static void zipDirectory(ZipOutputStream zos, Path sourceDir, String zipRootPrefix) throws IOException {
        String prefix = zipRootPrefix.endsWith("/") ? zipRootPrefix : zipRootPrefix + "/";
        Files.walkFileTree(
                sourceDir,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Path rel = sourceDir.relativize(file);
                        String entryName = prefix + rel.toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (exc != null) {
                            throw exc;
                        }
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private static final class ArchiveState {
        private final Set<String> lines;

        ArchiveState(Set<String> lines) {
            this.lines = lines;
        }

        static ArchiveState read(Path path) throws IOException {
            Set<String> set = new HashSet<>();
            if (Files.isRegularFile(path)) {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        set.add(t);
                    }
                }
            }
            return new ArchiveState(set);
        }

        boolean contains(String key) {
            return lines.contains(key);
        }

        void appendLine(String key) {
            lines.add(key);
        }

        void write(Path path) throws IOException {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder sb = new StringBuilder();
            for (String s : lines) {
                sb.append(s).append('\n');
            }
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    /** Logs at INFO once if state shows all expected keys for months already processed (cheap sanity check). */
    static void validateStateOnStartup(Path stateFile, ZoneId zone) {
        try {
            if (!Files.isRegularFile(stateFile)) {
                return;
            }
            YearMonth mustHaveArchived = YearMonth.from(LocalDate.now(zone).minusMonths(2));
            String key = "ARCHIVED|" + mustHaveArchived;
            String keyE = "ARCHIVED_EMPTY|" + mustHaveArchived;
            ArchiveState st = ArchiveState.read(stateFile);
            LocalDate today = LocalDate.now(zone);
            LocalDate due = mustHaveArchived.plusMonths(2).atDay(1);
            if (!today.isBefore(due) && !st.contains(key) && !st.contains(keyE)) {
                LOG.info(
                        "Historical archive: pending for "
                                + mustHaveArchived
                                + " (will run when scheduled check executes).");
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Historical archive state read: " + e.getMessage(), e);
        }
    }
}
