package ar.gob.smn.weather;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Tracks which CABA forecast bulletins (by SMN {@code updated}) were already sent to Telegram for the
 * morning (~5:30 ART) and evening (~17:30 ART) cycles, so we do not duplicate sends and can tell when the
 * API is still serving the previous slot's bulletin.
 */
final class SentForecastLog {

    private final Path path;
    private LocalDate morningDay;
    private String morningUpdated;
    private LocalDate eveningDay;
    private String eveningUpdated;

    private SentForecastLog(Path path) {
        this.path = path;
    }

    static SentForecastLog open(Path path) throws IOException {
        SentForecastLog log = new SentForecastLog(path);
        if (!Files.isRegularFile(path)) {
            return log;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            int p1 = t.indexOf('|');
            int p2 = p1 < 0 ? -1 : t.indexOf('|', p1 + 1);
            if (p1 <= 0 || p2 <= p1) {
                continue;
            }
            try {
                String kind = t.substring(0, p1);
                LocalDate day = LocalDate.parse(t.substring(p1 + 1, p2));
                String updated = t.substring(p2 + 1);
                if ("MORNING".equals(kind)) {
                    log.morningDay = day;
                    log.morningUpdated = updated;
                } else if ("EVENING".equals(kind)) {
                    log.eveningDay = day;
                    log.eveningUpdated = updated;
                }
            } catch (RuntimeException ignored) {
                // skip malformed
            }
        }
        return log;
    }

    /**
     * Send morning bulletin: new {@code updated}, not a duplicate of today's morning send, and not the
     * evening bulletin still exposed by the API before the morning refresh.
     */
    synchronized boolean shouldSendMorning(LocalDate todayArt, String updated) {
        if (updated == null || updated.isBlank()) {
            return false;
        }
        if (morningDay != null && morningDay.equals(todayArt) && updated.equals(morningUpdated)) {
            return false;
        }
        if (eveningUpdated != null
                && updated.equals(eveningUpdated)
                && eveningDay != null
                && (eveningDay.equals(todayArt) || eveningDay.equals(todayArt.minusDays(1)))) {
            return false;
        }
        return true;
    }

    /**
     * Send evening bulletin: new {@code updated}, not duplicate of today's evening, and not still on
     * the morning bulletin for today.
     */
    synchronized boolean shouldSendEvening(LocalDate todayArt, String updated) {
        if (updated == null || updated.isBlank()) {
            return false;
        }
        if (eveningDay != null && eveningDay.equals(todayArt) && updated.equals(eveningUpdated)) {
            return false;
        }
        if (morningUpdated != null
                && updated.equals(morningUpdated)
                && morningDay != null
                && morningDay.equals(todayArt)) {
            return false;
        }
        return true;
    }

    synchronized void recordMorning(LocalDate todayArt, String updated) throws IOException {
        morningDay = todayArt;
        morningUpdated = updated;
        rewrite();
    }

    synchronized void recordEvening(LocalDate todayArt, String updated) throws IOException {
        eveningDay = todayArt;
        eveningUpdated = updated;
        rewrite();
    }

    /** Whether we already stored today's morning bulletin (after a successful Telegram send). */
    synchronized boolean hasMorningRecordedFor(LocalDate todayArt) {
        return morningDay != null && morningDay.equals(todayArt);
    }

    /** Whether we already stored today's evening bulletin (after a successful Telegram send). */
    synchronized boolean hasEveningRecordedFor(LocalDate todayArt) {
        return eveningDay != null && eveningDay.equals(todayArt);
    }

    /** After morning send: same {@code updated} until next bulletin — use a slower poll interval. */
    synchronized boolean morningBulletinAlreadySentToday(LocalDate todayArt, String updated) {
        return morningDay != null
                && morningDay.equals(todayArt)
                && updated != null
                && updated.equals(morningUpdated);
    }

    /** After evening send: same {@code updated} until the next cycle — slower poll. */
    synchronized boolean eveningBulletinAlreadySentToday(LocalDate todayArt, String updated) {
        return eveningDay != null
                && eveningDay.equals(todayArt)
                && updated != null
                && updated.equals(eveningUpdated);
    }

    private void rewrite() throws IOException {
        StringBuilder sb = new StringBuilder();
        if (morningDay != null && morningUpdated != null) {
            sb.append("MORNING|").append(morningDay).append('|').append(morningUpdated).append('\n');
        }
        if (eveningDay != null && eveningUpdated != null) {
            sb.append("EVENING|").append(eveningDay).append('|').append(eveningUpdated).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }
}
