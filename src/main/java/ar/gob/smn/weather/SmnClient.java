package ar.gob.smn.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SmnClient {

    private static final Logger LOG = Logger.getLogger(SmnClient.class.getName());

    private static final String REFERER = "https://www.smn.gob.ar/";
    /** Chrome-like UA; SMN sometimes returns 401 for “bot” or generic clients on some locations (e.g. 10821). */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final ZoneId ART = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final String SMN_WEATHER_PREFIX = "https://ws1.smn.gob.ar/v1/weather/location/";
    private static final String SMN_FORECAST_PREFIX = "https://ws1.smn.gob.ar/v1/forecast/location/";
    /** Prefer “current”-like segment when building {@link Observation} from forecast JSON (same order as SMN day parts). */
    private static final String[] FORECAST_PERIOD_KEYS = {"afternoon", "morning", "night", "early_morning"};
    /** Warm browser-like session (Cloudflare / cookies) before calling {@code ws1.smn.gob.ar}. */
    private static final String[] WARMUP_PAGE_URLS = {
        "https://www.smn.gob.ar/",
        "https://www.smn.gob.ar/pronostico",
    };

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    /** When set, sent as {@code Cookie} on every {@code ws1} request (skip automatic jar + warm-up). */
    private final String manualCookieHeader;
    private volatile boolean sessionWarmedUp;

    /**
     * @param smnWsCookieHeader optional merged from {@link Config} ({@code smn.ws.cookies} / {@code SMN_WS_COOKIES}):
     *     paste the {@code Cookie} header from DevTools for {@code smn.gob.ar} if API returns 401 without it.
     */
    SmnClient(String smnWsCookieHeader) {
        if (smnWsCookieHeader != null && !smnWsCookieHeader.isBlank()) {
            this.manualCookieHeader = smnWsCookieHeader.trim();
            this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        } else {
            this.manualCookieHeader = null;
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            this.http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .cookieHandler(cookies)
                    .build();
        }
    }

    static final class Station {
        private final int locationId;
        private final String label;
        /** Georef row columns 4–5: SMN may only accept these for {@code /weather|forecast/location/{id}}. */
        private final int[] alternateApiIds;

        Station(int locationId, String label) {
            this(locationId, label, new int[0]);
        }

        Station(int locationId, String label, int[] alternateApiIds) {
            this.locationId = locationId;
            this.label = label;
            this.alternateApiIds = alternateApiIds != null ? alternateApiIds.clone() : new int[0];
        }

        int locationId() {
            return locationId;
        }

        String label() {
            return label;
        }

        int[] alternateApiIds() {
            return alternateApiIds.clone();
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
        /** SMN JSON {@code location.id}; null if unknown (use caller’s station id for tags). */
        private final Integer smnLocationId;

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
                String description,
                Integer smnLocationId) {
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
            this.smnLocationId = smnLocationId;
        }

        /** Nullable; when set, prefer over configured id for {@code @SMN…} tag lines. */
        Integer smnLocationId() {
            return smnLocationId;
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
     * Calls the same {@code GET /v1/weather/location/{id}} endpoints as the configured conditions mail poller, before
     * georef/other locations — identical API surface; may align cookies/session with SMN before arbitrary ids. Responses
     * are discarded; failures are logged at FINE and do not throw.
     */
    void primeWeatherApiForStations(List<Station> stations) throws IOException, InterruptedException {
        if (stations == null || stations.isEmpty()) {
            return;
        }
        int[] ids = stations.stream().mapToInt(Station::locationId).toArray();
        LOG.info(
                "SMN: priming ws1 with /v1/weather/location/ for poll ids "
                        + Arrays.toString(ids)
                        + " (same API as conditions mail loop)");
        for (Station s : stations) {
            String u = SMN_WEATHER_PREFIX + s.locationId();
            HttpResponse<String> w = smnSend(u);
            if (w.statusCode() == 401) {
                w = smnSend(u + "?");
            }
            int code = w.statusCode();
            if (code != 200) {
                LOG.log(Level.FINE, "SMN prime " + u + " -> HTTP " + code);
            }
        }
    }

    /**
     * Open-data text with many stations ({@code observaciones/tiepreYYYYMMDD.txt} on ssl.smn.gob.ar). Not the ws1 JSON
     * API. Tries today’s file (ART), then yesterday’s if the first is missing or empty.
     * <p>
     * The response body is read into a {@link String} in memory only — no temporary file is written to disk, so there is
     * nothing to delete after {@code /current} uses it.
     */
    Optional<String> fetchTieprePlainText() throws IOException, InterruptedException {
        LocalDate today = LocalDate.now(ART);
        for (int i = 0; i < 2; i++) {
            LocalDate d = today.minusDays(i);
            String path = "observaciones/tiepre" + d.format(DateTimeFormatter.BASIC_ISO_DATE) + ".txt";
            String url =
                    "https://ssl.smn.gob.ar/dpd/descarga_opendata.php?file="
                            + URLEncoder.encode(path, StandardCharsets.UTF_8);
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(url))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "text/plain,*/*")
                            .header("Accept-Language", "es-AR,es;q=0.9")
                            .GET()
                            .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = res.statusCode();
            String body = decodeTiepreResponseBody(res.body(), res.headers());
            if (code == 200 && body != null && !body.isBlank()) {
                LOG.fine(() -> "SMN tiepre open data: OK " + path);
                return Optional.of(body);
            }
            LOG.log(Level.FINE, () -> "SMN tiepre open data: HTTP " + code + " for " + path);
        }
        return Optional.empty();
    }

    /**
     * SMN’s {@code descarga_opendata.php} tiepre responses declare {@code text/plain;charset=iso-8859-1}. Honor that
     * when present; otherwise try UTF-8 and fall back to ISO-8859-1 if the UTF-8 decode contains U+FFFD (mis-decoded
     * Latin-1 bytes).
     */
    private static String decodeTiepreResponseBody(byte[] raw, HttpHeaders headers) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        Optional<Charset> declared =
                headers.firstValue("Content-Type").flatMap(SmnClient::charsetFromContentType);
        if (declared.isPresent()) {
            return new String(raw, declared.get());
        }
        String asUtf8 = new String(raw, StandardCharsets.UTF_8);
        if (asUtf8.indexOf('\uFFFD') < 0) {
            return asUtf8;
        }
        LOG.fine("SMN tiepre: no charset in Content-Type; decoded as ISO-8859-1 (UTF-8 contained U+FFFD)");
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    private static Optional<Charset> charsetFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return Optional.empty();
        }
        int i = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (i < 0) {
            return Optional.empty();
        }
        String tail = contentType.substring(i + "charset=".length()).trim();
        if (tail.startsWith("\"")) {
            int end = tail.indexOf('"', 1);
            tail = end > 1 ? tail.substring(1, end).trim() : tail.substring(1).trim();
        } else {
            int semi = tail.indexOf(';');
            if (semi >= 0) {
                tail = tail.substring(0, semi).trim();
            }
        }
        try {
            return Optional.of(Charset.forName(tail));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Location search as on the SMN pronóstico page (typeahead): {@code GET /v1/georef/location/search?name=…}. Each result
     * row’s first integer is the id used for {@code /v1/weather/location/{id}} (with retries/fallback inside
     * {@link #fetchObservation}). Rows are filtered to those whose text fields contain {@code substringFilter}
     * (case-insensitive).
     */
    List<Station> searchGeorefLocationsForCurrent(String nameQuery, String substringFilter, int maxRows)
            throws IOException, InterruptedException {
        String enc = URLEncoder.encode(nameQuery, StandardCharsets.UTF_8);
        String url = "https://ws1.smn.gob.ar/v1/georef/location/search?name=" + enc;
        HttpResponse<String> res = smnGet(url);
        JsonNode root = json.readTree(res.body());
        if (!root.isArray()) {
            return List.of();
        }
        String f = substringFilter.toLowerCase(Locale.ROOT);
        List<Station> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode row : root) {
            if (out.size() >= maxRows) {
                break;
            }
            if (!row.isArray() || row.size() < 2) {
                continue;
            }
            int id = row.get(0).asInt();
            if (seen.contains(id) || !georefRowTextContains(row, f)) {
                continue;
            }
            seen.add(id);
            out.add(new Station(id, georefRowLabel(row), georefAlternateApiIds(row, id)));
        }
        return List.copyOf(out);
    }

    /** Georef JSON row indices 4 and 5 often hold other SMN network ids worth trying when the primary id returns 401. */
    private static int[] georefAlternateApiIds(JsonNode row, int primaryId) {
        if (!row.isArray() || row.size() <= 5) {
            return new int[0];
        }
        List<Integer> list = new ArrayList<>();
        for (int idx = 4; idx <= 5; idx++) {
            JsonNode n = row.get(idx);
            if (n != null && n.isNumber()) {
                int v = n.asInt();
                if (v > 0 && v != primaryId && !list.contains(v)) {
                    list.add(v);
                }
            }
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String georefRowLabel(JsonNode row) {
        if (!row.isArray() || row.size() < 4) {
            return "Ubicación " + row.path(0).asInt();
        }
        String city = row.get(1).asText("");
        String dep = row.get(2).asText("");
        String prov = row.get(3).asText("");
        String code = row.size() > 9 ? row.get(9).asText("") : "";
        StringBuilder sb = new StringBuilder(city);
        if (!dep.isBlank() && !dep.equals(city)) {
            sb.append(", ").append(dep);
        }
        if (!prov.isBlank()) {
            sb.append(" — ").append(prov);
        }
        if (!code.isBlank()) {
            sb.append(" (").append(code).append(')');
        }
        return sb.toString();
    }

    private static boolean georefRowTextContains(JsonNode row, String qLower) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode x : row) {
            if (x.isTextual()) {
                sb.append(' ').append(x.asText());
            } else if (x.isNumber()) {
                sb.append(' ').append(x.asText());
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT).contains(qLower);
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
        String url = SMN_FORECAST_PREFIX + station.locationId();
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
        List<Integer> chain = new ArrayList<>();
        chain.add(station.locationId());
        for (int a : station.alternateApiIds()) {
            if (!chain.contains(a)) {
                chain.add(a);
            }
        }
        for (int i = 0; i < chain.size(); i++) {
            try {
                return fetchWeatherOrForecastForLocationId(chain.get(i), station.label());
            } catch (IOException e) {
                if (isDeniedWeatherAndForecast401(e) && i < chain.size() - 1) {
                    continue;
                }
                if (isDeniedWeatherAndForecast401(e) && i == chain.size() - 1 && chain.size() > 1) {
                    throw new IOException(
                            "SMN HTTP 401 para tiempo y pronóstico (ids probados: "
                                    + chain
                                    + "). Si persiste, configurá smn.ws.cookies / SMN_WS_COOKIES (Cookie desde el"
                                    + " navegador en smn.gob.ar). Detalle: "
                                    + e.getMessage(),
                            e);
                }
                throw e;
            }
        }
        throw new IOException("SMN: lista de ids vacía para " + station.label());
    }

    private static boolean isDeniedWeatherAndForecast401(IOException e) {
        String m = e.getMessage();
        return m != null && m.contains("SMN HTTP 401 for weather and forecast");
    }

    private Observation fetchWeatherOrForecastForLocationId(int id, String fallbackLabel)
            throws IOException, InterruptedException {
        String wUrl = SMN_WEATHER_PREFIX + id;
        HttpResponse<String> w = smnSend(wUrl);
        if (w.statusCode() == 401) {
            w = smnSend(wUrl + "?");
        }
        if (w.statusCode() == 200) {
            return parse(w.body(), fallbackLabel);
        }
        if (w.statusCode() == 401) {
            String fUrl = SMN_FORECAST_PREFIX + id;
            HttpResponse<String> f = smnSend(fUrl);
            if (f.statusCode() == 401) {
                f = smnSend(fUrl + "?");
            }
            if (f.statusCode() == 200) {
                return parseObservationFromForecast(f.body(), fallbackLabel);
            }
            throw new IOException(
                    "SMN HTTP 401 for weather and forecast (location "
                            + id
                            + "). El servicio no expuso datos para esta ubicación. Weather: "
                            + abbrevBody(w.body())
                            + " | Forecast: "
                            + abbrevBody(f.body()));
        }
        throw new IOException("SMN HTTP " + w.statusCode() + " for " + wUrl + ": " + w.body());
    }

    private void ensureSmnSession() throws IOException, InterruptedException {
        if (manualCookieHeader != null) {
            return;
        }
        if (sessionWarmedUp) {
            return;
        }
        synchronized (this) {
            if (sessionWarmedUp) {
                return;
            }
            for (String url : WARMUP_PAGE_URLS) {
                HttpRequest warm =
                        HttpRequest.newBuilder(URI.create(url))
                                .header("User-Agent", USER_AGENT)
                                .header(
                                        "Accept",
                                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                                .header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
                                .header("Upgrade-Insecure-Requests", "1")
                                .GET()
                                .build();
                http.send(warm, HttpResponse.BodyHandlers.discarding());
            }
            sessionWarmedUp = true;
        }
    }

    private static String abbrevBody(String body) {
        if (body == null) {
            return "";
        }
        String t = body.trim();
        return t.length() > 400 ? t.substring(0, 400) + "…" : t;
    }

    private HttpResponse<String> smnSend(String url) throws IOException, InterruptedException {
        ensureSmnSession();
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
                        .header("Referer", REFERER)
                        .header("Origin", "https://www.smn.gob.ar")
                        .header("Sec-CH-UA", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                        .header("Sec-CH-UA-Mobile", "?0")
                        .header("Sec-CH-UA-Platform", "\"Windows\"")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "same-site")
                        .GET();
        if (manualCookieHeader != null) {
            b.header("Cookie", manualCookieHeader);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> smnGet(String url) throws IOException, InterruptedException {
        HttpResponse<String> res = smnSend(url);
        if (res.statusCode() == 401) {
            res = smnSend(url + "?");
        }
        if (res.statusCode() == 401) {
            throw new IOException(
                    "SMN HTTP 401 for "
                            + url
                            + " — el id puede no tener datos públicos; probá otro id o otra ubicación. Body: "
                            + res.body());
        }
        if (res.statusCode() != 200) {
            throw new IOException("SMN HTTP " + res.statusCode() + " for " + url + ": " + res.body());
        }
        return res;
    }

    /**
     * When {@code /v1/weather/location/…} returns 401 but forecast does not, build an approximate “current” line from
     * today’s forecast period (SMN does not always expose live obs for every georef id).
     */
    private Observation parseObservationFromForecast(String body, String fallbackName) throws IOException {
        JsonNode root = json.readTree(body);
        String updatedStr = root.path("updated").asText(null);
        ZonedDateTime obsTime;
        if (updatedStr != null && !updatedStr.isBlank()) {
            obsTime = ZonedDateTime.parse(updatedStr, ISO);
        } else {
            obsTime = ZonedDateTime.now(ART);
        }
        JsonNode loc = root.path("location");
        String name = loc.path("name").asText(fallbackName);
        LocalDate today = obsTime.withZoneSameInstant(ART).toLocalDate();
        JsonNode days = root.path("forecast");
        if (!days.isArray() || days.isEmpty()) {
            throw new IOException("Forecast JSON has no forecast[]");
        }
        JsonNode day = findForecastDayForLocalDate(days, today);
        JsonNode seg = firstForecastPeriodWithTemperature(day);
        if (seg == null) {
            throw new IOException("Forecast day has no period with temperature");
        }
        Double temp = num(seg, "temperature");
        Double humidity = num(seg, "humidity");
        JsonNode wind = seg.path("wind");
        String windDir = text(wind, "direction");
        Double windSpeed = windSpeedMid(wind.path("speed_range"));
        String wx = text(seg.path("weather"), "description");
        String desc =
                (wx != null ? wx : "—")
                        + " (aprox. desde pronóstico SMN; observación en vivo no disponible para este id)";
        Integer smnLid = null;
        if (loc.has("id") && !loc.get("id").isNull()) {
            smnLid = loc.get("id").asInt();
        }
        return new Observation(name, obsTime, temp, null, humidity, null, null, windDir, windSpeed, desc, smnLid);
    }

    private static JsonNode findForecastDayForLocalDate(JsonNode days, LocalDate today) {
        for (JsonNode day : days) {
            String ds = day.path("date").asText("");
            if (ds.length() >= 10 && today.equals(LocalDate.parse(ds.substring(0, 10)))) {
                return day;
            }
        }
        return days.get(0);
    }

    private static JsonNode firstForecastPeriodWithTemperature(JsonNode day) {
        for (String key : FORECAST_PERIOD_KEYS) {
            JsonNode p = day.path(key);
            if (p.isMissingNode() || p.isNull()) {
                continue;
            }
            JsonNode t = p.path("temperature");
            if (!t.isMissingNode() && !t.isNull()) {
                return p;
            }
        }
        return null;
    }

    private static Double windSpeedMid(JsonNode speedRange) {
        if (speedRange == null || !speedRange.isArray() || speedRange.size() < 2) {
            return null;
        }
        return (speedRange.get(0).asDouble() + speedRange.get(1).asDouble()) / 2.0;
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

        Integer smnLid = null;
        if (loc.has("id") && !loc.get("id").isNull()) {
            smnLid = loc.get("id").asInt();
        }
        return new Observation(
                name, obsTime, temp, feelsLike, humidity, pressure, visibility, windDir, windSpeed, desc, smnLid);
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
