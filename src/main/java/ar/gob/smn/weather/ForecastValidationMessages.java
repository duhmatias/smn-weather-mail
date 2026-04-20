package ar.gob.smn.weather;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Spanish HTML / Telegram for daily forecast-vs-observed validation (CABA). */
final class ForecastValidationMessages {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMM uuuu", ES_AR);
    /** Leave margin under Telegram’s 4096 limit for {@code sendHtml}. */
    private static final int TELEGRAM_BODY_BUDGET = 3800;

    private ForecastValidationMessages() {}

    static String subject(LocalDate dataDay) {
        return "Validación pronóstico vs observado — " + dataDay;
    }

    static String buildEmailHtml(
            String stationLabel,
            LocalDate dataDay,
            Optional<MeasuresSummaryMessages.TempPeriod> observed,
            boolean observedRain,
            List<ForecastDaySnapshotLog.SnapshotRow> forecastSnapshots,
            String reportHost) {
        String h = esc(reportHost);
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>").append(esc(subject(dataDay))).append("</h2>\n");
        sb.append("<p><b>").append(esc(stationLabel)).append("</b> — día ").append(esc(dataDay.format(DAY))).append("</p>\n");

        sb.append("<h3>Observado (registro horario SMN)</h3>\n");
        if (observed.isPresent()) {
            MeasuresSummaryMessages.TempPeriod p = observed.get();
            sb.append("<p>Mín / máx temperatura: ")
                    .append(fmt(p.minTemp()))
                    .append(" / ")
                    .append(fmt(p.maxTemp()))
                    .append("</p>\n");
        } else {
            sb.append("<p>(Sin mediciones en el archivo local para ese día.)</p>\n");
        }
        sb.append("<p>Lluvia (condiciones reportadas): ").append(observedRain ? "sí" : "no").append("</p>\n");

        sb.append("<h3>Pronóstico (todos los boletines SMN guardados que incluían ese día)</h3>\n");
        if (forecastSnapshots.isEmpty()) {
            sb.append("<p><i>No hay en el historial local ningún JSON SMN que incluya ese día. ")
                    .append("Hace falta haber corrido este servicio cuando SMN ya exponía ese día en el pronóstico.</i></p>\n");
        } else {
            sb.append("<p>")
                    .append(forecastSnapshots.size())
                    .append(" registro(s), en orden cronológico (cuando se guardó cada JSON).</p>\n");
            sb.append("<ol>\n");
            for (ForecastDaySnapshotLog.SnapshotRow f : forecastSnapshots) {
                sb.append("<li><p>Registrado (ART): ").append(esc(f.writtenArt().toString())).append("</p>\n");
                if (!f.smnUpdated().isBlank()) {
                    sb.append("<p>SMN <code>updated</code>: ").append(esc(f.smnUpdated())).append("</p>\n");
                }
                sb.append("<p><code>temp_min</code> / <code>temp_max</code>: ")
                        .append(ForecastDaySnapshotLog.fmtTemp(f.tempMin()))
                        .append(" / ")
                        .append(ForecastDaySnapshotLog.fmtTemp(f.tempMax()))
                        .append("</p>\n");
                sb.append("<p>Máx. probabilidad de lluvia (tope de rangos por período): ")
                        .append(f.maxRainUpper())
                        .append(" %</p></li>\n");
            }
            sb.append("</ol>\n");
        }

        sb.append("<p style=\"color:#555;font-size:90%\">")
                .append("Comparación orientativa; se listan todos los pronósticos almacenados por esta app ")
                .append("en los que SMN incluía ese día en el array <code>forecast</code>.")
                .append("</p>\n");
        sb.append("<p>(").append(h).append(")</p>\n");
        return sb.toString();
    }

    static String buildTelegramHtml(
            String stationLabel,
            LocalDate dataDay,
            Optional<MeasuresSummaryMessages.TempPeriod> observed,
            boolean observedRain,
            List<ForecastDaySnapshotLog.SnapshotRow> forecastSnapshots,
            String reportHost) {
        String h = escTg(reportHost);
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(escTg(subject(dataDay))).append("</b>\n\n");
        sb.append(escTg(stationLabel)).append(" — ").append(escTg(dataDay.format(DAY))).append("\n\n");

        sb.append("<b>Observado</b>\n");
        if (observed.isPresent()) {
            MeasuresSummaryMessages.TempPeriod p = observed.get();
            sb.append("Mín / máx: ")
                    .append(fmt(p.minTemp()))
                    .append(" / ")
                    .append(fmt(p.maxTemp()))
                    .append("\n");
        } else {
            sb.append("(Sin mediciones locales.)\n");
        }
        sb.append("Lluvia: ").append(observedRain ? "sí" : "no").append("\n\n");

        sb.append("<b>Pronóstico (todos los que incluían ese día)</b>\n");
        if (forecastSnapshots.isEmpty()) {
            sb.append("(Sin datos en historial local.)\n");
        } else {
            int shown = appendTelegramSnapshots(sb, forecastSnapshots);
            if (shown == 0) {
                sb.append("(Lista larga — ver correo para todos los ")
                        .append(forecastSnapshots.size())
                        .append(" boletines.)\n");
            } else if (shown < forecastSnapshots.size()) {
                sb.append("\n… (+")
                        .append(forecastSnapshots.size() - shown)
                        .append(" más en el correo)\n");
            }
        }

        sb.append("\n(").append(h).append(")");
        String out = sb.toString();
        if (out.length() > TELEGRAM_BODY_BUDGET) {
            return out.substring(0, TELEGRAM_BODY_BUDGET - 20) + "\n…(truncado)";
        }
        return out;
    }

    /**
     * Appends numbered snapshot blocks until adding another would exceed budget. Returns count appended.
     */
    private static int appendTelegramSnapshots(StringBuilder sb, List<ForecastDaySnapshotLog.SnapshotRow> forecasts) {
        int shown = 0;
        for (int i = 0; i < forecasts.size(); i++) {
            ForecastDaySnapshotLog.SnapshotRow f = forecasts.get(i);
            StringBuilder block = new StringBuilder();
            block.append(i + 1)
                    .append(") ")
                    .append(escTg(f.writtenArt().toString()));
            if (!f.smnUpdated().isBlank()) {
                block.append(" · upd ").append(escTg(f.smnUpdated()));
            }
            block.append("\n   min/max ")
                    .append(ForecastDaySnapshotLog.fmtTemp(f.tempMin()))
                    .append(" / ")
                    .append(ForecastDaySnapshotLog.fmtTemp(f.tempMax()));
            block.append(" · lluvia ≤").append(f.maxRainUpper()).append("%\n");
            if (sb.length() + block.length() > TELEGRAM_BODY_BUDGET) {
                break;
            }
            sb.append(block);
            shown++;
        }
        return shown;
    }

    private static String fmt(double t) {
        return String.format(Locale.ROOT, "%.1f °C", t);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escTg(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
