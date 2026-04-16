package ar.gob.smn.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;

/** Renders SMN {@code /v1/forecast/location/…} JSON as plain text for email/Telegram. */
final class ForecastFormatter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DAYS = 5;

    private static final String[] PERIOD_KEYS =
            {"early_morning", "morning", "afternoon", "night"};
    private static final String[] PERIOD_LABELS =
            {"Madrugada", "Mañana", "Tarde", "Noche"};

    private ForecastFormatter() {}

    /** SMN bulletin timestamp for deduplication (may be empty if missing). */
    static String parseUpdated(String jsonBody) throws IOException {
        JsonNode root = JSON.readTree(jsonBody);
        return root.path("updated").asText("").trim();
    }

    static String format(String jsonBody) throws IOException {
        JsonNode root = JSON.readTree(jsonBody);
        String updated = root.path("updated").asText("").trim();
        StringBuilder sb = new StringBuilder();
        sb.append("--- SMN forecast (same location) ---\n");
        if (!updated.isEmpty()) {
            sb.append("Forecast data updated: ").append(updated).append("\n");
        }
        JsonNode days = root.path("forecast");
        if (!days.isArray() || days.isEmpty()) {
            sb.append("(No forecast days in response.)\n");
            return sb.toString();
        }
        int n = Math.min(days.size(), MAX_DAYS);
        for (int i = 0; i < n; i++) {
            appendDay(sb, days.get(i));
        }
        if (days.size() > MAX_DAYS) {
            sb.append("… (")
                    .append(days.size() - MAX_DAYS)
                    .append(" more day(s) on ws1.smn.gob.ar)\n");
        }
        return sb.toString();
    }

    /**
     * Same layout as {@link #format(String)} but a single calendar day (e.g. observation date in ART). If SMN has no
     * matching row, uses the first day in the array.
     */
    static String formatForCalendarDay(String jsonBody, LocalDate dayArt) throws IOException {
        JsonNode root = JSON.readTree(jsonBody);
        String updated = root.path("updated").asText("").trim();
        StringBuilder sb = new StringBuilder();
        sb.append("--- SMN forecast (same location) ---\n");
        if (!updated.isEmpty()) {
            sb.append("Forecast data updated: ").append(updated).append("\n");
        }
        JsonNode days = root.path("forecast");
        if (!days.isArray() || days.isEmpty()) {
            sb.append("(No forecast days in response.)\n");
            return sb.toString();
        }
        JsonNode day = findForecastDayForDate(days, dayArt);
        if (day == null) {
            sb.append("(No forecast days in response.)\n");
            return sb.toString();
        }
        appendDay(sb, day);
        sb.append("\n");
        return sb.toString();
    }

    private static JsonNode findForecastDayForDate(JsonNode days, LocalDate target) {
        for (int i = 0; i < days.size(); i++) {
            JsonNode day = days.get(i);
            if (target.equals(parseForecastLocalDate(day.path("date").asText("")))) {
                return day;
            }
        }
        return days.get(0);
    }

    private static LocalDate parseForecastLocalDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return LocalDate.MIN;
        }
        String core = dateStr.length() >= 10 ? dateStr.substring(0, 10) : dateStr;
        return LocalDate.parse(core);
    }

    private static void appendDay(StringBuilder sb, JsonNode day) {
        String date = day.path("date").asText("?");
        Double tmin = num(day, "temp_min");
        Double tmax = num(day, "temp_max");
        Double hmin = num(day, "humidity_min");
        Double hmax = num(day, "humidity_max");
        sb.append("\n").append(date);
        if (tmin != null || tmax != null) {
            sb.append("  ");
            if (tmin != null) {
                sb.append("min ").append(fmt(tmin)).append(" °C");
            }
            if (tmin != null && tmax != null) {
                sb.append(" / ");
            }
            if (tmax != null) {
                sb.append("max ").append(fmt(tmax)).append(" °C");
            }
        }
        if (hmin != null || hmax != null) {
            sb.append("  hum. ");
            if (hmin != null && hmax != null) {
                sb.append(fmt(hmin)).append("–").append(fmt(hmax)).append(" %");
            } else if (hmin != null) {
                sb.append(fmt(hmin)).append(" %");
            } else {
                sb.append(fmt(hmax)).append(" %");
            }
        }
        sb.append("\n");
        for (int p = 0; p < PERIOD_KEYS.length; p++) {
            JsonNode block = day.path(PERIOD_KEYS[p]);
            if (block.isMissingNode() || block.isNull()) {
                continue;
            }
            appendPeriod(sb, PERIOD_LABELS[p], block);
        }
    }

    private static void appendPeriod(StringBuilder sb, String label, JsonNode p) {
        String wx = p.path("weather").path("description").asText("—");
        Double temp = num(p, "temperature");
        String vis = p.path("visibility").asText(null);
        String windDir = p.path("wind").path("direction").asText(null);
        String windSpd = formatSpeedRange(p.path("wind").path("speed_range"));
        JsonNode rainArr = p.path("rain_prob_range");
        String rainP = formatIntRange(rainArr);
        sb.append("  ")
                .append(label)
                .append(": ")
                .append(wx);
        if (temp != null) {
            sb.append(", ").append(fmt(temp)).append(" °C");
        }
        if (vis != null && !vis.isBlank()) {
            sb.append(", vis. ").append(vis);
        }
        if (windDir != null || windSpd != null) {
            sb.append(", wind");
            if (windDir != null) {
                sb.append(" ").append(windDir);
            }
            if (windSpd != null) {
                sb.append(" ").append(windSpd);
            }
        }
        if (rainP != null && !isZeroOnlyRainRange(rainArr)) {
            sb.append(", rain prob. ").append(rainP);
        }
        sb.append("\n");
    }

    private static String formatIntRange(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.size() < 2) {
            return null;
        }
        int a = arr.get(0).asInt();
        int b = arr.get(1).asInt();
        return a + "–" + b + " %";
    }

    /** Omit rain line when SMN reports {@code [0,0]} (no chance). */
    private static boolean isZeroOnlyRainRange(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.size() < 2) {
            return false;
        }
        return arr.get(0).asInt() == 0 && arr.get(1).asInt() == 0;
    }

    private static String formatSpeedRange(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.size() < 2) {
            return null;
        }
        int a = arr.get(0).asInt();
        int b = arr.get(1).asInt();
        return a + "–" + b + " km/h";
    }

    private static Double num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        return v.asDouble();
    }

    private static String fmt(double d) {
        if (Double.isNaN(d)) {
            return "?";
        }
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
