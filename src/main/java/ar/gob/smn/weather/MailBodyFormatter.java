package ar.gob.smn.weather;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MailBodyFormatter {

    private static final ZoneId ART = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    /** Matches reference: {@code 04.00 hs.} */
    private static final DateTimeFormatter ACTUALIZADO_HOUR =
            DateTimeFormatter.ofPattern("HH.mm", Locale.ROOT);

    /** Plain text (legacy-style) vs HTML for email vs HTML for Telegram ({@code parse_mode=HTML}). */
    enum BodyTarget {
        PLAIN_TEXT,
        HTML_EMAIL,
        HTML_TELEGRAM
    }

    private MailBodyFormatter() {}

    /** After {@code Pronóstico}: SMN bulletin {@code updated} phrase (plain), or empty. */
    private static String pronosticoActualizacionSuffixPlain(String forecastUpdatedRaw) {
        String line = TelegramForecastFormatter.smnActualizacionSpanishPlain(forecastUpdatedRaw);
        return line.isEmpty() ? "" : " — " + line;
    }

    /** After {@code Pronóstico</b>}: same phrase escaped for HTML, or empty. */
    private static String pronosticoActualizacionSuffixHtml(String forecastUpdatedRaw) {
        String line = TelegramForecastFormatter.smnActualizacionSpanishPlain(forecastUpdatedRaw);
        return line.isEmpty() ? "" : " — " + escHtml(line);
    }

    /**
     * Current-conditions text (Spanish), optional CABA forecast block, and host footer {@code (name)}.
     *
     * @param forecastBlockPlain plain forecast (email HTML and fallback).
     * @param forecastTelegramDaysHtml trusted HTML fragment for Telegram combined message; null to use plain in
     *     {@code pre}.
     * @param forecastUpdatedRaw SMN JSON {@code updated} for the forecast (ISO datetime); when non-blank, appended
     *     after {@code Pronóstico} as {@code Actualización de las …} in Buenos Aires time, same wording as the
     *     forecast bulletin headline.
     * @param locationId SMN location id (used for {@code @CABA} / {@code @AEP} in email; {@code /CABA} on Telegram
     *     HTML).
     */
    static String formatSingle(
            SmnClient.Observation o,
            String forecastBlockPlain,
            String forecastTelegramDaysHtml,
            boolean includeForecast,
            int locationId,
            String hostFooter,
            String forecastUpdatedRaw,
            BodyTarget target) {
        if (target == BodyTarget.PLAIN_TEXT) {
            return formatPlain(o, forecastBlockPlain, includeForecast, locationId, hostFooter, forecastUpdatedRaw);
        }
        if (target == BodyTarget.HTML_EMAIL) {
            return formatHtmlEmail(
                    o, forecastBlockPlain, includeForecast, locationId, hostFooter, forecastUpdatedRaw);
        }
        return formatHtmlTelegram(
                o,
                forecastBlockPlain,
                forecastTelegramDaysHtml,
                includeForecast,
                locationId,
                hostFooter,
                forecastUpdatedRaw);
    }

    private static String formatPlain(
            SmnClient.Observation o,
            String forecastBlock,
            boolean includeForecast,
            int locationId,
            String hostFooter,
            String forecastUpdatedRaw) {
        StringBuilder sb = new StringBuilder();
        appendConditionsBlockPlain(sb, o, locationId);
        if (includeForecast && forecastBlock != null && !forecastBlock.isBlank()) {
            sb.append("\n\n\n");
            sb.append("Pronóstico");
            sb.append(pronosticoActualizacionSuffixPlain(forecastUpdatedRaw));
            sb.append("\n\n");
            sb.append(forecastBlock.trim());
            sb.append("\n");
        }
        appendHostLine(sb, hostFooter);
        return sb.toString();
    }

    private static String formatHtmlEmail(
            SmnClient.Observation o,
            String forecastBlock,
            boolean includeForecast,
            int locationId,
            String hostFooter,
            String forecastUpdatedRaw) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><body style=\"font-family:sans-serif;\">");
        appendConditionsBlockHtml(sb, o, locationId, "<br>", '@');
        if (includeForecast && forecastBlock != null && !forecastBlock.isBlank()) {
            sb.append("<br><br><br>");
            sb.append("<p><b>Pronóstico</b>")
                    .append(pronosticoActualizacionSuffixHtml(forecastUpdatedRaw))
                    .append("</p>");
            sb.append("<p style=\"margin:0;\">")
                    .append(forecastPlainToHtmlWithBoldPeriods(forecastBlock.trim()))
                    .append("</p>");
        }
        appendHostLineHtml(sb, hostFooter, "<br>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String formatHtmlTelegram(
            SmnClient.Observation o,
            String forecastBlockPlain,
            String forecastTelegramDaysHtml,
            boolean includeForecast,
            int locationId,
            String hostFooter,
            String forecastUpdatedRaw) {
        StringBuilder sb = new StringBuilder();
        // Telegram HTML: no @ before tag (mention parsing); use "/CABA". Bold + newlines, no br tag.
        appendConditionsBlockHtml(sb, o, locationId, "\n", '/');
        if (includeForecast) {
            boolean haveDaysHtml =
                    forecastTelegramDaysHtml != null && !forecastTelegramDaysHtml.isBlank();
            boolean havePlain = forecastBlockPlain != null && !forecastBlockPlain.isBlank();
            if (haveDaysHtml) {
                sb.append("\n\n\n");
                sb.append("<b>Pronóstico</b>")
                        .append(pronosticoActualizacionSuffixHtml(forecastUpdatedRaw))
                        .append("\n");
                sb.append(forecastTelegramDaysHtml);
            } else if (havePlain) {
                sb.append("\n\n\n");
                sb.append("<b>Pronóstico</b>")
                        .append(pronosticoActualizacionSuffixHtml(forecastUpdatedRaw))
                        .append("\n");
                sb.append("<pre>").append(escHtml(forecastBlockPlain.trim())).append("</pre>");
            }
        }
        appendHostLineHtml(sb, hostFooter, "\n");
        return sb.toString();
    }

    /** Plain / email: {@code STATION@TAG}; Telegram uses {@link #conditionsStyleHeaderLine(SmnClient.Observation, int, char)} with {@code '/'}. */
    static String conditionsStyleHeaderLine(SmnClient.Observation o, int locationId) {
        return conditionsStyleHeaderLine(o, locationId, '@');
    }

    static String conditionsStyleHeaderLine(SmnClient.Observation o, int locationId, char tagSeparator) {
        String title = o.stationName().toUpperCase(ES_AR);
        return title + tagSeparator + locationTag(tagLocationId(o, locationId));
    }

    /**
     * Plain current-conditions block only (same wording as email/Telegram; no host line, no forecast).
     */
    static String formatCurrentConditionsPlain(SmnClient.Observation o, int locationId) {
        StringBuilder sb = new StringBuilder();
        appendConditionsBlockPlain(sb, o, locationId);
        return sb.toString();
    }

    private static void appendConditionsBlockPlain(StringBuilder sb, SmnClient.Observation o, int locationId) {
        ZonedDateTime local = o.observationTime().withZoneSameInstant(ART);
        String title = o.stationName().toUpperCase(ES_AR);
        String tag = locationTag(tagLocationId(o, locationId));
        sb.append(title)
                .append('@')
                .append(tag)
                .append(" - Actualizado: ")
                .append(local.format(ACTUALIZADO_HOUR))
                .append(" hs.\n\n");

        String cond = o.description() != null ? o.description() : "—";
        sb.append("Condición actual: ").append(cond).append(".\n");

        if (o.temperature() != null) {
            sb.append("Temperatura: ").append(fmt1(o.temperature())).append("°C\n");
        } else {
            sb.append("Temperatura: —\n");
        }
        if (o.feelsLike() != null) {
            sb.append("Sensación térmica: ").append(fmt1(o.feelsLike())).append("°C\n");
        }
        if (o.humidity() != null) {
            sb.append("Humedad: ").append(fmt1(o.humidity())).append("%\n");
        } else {
            sb.append("Humedad: —\n");
        }
        if (o.pressure() != null) {
            sb.append("Presión: ").append(fmt1(o.pressure())).append(" HPa\n");
        } else {
            sb.append("Presión: —\n");
        }
        sb.append("Viento de dirección: ").append(windLine(o)).append("\n");
        if (o.visibilityKm() != null) {
            sb.append("Visibilidad: ").append(fmt1(o.visibilityKm())).append(" km.\n");
        } else {
            sb.append("Visibilidad: —\n");
        }
    }

    /**
     * @param eol line break: {@code "<br>"} for email HTML; {@code "\n"} for Telegram {@code parse_mode=HTML} (no
     *     {@code br} tag).
     */
    private static void appendConditionsBlockHtml(
            StringBuilder sb, SmnClient.Observation o, int locationId, String eol, char stationTagSeparator) {
        ZonedDateTime local = o.observationTime().withZoneSameInstant(ART);
        String title = o.stationName().toUpperCase(ES_AR);
        String tag = locationTag(tagLocationId(o, locationId));
        sb.append("<b>")
                .append(escHtml(title))
                .append(stationTagSeparator == '/' ? "/" : "@")
                .append(escHtml(tag))
                .append("</b>")
                .append(" - Actualizado: ")
                .append(escHtml(local.format(ACTUALIZADO_HOUR)))
                .append(" hs.")
                .append(eol)
                .append(eol);

        String cond = o.description() != null ? o.description() : "—";
        sb.append("Condición actual: <b>")
                .append(escHtml(cond))
                .append("</b>.")
                .append(eol);

        if (o.temperature() != null) {
            sb.append("Temperatura: <b>")
                    .append(escHtml(fmt1(o.temperature())))
                    .append("</b>°C")
                    .append(eol);
        } else {
            sb.append("Temperatura: —").append(eol);
        }
        if (o.feelsLike() != null) {
            sb.append("Sensación térmica: <b>")
                    .append(escHtml(fmt1(o.feelsLike())))
                    .append("</b>°C")
                    .append(eol);
        }
        if (o.humidity() != null) {
            sb.append("Humedad: <b>").append(escHtml(fmt1(o.humidity()))).append("</b>%").append(eol);
        } else {
            sb.append("Humedad: —").append(eol);
        }
        if (o.pressure() != null) {
            sb.append("Presión: <b>").append(escHtml(fmt1(o.pressure()))).append("</b> HPa").append(eol);
        } else {
            sb.append("Presión: —").append(eol);
        }
        sb.append("Viento de dirección: <b>")
                .append(escHtml(windLine(o)))
                .append("</b>")
                .append(eol);
        if (o.visibilityKm() != null) {
            sb.append("Visibilidad: <b>")
                    .append(escHtml(fmt1(o.visibilityKm())))
                    .append("</b> km.")
                    .append(eol);
        } else {
            sb.append("Visibilidad: —").append(eol);
        }
    }

    private static final Pattern FORECAST_PERIOD_LINE =
            Pattern.compile("^(\\s*)(Madrugada|Mañana|Tarde|Noche)(:)(.*)$");

    /**
     * Plain SMN day forecast (from {@link ForecastFormatter}) → HTML with {@code <br>} and bold period labels only.
     */
    private static String forecastPlainToHtmlWithBoldPeriods(String plain) {
        String[] lines = plain.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append("<br>");
            }
            String line = lines[i];
            Matcher m = FORECAST_PERIOD_LINE.matcher(line);
            if (m.matches()) {
                out.append(escHtml(m.group(1)))
                        .append("<b>")
                        .append(escHtml(m.group(2)))
                        .append("</b>")
                        .append(escHtml(m.group(3)))
                        .append(escHtml(m.group(4)));
            } else {
                out.append(escHtml(line));
            }
        }
        return out.toString();
    }

    private static String escHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void appendHostLineHtml(StringBuilder sb, String hostFooter, String eol) {
        String h = hostFooter != null ? hostFooter.trim() : "";
        if (h.isEmpty()) {
            h = "unknown";
        }
        sb.append(eol).append(eol).append("(").append(escHtml(h)).append(")");
    }

    private static String windLine(SmnClient.Observation o) {
        String dir = o.windDirection();
        Double spd = o.windSpeed();
        if (dir != null && spd != null) {
            return dir + " a " + fmt1(spd) + " km/h.";
        }
        if (dir != null) {
            return dir + ".";
        }
        if (spd != null) {
            return fmt1(spd) + " km/h.";
        }
        return "—";
    }

    private static String fmt1(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    /** Prefer SMN JSON {@code location.id} when present (e.g. alternate id used for fetch). */
    private static int tagLocationId(SmnClient.Observation o, int fallback) {
        Integer x = o.smnLocationId();
        return x != null ? x : fallback;
    }

    /** Short tag after @ in the header line (screenshot: CABA). */
    private static String locationTag(int locationId) {
        if (locationId < 0) {
            return "TXT";
        }
        if (locationId == 4864) {
            return "CABA";
        }
        if (locationId == 10821) {
            return "AEP";
        }
        return "SMN" + locationId;
    }

    private static void appendHostLine(StringBuilder sb, String hostFooter) {
        String h = hostFooter != null ? hostFooter.trim() : "";
        if (h.isEmpty()) {
            h = "unknown";
        }
        sb.append("\n(").append(h).append(")\n");
    }
}
