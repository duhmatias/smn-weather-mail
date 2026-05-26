package ar.gob.smn.weather;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Spanish HTML / Telegram for daily forecast-vs-observed validation (CABA). */
final class ForecastValidationMessages {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final ZoneId ART = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMM uuuu", ES_AR);
    private static final DateTimeFormatter LOG_DAY_SHORT = DateTimeFormatter.ofPattern("d MMM uuuu", ES_AR);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", ES_AR);
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

        sb.append("<h3>Comparación observado vs pronóstico</h3>\n");
        if (observed.isPresent() && !forecastSnapshots.isEmpty()) {
            MeasuresSummaryMessages.TempPeriod p = observed.get();
            ForecastDaySnapshotLog.SnapshotRow closest = forecastSnapshots.get(0);  // First = most recent
            ForecastDaySnapshotLog.SnapshotRow farthest = forecastSnapshots.get(forecastSnapshots.size() - 1);

            sb.append("<p><b>Mínima</b><br>");
            sb.append("Real: ").append(fmt(p.minTemp()));
            if (p.minTempAt().isPresent()) {
                sb.append(" (").append(esc(fmtClock(p.minTempAt().get()))).append(")");
            }
            sb.append("<br>Pronóstico cercano: ").append(ForecastDaySnapshotLog.fmtTemp(closest.tempMin()));
            sb.append(" (").append(esc(closest.logDayArt().format(LOG_DAY_SHORT))).append(")");
            sb.append("<br>Pronóstico lejano: ").append(ForecastDaySnapshotLog.fmtTemp(farthest.tempMin()));
            sb.append(" (").append(esc(farthest.logDayArt().format(LOG_DAY_SHORT))).append(")");
            sb.append("</p>\n");

            sb.append("<p><b>Máxima</b><br>");
            sb.append("Real: ").append(fmt(p.maxTemp()));
            if (p.maxTempAt().isPresent()) {
                sb.append(" (").append(esc(fmtClock(p.maxTempAt().get()))).append(")");
            }
            sb.append("<br>Pronóstico cercano: ").append(ForecastDaySnapshotLog.fmtTemp(closest.tempMax()));
            sb.append(" (").append(esc(closest.logDayArt().format(LOG_DAY_SHORT))).append(")");
            sb.append("<br>Pronóstico lejano: ").append(ForecastDaySnapshotLog.fmtTemp(farthest.tempMax()));
            sb.append(" (").append(esc(farthest.logDayArt().format(LOG_DAY_SHORT))).append(")");
            sb.append("</p>\n");
        } else if (observed.isPresent()) {
            MeasuresSummaryMessages.TempPeriod p = observed.get();
            sb.append("<p><b>Observado (sin pronósticos históricos para comparar)</b><br>");
            sb.append("Mín / máx temperatura: ")
                    .append(fmt(p.minTemp()))
                    .append(" / ")
                    .append(fmt(p.maxTemp()));
            if (p.minTempAt().isPresent()) {
                sb.append(" — hora mín. ART: ").append(esc(fmtClock(p.minTempAt().get())));
            }
            if (p.maxTempAt().isPresent()) {
                sb.append(" — hora máx. ART: ").append(esc(fmtClock(p.maxTempAt().get())));
            }
            sb.append("</p>\n");
        } else {
            sb.append("<p>(Sin mediciones en el archivo local para ese día.)</p>\n");
        }
        sb.append("<p>Lluvia (condiciones reportadas): ").append(observedRain ? "sí" : "no").append("</p>\n");

