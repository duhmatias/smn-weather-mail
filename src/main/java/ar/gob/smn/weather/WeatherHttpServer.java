package ar.gob.smn.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight JSON web API using JDK's built-in {@link HttpServer}. Runs alongside the main polling loop on a
 * configurable port. Endpoints:
 * <ul>
 *   <li>{@code GET /api/current?q=<location>} — current conditions by location search (same logic as /current)</li>
 *   <li>{@code GET /api/stations} — current conditions for all configured SMN stations</li>
 *   <li>{@code GET /api/health} — simple health check</li>
 * </ul>
 */
final class WeatherHttpServer {

    private static final Logger LOG = Logger.getLogger(WeatherHttpServer.class.getName());
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final int port;
    private final SmnClient smn;
    private final List<SmnClient.Station> configuredStations;
    private final String reportHost;
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    WeatherHttpServer(int port, SmnClient smn, List<SmnClient.Station> configuredStations, String reportHost) {
        this.port = port;
        this.smn = smn;
        this.configuredStations = configuredStations;
        this.reportHost = reportHost;
    }

    void start() throws IOException {
        // Bind to all interfaces (IPv4 + IPv6) — required for alwaysdata services (IPv6 :: needed for external access)
        server = HttpServer.create(new InetSocketAddress(port), 0);
        ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r, "http-api");
            t.setDaemon(true);
            return t;
        };
        server.setExecutor(Executors.newFixedThreadPool(2, daemonFactory));
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/stations", this::handleStations);
        server.createContext("/api/current", this::handleCurrent);
        server.createContext("/api/forecast", this::handleForecast);
        server.start();
        LOG.info("HTTP JSON API started on port " + port
                + " (endpoints: GET /api/current?q=…, GET /api/stations, GET /api/health)");
    }

    void stop() {
        if (server != null) {
            server.stop(1);
        }
    }

    // ── /api/health ──────────────────────────────────────────────────────────────

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!checkGet(exchange)) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "up");
        body.put("version", AppVersion.implementationVersion());
        sendJson(exchange, 200, body);
    }

    // ── /api/stations ────────────────────────────────────────────────────────────

    private void handleStations(HttpExchange exchange) throws IOException {
        if (!checkGet(exchange)) return;
        List<Map<String, Object>> stationResults = new ArrayList<>();
        for (SmnClient.Station station : configuredStations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("locationId", station.locationId());
            entry.put("label", station.label());
            try {
                SmnClient.Observation obs = smn.fetchObservation(station);
                entry.put("observation", observationToMap(obs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                entry.put("error", "Interrupted while fetching observation");
            } catch (IOException e) {
                entry.put("error", e.getMessage());
            }
            stationResults.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stations", stationResults);
        body.put("count", stationResults.size());
        if (reportHost != null) {
            body.put("reportHost", reportHost);
        }
        sendJson(exchange, 200, body);
    }

    // ── /api/current?q=<query> ───────────────────────────────────────────────────

    private void handleCurrent(HttpExchange exchange) throws IOException {
        if (!checkGet(exchange)) return;
        String query = extractQueryParam(exchange, "q");
        if (query == null || query.isBlank()) {
            sendJsonError(exchange, 400, "Missing required query parameter: q");
            return;
        }

        List<SmnClient.Observation> observations = new ArrayList<>();
        String source = "unknown";

        try {
            // Try tiepre open-data first (same priority as CurrentConditionsQuery)
            Optional<String> tiepre = smn.fetchTieprePlainText();
            if (tiepre.isPresent()) {
                List<SmnClient.Observation> fromTiepre = TiepreOpenData.matchQuery(query, tiepre.get());
                if (!fromTiepre.isEmpty()) {
                    observations.addAll(fromTiepre);
                    source = "tiepre";
                }
            }

            // Fallback to georef + ws1 API
            if (observations.isEmpty()) {
                List<SmnClient.Station> candidates =
                        smn.searchGeorefLocationsForCurrent(query, query, CurrentConditionsQuery.MAX_GEOREF_CANDIDATES);
                List<String> errors = new ArrayList<>();
                for (SmnClient.Station s : candidates) {
                    if (observations.size() >= CurrentConditionsQuery.MAX_CURRENT_MATCHES) break;
                    try {
                        observations.add(smn.fetchObservation(s));
                    } catch (IOException e) {
                        errors.add(s.label() + " (id " + s.locationId() + "): " + e.getMessage());
                    }
                }
                source = "georef";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJsonError(exchange, 500, "Request interrupted");
            return;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error fetching weather for query: " + query, e);
            sendJsonError(exchange, 502, "Error fetching data from SMN: " + e.getMessage());
            return;
        }

        List<Map<String, Object>> obsList = new ArrayList<>();
        for (SmnClient.Observation obs : observations) {
            obsList.add(observationToMap(obs));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("source", source);
        body.put("observations", obsList);
        body.put("count", obsList.size());
        if (reportHost != null) {
            body.put("reportHost", reportHost);
        }
        sendJson(exchange, 200, body);
    }

    // ── /api/forecast ────────────────────────────────────────────────────────────

    private void handleForecast(HttpExchange exchange) throws IOException {
        if (!checkGet(exchange)) return;

        // Use the first configured station (CABA) for forecast
        if (configuredStations.isEmpty()) {
            sendJsonError(exchange, 500, "No stations configured");
            return;
        }

        SmnClient.Station station = configuredStations.get(0);

        try {
            SmnClient.ForecastPayload forecast = smn.fetchForecastPayload(station);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("locationId", station.locationId());
            body.put("label", station.label());
            body.put("updated", forecast.updated());
            body.put("plainText", forecast.plainText());
            body.put("telegramHtml", forecast.telegramHtml());

            if (reportHost != null) {
                body.put("reportHost", reportHost);
            }

            sendJson(exchange, 200, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJsonError(exchange, 500, "Request interrupted");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error fetching forecast", e);
            sendJsonError(exchange, 502, "Error fetching forecast from SMN: " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private Map<String, Object> observationToMap(SmnClient.Observation obs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stationName", obs.stationName());
        map.put("observationTime", obs.observationTime() != null ? ISO.format(obs.observationTime()) : null);
        map.put("temperature", obs.temperature());
        map.put("feelsLike", obs.feelsLike());
        map.put("humidity", obs.humidity());
        map.put("pressure", obs.pressure());
        map.put("visibilityKm", obs.visibilityKm());
        map.put("windDirection", obs.windDirection());
        map.put("windSpeed", obs.windSpeed());
        map.put("description", obs.description());
        map.put("smnLocationId", obs.smnLocationId());
        return map;
    }

    private boolean checkGet(HttpExchange exchange) throws IOException {
        // CORS preflight
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return false;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonError(exchange, 405, "Method not allowed. Use GET.");
            return false;
        }
        return true;
    }

    private String extractQueryParam(HttpExchange exchange, String name) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null) return null;
        for (String param : rawQuery.split("&")) {
            int eq = param.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(param.substring(0, eq), StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", JSON_CONTENT_TYPE);
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", message);
        error.put("status", status);
        sendJson(exchange, status, error);
    }
}
