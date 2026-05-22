package ar.gob.smn.weather;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Spanish HTML and Telegram bodies for the daily 08:00 ART summary of the previous day's tiepre extrema (from
 * {@link TiepreExtremaHistoryLog}). Each line shows location and observation time so the recipient can see where and
 * when each daily extreme occurred.
 */
final class TiepreExtremaDailyMessages {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter SUBJECT_DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm z", ES_AR);
    private static final DateTimeFormatter HEADER_DAY = DateTimeFormatter.ofPattern("d MMM uuuu", ES_AR);

    private TiepreExtremaDailyMessages() {}

    static String subject(LocalDate dataDay) {
        return "Datos extremos diarios — " + dataDay.format(SUBJECT_DAY);
    }

    static String formatTelegramHtml(
            LocalDate dataDay,
            TiepreExtremaHistoryLog.DailyExtremes extremes,
            ZoneId zone,
            String reportHost) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>Datos extremos diarios</b>\n")
                .append(esc(dataDay.format(HEADER_DAY)))
                .append("\n");
        sb.append(esc(line("Máx", extremes.maxTemp(), zone, false))).append("\n");
        sb.append(esc(line("Mín", extremes.minTemp(), zone, false))).append("\n");
        sb.append(esc(windLine(extremes.maxWind(), zone)));
        String h = reportHost == null || reportHost.isBlank() ? "—" : reportHost;
        sb.append("\n\n(").append(esc(h)).append(")");
        return sb.toString();
    }

    static String formatEmailHtml(
            LocalDate dataDay,
            TiepreExtremaHistoryLog.DailyExtremes extremes,
            ZoneId zone,
            String reportHost) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Datos extremos diarios</h2>\n");
        sb.append("<p>")
                .append(esc(dataDay.format(HEADER_DAY)))
                .append(" — referencia horaria: ")
                .append(esc(zone.getId()))
                .append(".</p>\n");
        sb.append("<ul>\n");
        sb.append("<li><b>Temperatura máxima</b><br>").append(rowDetails(extremes.maxTemp(), zone, false)).append("</li>\n");
        sb.append("<li><b>Temperatura mínima</b><br>").append(rowDetails(extremes.minTemp(), zone, false)).append("</li>\n");
        sb.append("<li><b>Viento (máx. intensidad numérica)</b><br>")
                .append(rowDetails(extremes.maxWind(), zone, true))
                .append("</li>\n");
        sb.append("</ul>\n");
        String h = reportHost == null || reportHost.isBlank() ? "unknown" : reportHost;
        sb.append("<p style=\"color:#666;font-size:90%\">(").append(esc(h)).append(")</p>\n");
        return sb.toString();
    }

    private static String line(String label, TiepreExtremaHistoryLog.Record r, ZoneId zone, boolean wind) {
        if (r == null) {
            return label + " — sin datos";
        }
        String value =
                wind
                        ? String.format(Locale.ROOT, "%.1f km/h", r.value())
                        : String.format(Locale.ROOT, "%.1f °C", r.value());
        return label
                + " "
                + value
                + " — "
                + r.stationName()
                + " — "
                + r.observationArt().withZoneSameInstant(zone).format(WHEN);
    }

    private static String windLine(TiepreExtremaHistoryLog.Record r, ZoneId zone) {
        if (r == null) {
            return "Viento máx: sin magnitud numérica registrada el día";
        }
        String dir = r.windDirection() == null || r.windDirection().isBlank() ? "" : r.windDirection() + " ";
        return "Viento máx "
                + dir
                + String.format(Locale.ROOT, "%.1f km/h", r.value())
                + " — "
                + r.stationName()
                + " — "
                + r.observationArt().withZoneSameInstant(zone).format(WHEN);
    }

    private static String rowDetails(TiepreExtremaHistoryLog.Record r, ZoneId zone, boolean wind) {
        if (r == null) {
            return esc("(sin datos en el historial del día)");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Lugar: ").append(esc(r.stationName())).append("<br>");
        sb.append("Hora observación: ")
                .append(esc(r.observationArt().withZoneSameInstant(zone).format(WHEN)))
                .append("<br>");
        if (wind) {
            String dir = r.windDirection() == null || r.windDirection().isBlank() ? "—" : r.windDirection();
            sb.append("Dirección: ").append(esc(dir)).append("<br>");
            sb.append("Velocidad: ")
                    .append(esc(String.format(Locale.ROOT, "%.1f km/h", r.value())))
                    .append("<br>");
        } else {
            sb.append("Temperatura: ")
                    .append(esc(String.format(Locale.ROOT, "%.1f °C", r.value())))
                    .append("<br>");
        }
        return sb.toString();
    }

    /** Plain-text version of {@link #formatTelegramHtml} for the email {@code multipart/alternative} alt body. */
    static String telegramAsPlain(String telegramHtml) {
        if (telegramHtml == null || telegramHtml.isEmpty()) {
            return "";
        }
        return telegramHtml
                .replace("<b>", "")
                .replace("</b>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
