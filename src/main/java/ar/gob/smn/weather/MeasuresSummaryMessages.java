package ar.gob.smn.weather;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Builds Spanish HTML bodies for scheduled temperature summaries from {@link MeasuresHistoryReader} rows. */
final class MeasuresSummaryMessages {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", ES_AR);

    private MeasuresSummaryMessages() {}

    static Optional<TempPeriod> aggregateTemps(List<MeasuresHistoryReader.MeasureRow> rows) {
        double minT = Double.POSITIVE_INFINITY;
        double maxT = Double.NEGATIVE_INFINITY;
        double sumT = 0;
        int nT = 0;
        double minF = Double.POSITIVE_INFINITY;
        double maxF = Double.NEGATIVE_INFINITY;
        double sumF = 0;
        int nF = 0;
        for (MeasuresHistoryReader.MeasureRow r : rows) {
            if (r.tempC() != null && !Double.isNaN(r.tempC())) {
                double t = r.tempC();
                minT = Math.min(minT, t);
                maxT = Math.max(maxT, t);
                sumT += t;
                nT++;
            }
            if (r.feelsC() != null && !Double.isNaN(r.feelsC())) {
                double f = r.feelsC();
                minF = Math.min(minF, f);
                maxF = Math.max(maxF, f);
                sumF += f;
                nF++;
            }
        }
        if (nT == 0) {
            return Optional.empty();
        }
        Double minFeel = nF > 0 ? minF : null;
        Double maxFeel = nF > 0 ? maxF : null;
        Double avgFeel = nF > 0 ? sumF / nF : null;
        return Optional.of(
                new TempPeriod(minT, maxT, sumT / nT, nT, minFeel, maxFeel, avgFeel, nF));
    }

    static Optional<Double> monthMinTemp(List<MeasuresHistoryReader.MeasureRow> rows) {
        Double min = null;
        for (MeasuresHistoryReader.MeasureRow r : rows) {
            if (r.tempC() == null || Double.isNaN(r.tempC())) {
                continue;
            }
            double t = r.tempC();
            if (min == null || t < min) {
                min = t;
            }
        }
        return Optional.ofNullable(min);
    }

    static Optional<Double> monthMaxTemp(List<MeasuresHistoryReader.MeasureRow> rows) {
        Double max = null;
        for (MeasuresHistoryReader.MeasureRow r : rows) {
            if (r.tempC() == null || Double.isNaN(r.tempC())) {
                continue;
            }
            double t = r.tempC();
            if (max == null || t > max) {
                max = t;
            }
        }
        return Optional.ofNullable(max);
    }

    static final class TempPeriod {
        private final double minTemp;
        private final double maxTemp;
        private final double avgTemp;
        private final int tempSamples;
        private final Double minFeel;
        private final Double maxFeel;
        private final Double avgFeel;
        private final int feelSamples;

        TempPeriod(
                double minTemp,
                double maxTemp,
                double avgTemp,
                int tempSamples,
                Double minFeel,
                Double maxFeel,
                Double avgFeel,
                int feelSamples) {
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
            this.avgTemp = avgTemp;
            this.tempSamples = tempSamples;
            this.minFeel = minFeel;
            this.maxFeel = maxFeel;
            this.avgFeel = avgFeel;
            this.feelSamples = feelSamples;
        }

        double minTemp() {
            return minTemp;
        }

        double maxTemp() {
            return maxTemp;
        }

        double avgTemp() {
            return avgTemp;
        }

        int tempSamples() {
            return tempSamples;
        }

        Double minFeel() {
            return minFeel;
        }

        Double maxFeel() {
            return maxFeel;
        }

        Double avgFeel() {
            return avgFeel;
        }

        int feelSamples() {
            return feelSamples;
        }
    }

    static String dailySubject(LocalDate dataDay) {
        return "Resumen térmico (día " + dataDay + ")";
    }

    static String weeklySubject(LocalDate weekStart, LocalDate weekEnd) {
        return "Resumen semanal " + weekStart + " – " + weekEnd;
    }

    static String monthlySubject(int year, int monthValue) {
        return "Temperaturas mes " + year + "-" + String.format(Locale.ROOT, "%02d", monthValue);
    }

