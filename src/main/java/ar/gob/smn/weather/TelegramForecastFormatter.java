package ar.gob.smn.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Compact Spanish layout for CABA forecast Telegram posts (SMN-style: día en mayúsculas, Mañana/Tarde/Noche,
 * probabilidad de lluvia).
 */
final class TelegramForecastFormatter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Match reference: several days visible in one message. */
    private static final int MAX_DAYS = 8;

    private static final String[] TELEGRAM_PERIOD_KEYS =
            {"early_morning", "morning", "afternoon", "night"};
    private static final String[] TELEGRAM_PERIOD_LABELS =
            {"Madrugada", "Mañana", "Tarde", "Noche"};

    private TelegramForecastFormatter() {}

    /**
     * Telegram reference style {@code CIUDAD…/CABA} — use slash, not {@code @}: in {@code parse_mode=HTML},
     * {@code @} triggers mention parsing and corrupts the line.
     */
    static String smnLocationHeadline(SmnClient.Station station) {
        String tag =
                station.locationId() == 4864
                        ? "CABA"
                        : (station.locationId() == 10821 ? "AEP" : "SMN" + station.locationId());
        return station.label().toUpperCase(ES_AR) + "/" + tag;
    }

    /**
     * Full bulletin as Telegram {@code parse_mode=HTML}: bold title line and bold day headers, body lines plain
     * (no {@code pre} — proportional text like SMN reference).
     */
    static String formatTelegramHtml(String jsonBody, String headerSource) throws IOException {
        JsonNode root = JSON.readTree(jsonBody);
        String updatedRaw = root.path("updated").asText("").trim();
        StringBuilder sb = new StringBuilder();
        if (!updatedRaw.isEmpty()) {
            sb.append("<b>").append(escTg(formatHeader(headerSource, updatedRaw))).append("</b>");
        } else {
            sb.append("<b>").append(escTg(headerSource)).append(".</b>");
        }
        sb.append("\n\n");
        appendForecastDaysHtml(sb, root);
        return sb.toString();
    }

    /**
     * Single calendar day for combined conditions + forecast Telegram (observation’s date in ART). Falls back to the
     * first SMN day if no exact date match.
     */
    static String formatTelegramHtmlDaySlice(String jsonBody, LocalDate dayArt) throws IOException {
        return formatTelegramHtmlDaySlices(jsonBody, dayArt, null);
    }

    /**
     * One or two day blocks for combined conditions + forecast: primary day and optional second (e.g. day after
     * observation) when SMN exposes it.
     */
    static String formatTelegramHtmlDaySlices(String jsonBody, LocalDate dayArt, LocalDate secondDayOrNull)
            throws IOException {
        JsonNode root = JSON.readTree(jsonBody);
        JsonNode days = root.path("forecast");
        if (!days.isArray() || days.isEmpty()) {
            return escTg("(Sin días de pronóstico en la respuesta.)");
        }
        JsonNode first = findForecastDayForDate(days, dayArt);
        LocalDate todayArt = LocalDate.now(BUENOS_AIRES);
        StringBuilder sb = new StringBuilder();
        appendDayBlockHtml(sb, first, todayArt);
        if (secondDayOrNull != null) {
            LocalDate firstD = parseForecastDay(first.path("date").asText(""));
            if (!secondDayOrNull.equals(firstD)) {
                JsonNode second = findForecastDayStrict(days, secondDayOrNull);
                if (second != null) {
                    sb.append('\n');
                    appendDayBlockHtml(sb, second, todayArt);
                }
            }
        }
        return sb.toString();
    }

    private static JsonNode findForecastDayStrict(JsonNode days, LocalDate target) {
        if (!days.isArray()) {
            return null;
        }
        for (int i = 0; i < days.size(); i++) {
            JsonNode d = days.get(i);
            if (target.equals(parseForecastDay(d.path("date").asText("")))) {
                return d;
            }
        }
        return null;
    }

    private static JsonNode findForecastDayForDate(JsonNode days, LocalDate target) {
        JsonNode strict = findForecastDayStrict(days, target);
        if (strict != null) {
            return strict;
        }
        return !days.isEmpty() ? days.get(0) : null;
    }

    private static void appendForecastDaysHtml(StringBuilder sb, JsonNode root) {
        JsonNode days = root.path("forecast");
        if (!days.isArray() || days.isEmpty()) {
            sb.append(escTg("(Sin días de pronóstico en la respuesta.)"));
            return;
        }
        LocalDate todayArt = LocalDate.now(BUENOS_AIRES);
        int n = Math.min(days.size(), MAX_DAYS);
        for (int i = 0; i < n; i++) {
            appendDayBlockHtml(sb, days.get(i), todayArt);
            if (i < n - 1) {
                sb.append('\n');
            }
        }
        if (days.size() > MAX_DAYS) {
            sb.append("\n… (")
                    .append(escTg(String.valueOf(days.size() - MAX_DAYS)))
                    .append(" día(s) más en smn.gob.ar)");
        }
    }

    private static void appendDayBlockHtml(StringBuilder sb, JsonNode day, LocalDate todayArt) {
        LocalDate d = parseForecastDay(day.path("date").asText(""));
        boolean hoy = d.equals(todayArt);
        sb.append("<b>").append(escTg(dayTitleLine(d, hoy) + ".")).append("</b>\n");
        for (int p = 0; p < TELEGRAM_PERIOD_KEYS.length; p++) {
            JsonNode block = day.path(TELEGRAM_PERIOD_KEYS[p]);
            if (block.isMissingNode() || block.isNull()) {
                continue;
            }
            appendPeriodLineHtml(sb, TELEGRAM_PERIOD_LABELS[p], block);
        }
    }

    private static void appendPeriodLineHtml(StringBuilder sb, String label, JsonNode p) {
        String wx = p.path("weather").path("description").asText("—");
        Double temp = num(p, "temperature");
        sb.append("<b>").append(escTg(label)).append("</b>: ").append(escTg(wx));
        if (temp != null && !Double.isNaN(temp)) {
            sb.append(", ").append(String.format(Locale.ROOT, "%.1f", temp)).append("°C");
        }
        String rain = formatRainSpanish(p.path("rain_prob_range"));
        if (rain != null) {
            sb.append(", con una probabilidad de lluvia de ").append(escTg(rain)).append('.');
        } else {
            sb.append('.');
        }
        sb.append('\n');
    }

    private static String escTg(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Plain-text bulletin (e.g. for history files). Headline uses {@code /} before the tag.
     *
     * @param headerSource e.g. {@code CIUDAD AUTÓNOMA DE BUENOS AIRES/CABA}.
     */
    static String format(String jsonBody, String headerSource) throws IOException {
        JsonNode root = JSON.readTree(jsonBody);
        String updatedRaw = root.path("updated").asText("").trim();
        StringBuilder sb = new StringBuilder();
        if (!updatedRaw.isEmpty()) {
            sb.append(formatHeader(headerSource, updatedRaw));
        } else {
            sb.append(headerSource).append('.');
        }
        sb.append("\n\n");

        JsonNode days = root.path("forecast");
        if (!days.isArray() || days.isEmpty()) {
            sb.append("(Sin días de pronóstico en la respuesta.)");
            return sb.toString();
        }

        LocalDate todayArt = LocalDate.now(BUENOS_AIRES);
        int n = Math.min(days.size(), MAX_DAYS);
        for (int i = 0; i < n; i++) {
            appendDayBlock(sb, days.get(i), todayArt);
            if (i < n - 1) {
                sb.append("\n");
            }
        }
        if (days.size() > MAX_DAYS) {
            sb.append("\n… (")
                    .append(days.size() - MAX_DAYS)
                    .append(" día(s) más en smn.gob.ar)");
        }
        return sb.toString();
    }

    /**
     * SMN {@code updated} as in the bulletin headline, without station name or parentheses, e.g.
     * {@code Actualización de las 12:14 del viernes 17 de abril}. Empty if {@code updatedRaw} is blank; on parse
     * failure, {@code Actualización: } plus the trimmed raw value.
     */
    static String smnActualizacionSpanishPlain(String updatedRaw) {
        if (updatedRaw == null || updatedRaw.isBlank()) {
            return "";
        }
        String raw = updatedRaw.trim();
        try {
            ZonedDateTime z = parseUpdated(raw);
            String hhmm = z.format(DateTimeFormatter.ofPattern("HH:mm", ES_AR));
            String weekday = z.getDayOfWeek().getDisplayName(TextStyle.FULL, ES_AR);
            String dd = String.format(Locale.ROOT, "%02d", z.getDayOfMonth());
            String month = z.getMonth().getDisplayName(TextStyle.FULL, ES_AR);
            return "Actualización de las "
                    + hhmm
                    + " del "
                    + weekday
                    + " "
                    + dd
                    + " de "
                    + month;
        } catch (DateTimeException e) {
            return "Actualización: " + raw;
        }
    }

    private static String formatHeader(String headerSource, String updatedRaw) {
        String line = smnActualizacionSpanishPlain(updatedRaw);
        if (line.isEmpty()) {
            return headerSource + ".";
        }
        return headerSource + " (" + line + ").";
    }

    private static ZonedDateTime parseUpdated(String updatedRaw) {
        try {
            return ZonedDateTime.parse(updatedRaw, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .withZoneSameInstant(BUENOS_AIRES);
        } catch (DateTimeParseException e) {
            return ZonedDateTime.parse(updatedRaw, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                    .withZoneSameInstant(BUENOS_AIRES);
        }
    }

    private static void appendDayBlock(StringBuilder sb, JsonNode day, LocalDate todayArt) {
        LocalDate d = parseForecastDay(day.path("date").asText(""));
        boolean hoy = d.equals(todayArt);
        sb.append(dayTitleLine(d, hoy)).append(".\n");
        for (int p = 0; p < TELEGRAM_PERIOD_KEYS.length; p++) {
            JsonNode block = day.path(TELEGRAM_PERIOD_KEYS[p]);
            if (block.isMissingNode() || block.isNull()) {
                continue;
            }
            appendPeriodLine(sb, TELEGRAM_PERIOD_LABELS[p], block);
        }
    }

    private static LocalDate parseForecastDay(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return LocalDate.MIN;
        }
        String core = dateStr.length() >= 10 ? dateStr.substring(0, 10) : dateStr;
        return LocalDate.parse(core);
    }

    /** e.g. {@code HOY LUNES 08 DE ENERO} or {@code MARTES 09 DE ENERO}. */
    private static String dayTitleLine(LocalDate d, boolean useHoy) {
        String dow = d.getDayOfWeek().getDisplayName(TextStyle.FULL, ES_AR).toUpperCase(ES_AR);
        String dd = String.format(Locale.ROOT, "%02d", d.getDayOfMonth());
        String month = d.getMonth().getDisplayName(TextStyle.FULL, ES_AR).toUpperCase(ES_AR);
        String rest = dow + " " + dd + " DE " + month;
        return useHoy ? "HOY " + rest : rest;
    }

    /** {@code Mañana: Parcialmente nublado, 20.0°C, con una probabilidad de lluvia de 10%.} */
    private static void appendPeriodLine(StringBuilder sb, String label, JsonNode p) {
        String wx = p.path("weather").path("description").asText("—");
        Double temp = num(p, "temperature");
        sb.append(label).append(": ").append(wx);
        if (temp != null && !Double.isNaN(temp)) {
            sb.append(", ").append(String.format(Locale.ROOT, "%.1f", temp)).append("°C");
        }
        String rain = formatRainSpanish(p.path("rain_prob_range"));
        if (rain != null) {
            sb.append(", con una probabilidad de lluvia de ").append(rain).append('.');
        } else {
            sb.append('.');
        }
        sb.append('\n');
    }

    private static String formatRainSpanish(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.size() < 2) {
            return null;
        }
        int a = arr.get(0).asInt();
        int b = arr.get(1).asInt();
        if (a == 0 && b == 0) {
            return null;
        }
        if (a == b) {
            return a + "%";
        }
        return "entre " + a + "% y " + b + "%";
    }

    private static Double num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        return v.asDouble();
    }
}