        sb.append("<h3>Último pronóstico de cada día anterior (ART) que incluía este día</h3>\n");
        if (forecastSnapshots.isEmpty()) {
            sb.append("<p><i>No hay en el historial local, para días anteriores al observado, ningún JSON SMN que ")
                    .append("incluyera este día en <code>forecast[]</code>. Hace falta haber corrido este servicio cuando ")
                    .append("SMN ya exponía ese día en el pronóstico.</i></p>\n");
        } else {
            sb.append("<p>")
                    .append(forecastSnapshots.size())
                    .append(" día(s): por cada día calendario previo al observado, el <b>último</b> boletín guardado ese ")
                    .append("día que aún contenía el pronóstico para ")
                    .append(esc(dataDay.toString()))
                    .append(" (orden: día más reciente primero).</p>\n");
            sb.append("<ol>\n");
            for (ForecastDaySnapshotLog.SnapshotRow f : forecastSnapshots) {
                sb.append("<li><p><b>Día del boletín (ART):</b> ")
                        .append(esc(f.logDayArt().format(LOG_DAY_SHORT)))
                        .append(" — <b>registrado:</b> ")
                        .append(esc(f.writtenArt().toString()))
                        .append("</p>\n");
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
                .append("Comparación orientativa: una fila por día anterior al observado; cada fila es el último JSON ")
                .append("almacenado ese día (ART) en el que SMN seguía incluyendo el día comparado en ")
                .append("<code>forecast</code>.")
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

        sb.append("<b>Comparación observado vs pronóstico</b>\n");
        if (observed.isPresent() && !forecastSnapshots.isEmpty()) {
            MeasuresSummaryMessages.TempPeriod p = observed.get();
            ForecastDaySnapshotLog.SnapshotRow closest = forecastSnapshots.get(0);
            ForecastDaySnapshotLog.SnapshotRow farthest = forecastSnapshots.get(forecastSnapshots.size() - 1);

            sb.append("<b>Min</b> ").append(fmt(p.minTemp()));
            if (p.minTempAt().isPresent()) {
                sb.append(" (").append(escTg(fmtClock(p.minTempAt().get()))).append(")");
            }
            sb.append(" <b>Closest</b> ").append(ForecastDaySnapshotLog.fmtTemp(closest.tempMin()));
            sb.append(" (").append(escTg(fmtShortDate(closest.logDayArt()))).append(")");
            sb.append(" <b>Faraway</b> ").append(ForecastDaySnapshotLog.fmtTemp(farthest.tempMin()));
            sb.append(" (").append(escTg(fmtShortDate(farthest.logDayArt()))).append(")\n");

            sb.append("<b>Max</b> ").append(fmt(p.maxTemp()));
            if (p.maxTempAt().isPresent()) {
                sb.append(" (").append(escTg(fmtClock(p.maxTempAt().get()))).append(")");
            }
            sb.append(" <b>Closest</b> ").append(ForecastDaySnapshotLog.fmtTemp(closest.tempMax()));
            sb.append(" (").append(escTg(fmtShortDate(closest.logDayArt()))).append(")");
            sb.append(" <b>Faraway</b> ").append(ForecastDaySnapshotLog.fmtTemp(farthest.tempMax()));
            sb.append(" (").append(escTg(fmtShortDate(farthest.logDayArt()))).append(")\n");
        } else if (observed.isPresent()) {
            MeasuresSummaryMessages.TempPeriod p = observed.get();
            sb.append("Mín / máx: ")
                    .append(fmt(p.minTemp()))
                    .append(" / ")
                    .append(fmt(p.maxTemp()));
            if (p.minTempAt().isPresent()) {
                sb.append(" — mín. ").append(escTg(fmtClock(p.minTempAt().get())));
            }
            if (p.maxTempAt().isPresent()) {
                sb.append(" — máx. ").append(escTg(fmtClock(p.maxTempAt().get())));
            }
            sb.append("\n");
        } else {
            sb.append("(Sin mediciones locales.)\n");
        }
        sb.append("Lluvia: ").append(observedRain ? "sí" : "no").append("\n\n");

        sb.append("<b>Último pronóstico por día anterior (ART) que incluía este día</b>\n");
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
                    .append(escTg(fmtShortDate(f.logDayArt())))
                    .append(" ")
                    .append(ForecastDaySnapshotLog.fmtTemp(f.tempMin()))
                    .append("/")
                    .append(ForecastDaySnapshotLog.fmtTemp(f.tempMax()));
            block.append(" lluvia ≤").append(f.maxRainUpper()).append("%\n");
            if (sb.length() + block.length() > TELEGRAM_BODY_BUDGET) {
                break;
            }
            sb.append(block);
            shown++;
        }
        return shown;
    }

    /**
     * Human-readable SMN bulletin time for Telegram: {@code hora ART HH:mm} from JSON {@code updated} when parseable,
     * otherwise the snapshot {@code written} instant’s clock in ART.
     */
    private static String snapshotSmnHoraArt(String smnUpdated, ZonedDateTime writtenArt) {
        return "hora ART " + parseSmnUpdatedInstant(smnUpdated).orElse(writtenArt).withZoneSameInstant(ART).format(CLOCK);
    }

    private static Optional<ZonedDateTime> parseSmnUpdatedInstant(String smnUpdated) {
        if (smnUpdated == null || smnUpdated.isBlank()) {
            return Optional.empty();
        }
        String t = smnUpdated.trim();
        try {
            return Optional.of(OffsetDateTime.parse(t, DateTimeFormatter.ISO_OFFSET_DATE_TIME).atZoneSameInstant(ART));
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(ZonedDateTime.parse(t));
            } catch (DateTimeParseException e2) {
                return Optional.empty();
            }
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static String fmt(double t) {
        return String.format(Locale.ROOT, "%.1f °C", t);
    }

    private static String fmtClock(ZonedDateTime z) {
        return z.withZoneSameInstant(ART).format(CLOCK);
    }

    /** Format date as "d/M" (e.g., "24/5") */
    private static String fmtShortDate(LocalDate date) {
        return date.getDayOfMonth() + "/" + date.getMonthValue();
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
