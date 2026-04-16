package ar.gob.smn.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class SmnClient {

    private static final String REFERER = "https://www.smn.gob.ar/";
    /** Chrome-like UA; SMN sometimes returns 401 for “bot” or generic clients on some locations (e.g. 10821). */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json = new ObjectMapper();

    static final class Station {
        private final int locationId;
        private final String label;

        Station(int locationId, String label) {
            this.locationId = locationId;
            this.label = label;
        }

        int locationId() {
            return locationId;
        }

        String label() {
            return label;
        }

        @Override
        public String toString() {
            return locationId + "=" + label;
        }
    }

    static final class Observation {
        private final String stationName;
        private final ZonedDateTime observationTime;
        private final Double temperature;
        private final Double feelsLike;
        private final Double humidity;
        private final Double pressure;
        private final Double visibilityKm;
        private final String windDirection;
        private final Double windSpeed;
        private final String description;

        Observation(
                String stationName,
                ZonedDateTime observationTime,
                Double temperature,
                Double feelsLike,
                Double humidity,
                Double pressure,
                Double visibilityKm,
                String windDirection,
                Double windSpeed,
                String description) {
            this.stationName = stationName;
            this.observationTime = observationTime;
            this.temperature = temperature;
            this.feelsLike = feelsLike;
            this.humidity = humidity;
            this.pressure = pressure;
            this.visibilityKm = visibilityKm;
            this.windDirection = windDirection;
            this.windSpeed = windSpeed;
            this.description = description;
        }

        String stationName() {
            return stationName;
        }

        ZonedDateTime observationTime() {
            return observationTime;
        }

        Double temperature() {
            return temperature;
        }

        Double feelsLike() {
            return feelsLike;
        }

        Double humidity() {
            return humidity;
        }

        Double pressure() {
            return pressure;
        }

        Double visibilityKm() {
            return visibilityKm;
        }

        String windDirection() {
            return windDirection;
        }

        Double windSpeed() {
            return windSpeed;
        }

        String description() {
            return description;
        }
    }

    List<Observation> fetchAll(List<Station> stations) throws IOException, InterruptedException {
        List<Observation> out = new ArrayList<>();
        for (Station s : stations) {
            out.add(fetch(s));
        }
        return out;
    }

    /**
     * Fetches one station; same contract as {@link #fetchAll} per station.
     */
    Observation fetchObservation(Station station) throws IOException, InterruptedException {
        return fetch(station);
    }

    /**
     * Short-term forecast for the same {@link Station} ({@code /v1/forecast/location/{id}}), plain text for email bodies.
     */
    String fetchForecastPlainText(Station station) throws IOException, InterruptedException {
        return fetchForecastPayload(station).plainText();
    }

    /** Forecast JSON as plain text plus {@code updated} for deduplication. */
    static final class ForecastPayload {
        private final String updated;
        private final String plainText;
        private final String telegramText;
        private final String telegramHtml;
        /** Raw JSON from {@code /v1/forecast/location/…} for slicing (e.g. single-day combined email/Telegram). */
        private final String forecastJson;

        ForecastPayload(
                String updated,
                String plainText,
                String telegramText,
                String telegramHtml,
                String forecastJson) {
            this.updated = updated;
            this.plainText = plainText;
            this.telegramText = telegramText;
            this.telegramHtml = telegramHtml;
            this.forecastJson = forecastJson;
        }

        String updated() {
            return updated;
        }

        String plainText() {
            return plainText;
        }

        /** Plain bulletin for logs and non-HTML consumers; headline uses {@code STATION/TAG}. */
        String telegramText() {
            return telegramText;
        }

        /** Full forecast as Telegram HTML ({@code parse_mode=HTML}). */
        String telegramHtml() {
            return telegramHtml;
        }

        String forecastJson() {
            return forecastJson;
        }
    }

    ForecastPayload fetchForecastPayload(Station station) throws IOException, InterruptedException {
        String url = "https://ws1.smn.gob.ar/v1/forecast/location/" + station.locationId();
        HttpResponse<String> res = smnGet(url);
        String body = res.body();
        String updated = ForecastFormatter.parseUpdated(body);
        String plain = ForecastFormatter.format(body);
        String headline = TelegramForecastFormatter.smnLocationHeadline(station);
        String telegram = TelegramForecastFormatter.format(body, headline);
        String telegramHtml = TelegramForecastFormatter.formatTelegramHtml(body, headline);
        return new ForecastPayload(updated, plain, telegram, telegramHtml, body);
    }

    private Observation fetch(Station station) throws IOException, InterruptedException {
        String url = "https://ws1.smn.gob.ar/v1/weather/location/" + station.locationId();
        HttpResponse<String> res = smnGet(url);
        return parse(res.body(), station.label());
    }

    private HttpResponse<String> smnGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
                .header("Referer", REFERER)
                .header("Origin", "https://www.smn.gob.ar")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-site")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401) {
            throw new IOException("SMN HTTP 401 for " + url + " — try SMN_LOCATION_IDS=4864 or later. Body: " + res.body());
        }
        if (res.statusCode() != 200) {
            throw new IOException("SMN HTTP " + res.statusCode() + " for " + url + ": " + res.body());
        }
        return res;
    }

    private Observation parse(String body, String fallbackName) throws IOException {
        JsonNode root = json.readTree(body);
        String dateStr = root.path("date").asText(null);
        if (dateStr == null) {
            throw new IOException("Missing date in SMN response");
        }
        ZonedDateTime obsTime = ZonedDateTime.parse(dateStr, ISO);

        JsonNode loc = root.path("location");
        String name = loc.path("name").asText(fallbackName);

        Double temp = num(root, "temperature");
        Double feelsLike = num(root, "feels_like");
        Double humidity = num(root, "humidity");
        Double pressure = num(root, "pressure");
        Double visibility = num(root, "visibility");
        JsonNode wind = root.path("wind");
        String windDir = text(wind, "direction");
        Double windSpeed = num(wind, "speed");
        String desc = text(root.path("weather"), "description");

        return new Observation(name, obsTime, temp, feelsLike, humidity, pressure, visibility, windDir, windSpeed, desc);
    }

    private static Double num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        return v.asDouble();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }
}