    static String formatSectionDaily(String stationName, LocalDate dataDay, TempPeriod p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p><b>").append(esc(stationName)).append("</b> — ").append(esc(dataDay.format(DAY))).append("</p>");
        sb.append("<ul>");
        sb.append("<li>Mínima: <b>").append(fmt1(p.minTemp())).append("</b> °C</li>");
        sb.append("<li>Máxima: <b>").append(fmt1(p.maxTemp())).append("</b> °C</li>");
        sb.append("<li>Promedio (todas las mediciones del día): <b>").append(fmt1(p.avgTemp())).append("</b> °C</li>");
        if (p.feelSamples() > 0 && p.minFeel() != null && p.maxFeel() != null && p.avgFeel() != null) {
            sb.append("<li>Sensación térmica — mín: <b>")
                    .append(fmt1(p.minFeel()))
                    .append("</b> °C, máx: <b>")
                    .append(fmt1(p.maxFeel()))
                    .append("</b> °C, promedio: <b>")
                    .append(fmt1(p.avgFeel()))
                    .append("</b> °C</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    static String formatSectionWeekly(
            String stationName,
            LocalDate weekStart,
            LocalDate weekEnd,
            TempPeriod p,
            int rainDays,
            MeasuresHistoryReader.WindMax windMaxOrNull) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p><b>")
                .append(esc(stationName))
                .append("</b> — ")
                .append(esc(weekStart.format(DAY)))
                .append(" – ")
                .append(esc(weekEnd.format(DAY)))
                .append("</p>");
        sb.append("<ul>");
        sb.append("<li>Mínima: <b>").append(fmt1(p.minTemp())).append("</b> °C</li>");
        sb.append("<li>Máxima: <b>").append(fmt1(p.maxTemp())).append("</b> °C</li>");
        sb.append("<li>Promedio (todas las mediciones de la semana): <b>")
                .append(fmt1(p.avgTemp()))
                .append("</b> °C</li>");
        if (p.feelSamples() > 0 && p.minFeel() != null && p.maxFeel() != null && p.avgFeel() != null) {
            sb.append("<li>Sensación térmica — mín: <b>")
                    .append(fmt1(p.minFeel()))
                    .append("</b> °C, máx: <b>")
                    .append(fmt1(p.maxFeel()))
                    .append("</b> °C, promedio: <b>")
                    .append(fmt1(p.avgFeel()))
                    .append("</b> °C</li>");
        }
        sb.append("<li>Días con precipitación (según texto de condición): <b>").append(rainDays).append("</b></li>");
        if (windMaxOrNull != null) {
            sb.append("<li>Viento máximo en la semana: <b>")
                    .append(fmt1(windMaxOrNull.kmh()))
                    .append("</b> km/h");
            if (windMaxOrNull.direction() != null && !windMaxOrNull.direction().isEmpty()) {
                sb.append(", dirección <b>").append(esc(windMaxOrNull.direction())).append("</b>");
            }
            sb.append("</li>");
        } else {
            sb.append("<li>Viento máximo en la semana: —</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    static String sectionNoDataHtml(String stationLabel, String periodHuman) {
        return "<p><b>" + esc(stationLabel) + "</b>: sin mediciones en el historial para " + esc(periodHuman) + ".</p>";
    }

    static String formatSectionMonthly(String stationName, int year, int month, double minT, double maxT) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p><b>").append(esc(stationName)).append("</b> — ").append(month).append("/").append(year).append("</p>");
        sb.append("<ul>");
        sb.append("<li>Mínima del mes: <b>").append(fmt1(minT)).append("</b> °C</li>");
        sb.append("<li>Máxima del mes: <b>").append(fmt1(maxT)).append("</b> °C</li>");
        sb.append("</ul>");
        return sb.toString();
    }

    static String wrapEmail(String title, List<String> sections, String hostFooter) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><body style=\"font-family:sans-serif;\">");
        sb.append("<h2 style=\"margin-top:0;\">").append(esc(title)).append("</h2>");
        for (String s : sections) {
            sb.append(s);
        }
        String h = hostFooter != null && !hostFooter.isBlank() ? hostFooter.trim() : "unknown";
        sb.append("<p style=\"color:#555;\">(").append(esc(h)).append(")</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** Same subject line as email {@code <h2>}; Telegram supports {@code <b>} only for structure. */
    static String telegramTitleBold(String plainSubject) {
        return "<b>" + esc(plainSubject) + "</b>\n\n";
    }

    /** Host line like email footer: {@code (hostname)}. */
    static String telegramHostFooter(String reportHost) {
        String h = reportHost != null && !reportHost.isBlank() ? reportHost.trim() : "unknown";
        return "\n(" + esc(h) + ")";
    }

    /**
     * Telegram HTML mirroring {@link #formatSectionDaily}: station + date, then the same list labels as the email
     * (bullet character — Telegram does not support {@code <ul>}).
     */
    static String telegramSectionDaily(String stationName, LocalDate dataDay, TempPeriod p) {
        String nl = "\n";
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(esc(stationName)).append("</b> — ").append(esc(dataDay.format(DAY))).append(nl);
        sb.append("• Mínima: <b>").append(fmt1(p.minTemp())).append("</b> °C").append(nl);
        sb.append("• Máxima: <b>").append(fmt1(p.maxTemp())).append("</b> °C").append(nl);
        sb.append("• Promedio (todas las mediciones del día): <b>")
                .append(fmt1(p.avgTemp()))
                .append("</b> °C")
                .append(nl);
        if (p.feelSamples() > 0 && p.minFeel() != null && p.maxFeel() != null && p.avgFeel() != null) {
            sb.append("• Sensación térmica — mín: <b>")
                    .append(fmt1(p.minFeel()))
                    .append("</b> °C, máx: <b>")
                    .append(fmt1(p.maxFeel()))
                    .append("</b> °C, promedio: <b>")
                    .append(fmt1(p.avgFeel()))
                    .append("</b> °C")
                    .append(nl);
        }
        sb.append(nl);
        return sb.toString();
    }

    /** Mirrors {@link #formatSectionWeekly} list text. */
    static String telegramSectionWeekly(
            String stationName,
            LocalDate weekStart,
            LocalDate weekEnd,
            TempPeriod p,
            int rainDays,
            MeasuresHistoryReader.WindMax windMaxOrNull) {
        String nl = "\n";
        StringBuilder sb = new StringBuilder();
        sb.append("<b>")
                .append(esc(stationName))
                .append("</b> — ")
                .append(esc(weekStart.format(DAY)))
                .append(" – ")
                .append(esc(weekEnd.format(DAY)))
                .append(nl);
        sb.append("• Mínima: <b>").append(fmt1(p.minTemp())).append("</b> °C").append(nl);
        sb.append("• Máxima: <b>").append(fmt1(p.maxTemp())).append("</b> °C").append(nl);
        sb.append("• Promedio (todas las mediciones de la semana): <b>")
                .append(fmt1(p.avgTemp()))
                .append("</b> °C")
                .append(nl);
        if (p.feelSamples() > 0 && p.minFeel() != null && p.maxFeel() != null && p.avgFeel() != null) {
            sb.append("• Sensación térmica — mín: <b>")
                    .append(fmt1(p.minFeel()))
                    .append("</b> °C, máx: <b>")
                    .append(fmt1(p.maxFeel()))
                    .append("</b> °C, promedio: <b>")
                    .append(fmt1(p.avgFeel()))
                    .append("</b> °C")
                    .append(nl);
        }
        sb.append("• Días con precipitación (según texto de condición): <b>").append(rainDays).append("</b>").append(nl);
        if (windMaxOrNull != null) {
            sb.append("• Viento máximo en la semana: <b>")
                    .append(fmt1(windMaxOrNull.kmh()))
                    .append("</b> km/h");
            if (windMaxOrNull.direction() != null && !windMaxOrNull.direction().isEmpty()) {
                sb.append(", dirección <b>").append(esc(windMaxOrNull.direction())).append("</b>");
            }
            sb.append(nl);
        } else {
            sb.append("• Viento máximo en la semana: —").append(nl);
        }
        sb.append(nl);
        return sb.toString();
    }

    /** Mirrors {@link #formatSectionMonthly}. */
    static String telegramSectionMonthly(String stationName, int year, int month, double minT, double maxT) {
        String nl = "\n";
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(esc(stationName)).append("</b> — ").append(month).append("/").append(year).append(nl);
        sb.append("• Mínima del mes: <b>").append(fmt1(minT)).append("</b> °C").append(nl);
        sb.append("• Máxima del mes: <b>").append(fmt1(maxT)).append("</b> °C").append(nl);
        sb.append(nl);
        return sb.toString();
    }

    /** Same sentence as {@link #sectionNoDataHtml} (without {@code <p>}). */
    static String telegramSectionNoData(String stationLabel, String periodHuman) {
        return "<b>"
                + esc(stationLabel)
                + "</b>: sin mediciones en el historial para "
                + esc(periodHuman)
                + ".\n\n";
    }

    /**
     * Plain text for email {@code multipart/alternative}: same wording as Telegram HTML fragments, without tags.
     */
    static String telegramContentAsPlain(String telegramHtmlFragment) {
        if (telegramHtmlFragment == null || telegramHtmlFragment.isEmpty()) {
            return "";
        }
        return telegramHtmlFragment
                .replace("<b>", "")
                .replace("</b>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");
    }

    private static String fmt1(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
