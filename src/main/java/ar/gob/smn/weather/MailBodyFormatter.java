package ar.gob.smn.weather;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class MailBodyFormatter {

    private static final ZoneId ART = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT);

    private MailBodyFormatter() {}

    static String formatSingle(SmnClient.Observation o) {
        StringBuilder sb = new StringBuilder();
        sb.append("SMN weather update (Buenos Aires time)\n\n");
        appendObservation(sb, o);
        sb.append("Source: Servicio Meteorológico Nacional (ws1.smn.gob.ar)\n");
        return sb.toString();
    }

    private static void appendObservation(StringBuilder sb, SmnClient.Observation o) {
        ZonedDateTime local = o.observationTime().withZoneSameInstant(ART);
        sb.append("--- ").append(o.stationName()).append(" ---\n");
        sb.append("Observation time: ").append(local.format(FMT)).append("\n");
        sb.append(line("Temperature", o.temperature(), " °C"));
        if (o.feelsLike() != null) {
            sb.append(line("Sensación térmica", o.feelsLike(), " °C"));
        }
        sb.append(line("Humidity", o.humidity(), " %"));
        sb.append(line("Pressure", o.pressure(), " hPa"));
        sb.append(line("Visibility", o.visibilityKm(), " km"));
        sb.append("Wind: ");
        if (o.windSpeed() != null) {
            sb.append(o.windSpeed()).append(" km/h");
        } else {
            sb.append("—");
        }
        if (o.windDirection() != null) {
            sb.append(" from ").append(o.windDirection());
        }
        sb.append("\n");
        sb.append("Conditions: ").append(o.description() != null ? o.description() : "—").append("\n\n");
    }

    private static String line(String label, Double value, String unit) {
        if (value == null) {
            return label + ": —\n";
        }
        return label + ": " + value + unit + "\n";
    }
}
