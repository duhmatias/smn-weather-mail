package ar.gob.smn.weather;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/** Spanish HTML / Telegram for daily forecast-vs-observed validation (CABA). */
final class ForecastValidationMessages {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMM uuuu", ES_AR);

    private ForecastValidationMessages() {}

    static String subject(LocalDate dataDay) {
        return "Validación pronóstico vs observado — " + dataDay;
    }

    static String buildEmailHtml(
            String stationLabel,
            LocalDate dataDay,
            Optional<MeasuresSummaryMessages.TempPeriod> observed,
            boolean observedRain,
            Optional<ForecastDaySnapshotLog.SnapshotRow> firstForecast,
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

        sb.append("<h3>Pronóstico (primera aparición de ese día en JSON SMN guardado por esta app)</h3>\n");
        if (firstForecast.isPresent()) {
            ForecastDaySnapshotLog.SnapshotRow f = firstForecast.get();
            sb.append("<p>Registrado (ART): ").append(esc(f.writtenArt().toString())).append("</p>\n");
            if (!f.smnUpdated().isBlank()) {
                sb.append("<p>SMN <code>updated</code>: ").append(esc(f.smnUpdated())).append("</p>\n");
            }
            sb.append("<p><code>temp_min</code> / <code>temp_max</code> del boletín: ")
                    .append(ForecastDaySnapshotLog.fmtTemp(f.tempMin()))
                    .append(" / ")
                    .append(ForecastDaySnapshotLog.fmtTemp(f.tempMax()))
                    .append("</p>\n");
            sb.append("<p>Máx. probabilidad de lluvia (tope de rangos por período): ")
                    .append(f.maxRainUpper())
                    .append(" %</p>\n");
        } else {
            sb.append("<p><i>No hay en el historial local ningún JSON SMN que incluya ese día. ")
                    .append("Hace falta haber corrido este servicio cuando SMN ya exponía ese día en el pronóstico.</i></p>\n");
        }

        sb.append("<p style=\"color:#555;font-size:90%\">")
                .append("Comparación orientativa; el pronóstico citado es el primero almacenado mientras corrían los envíos.")
                .append("</p>\n");
        sb.append("<p>(").append(h).append(")</p>\n");
        return sb.toString();
    }

    static String buildTelegramHtml(
            String stationLabel,
            LocalDate dataDay,
            Optional<MeasuresSummaryMessages.TempPeriod> observed,
            boolean observedRain,
            Optional<ForecastDaySnapshotLog.SnapshotRow> firstForecast,
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

        sb.append("<b>Pronóstico (primera aparición en historial)</b>\n");
        if (firstForecast.isPresent()) {
            ForecastDaySnapshotLog.SnapshotRow f = firstForecast.get();
            sb.append("Registrado: ").append(escTg(f.writtenArt().toString())).append("\n");
            if (!f.smnUpdated().isBlank()) {
                sb.append("updated: ").append(escTg(f.smnUpdated())).append("\n");
            }
            sb.append("min / max: ")
                    .append(ForecastDaySnapshotLog.fmtTemp(f.tempMin()))
                    .append(" / ")
                    .append(ForecastDaySnapshotLog.fmtTemp(f.tempMax()))
                    .append("\n");
            sb.append("Máx. prob. lluvia: ").append(f.maxRainUpper()).append(" %\n");
        } else {
            sb.append("(Sin datos en historial local.)\n");
        }

        sb.append("\n(").append(h).append(")");
        return sb.toString();
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
