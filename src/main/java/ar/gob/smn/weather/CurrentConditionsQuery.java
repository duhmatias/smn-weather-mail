package ar.gob.smn.weather;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tries SMN open-data {@code tiepre} text first, then georef + ws1 observation API for {@code /current} and
 * {@code java -Dcurrent=…} CLI runs.
 */
final class CurrentConditionsQuery {

    private static final Logger LOG = Logger.getLogger(CurrentConditionsQuery.class.getName());

    static final int MAX_GEOREF_CANDIDATES = 60;
    static final int MAX_CURRENT_MATCHES = 25;
    private static final int MS_BETWEEN_SMN_CURRENT_LOCATIONS = (int) Duration.ofSeconds(10).toMillis();

    /** Non-empty pieces: Telegram sends each as a message; CLI prints {@link #allText()}. */
    static final class Result {
        private final List<String> chunks;

        Result(List<String> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        List<String> chunks() {
            return chunks;
        }

        String allText() {
            return String.join("\n\n", chunks);
        }
    }

    /**
     * Same logic as Telegram {@code /current}: georef, optional reverse order for 2+ rows, SMN fetches, formatted
     * blocks and error lines.
     *
     * @param primeStations same ids as the conditions mail loop ({@code smn.location.ids} / {@code SMN_LOCATION_IDS});
     *     used to prime {@code /v1/weather/location/} before georef (can be empty to skip priming).
     */
    static Result run(
            String query, SmnClient smn, String reportHost, List<SmnClient.Station> primeStations)
            throws IOException, InterruptedException {
        LOG.info("current conditions: query=«" + query + "» reportHost=" + reportHost);
        try {
            Optional<String> tiepre = smn.fetchTieprePlainText();
            if (tiepre.isPresent()) {
                List<SmnClient.Observation> fromFile = TiepreOpenData.matchQuery(query, tiepre.get());
                if (!fromFile.isEmpty()) {
                    LOG.info("current conditions: tiepre open data, matches=" + fromFile.size());
                    return buildTiepreResult(query, fromFile, reportHost);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (IOException e) {
            LOG.log(Level.FINE, "tiepre open data unavailable, using georef/ws1", e);
        }
        // Same /v1/weather/location/{id} URLs as the conditions mail loop, then georef — not a different API.
        smn.primeWeatherApiForStations(primeStations);
        String intro =
                "Condiciones actuales — «" + query + "»\n(" + reportHost + ")\n\n";
        List<SmnClient.Station> candidates =
                smn.searchGeorefLocationsForCurrent(query, query, MAX_GEOREF_CANDIDATES);
        if (candidates.isEmpty()) {
            return new Result(
                    List.of(
                            intro
                                    + "No hay ubicaciones en el buscador SMN (mismo criterio que smn.gob.ar/pronostico) para «"
                                    + query
                                    + "».\n"));
        }
        List<SmnClient.Station> ordered = new ArrayList<>(candidates);
        if (ordered.size() > 1) {
            Collections.reverse(ordered);
        }
        candidates = ordered;

        boolean multi = candidates.size() > 1;
        String header =
                intro
                        + "Ubicaciones encontradas en SMN: "
                        + candidates.size()
                        + " (máx. "
                        + MAX_GEOREF_CANDIDATES
                        + ")\n"
                        + (multi
                                ? "(Condiciones: orden inverso al listado georef — para comparar id vs. orden de llamada.)\n"
                                : "");

        List<String> chunks = new ArrayList<>();
        if (multi) {
            chunks.add(header.trim());
        }

        StringBuilder singleBody = new StringBuilder();
        if (!multi) {
            singleBody.append(intro);
            singleBody.append("Ubicaciones encontradas en SMN: ")
                    .append(candidates.size())
                    .append(" (máx. ")
                    .append(MAX_GEOREF_CANDIDATES)
                    .append(")\n");
        }

        List<String> fetchErrors = new ArrayList<>();
        int matches = 0;
        int candidateIndex = 0;
        for (SmnClient.Station s : candidates) {
            if (matches >= MAX_CURRENT_MATCHES) {
                String tail =
                        "(Solo se muestran las primeras "
                                + MAX_CURRENT_MATCHES
                                + " con datos; acotá la búsqueda.)";
                if (multi) {
                    chunks.add(tail);
                } else {
                    singleBody.append("\n").append(tail).append('\n');
                }
                break;
            }
            if (candidateIndex > 0) {
                Thread.sleep(MS_BETWEEN_SMN_CURRENT_LOCATIONS);
            }
            try {
                SmnClient.Observation o = smn.fetchObservation(s);
                matches++;
                String block = MailBodyFormatter.formatCurrentConditionsPlain(o, s.locationId());
                if (multi) {
                    chunks.add(block);
                } else {
                    singleBody.append(block);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (IOException e) {
                fetchErrors.add(s.label() + " (id " + s.locationId() + "): " + e.getMessage());
                LOG.log(Level.FINE, "SMN weather for /current: " + s.locationId(), e);
            }
            candidateIndex++;
        }

        if (matches == 0 && fetchErrors.isEmpty()) {
            String line = "No se pudo obtener el tiempo actual para esas ubicaciones.\n";
            if (multi) {
                chunks.add(line.trim());
            } else {
                singleBody.append(line);
            }
        } else if (matches == 0) {
            String line =
                    "Ninguna ubicación devolvió datos de tiempo actual (p. ej. HTTP 401 en algunas estaciones).\n";
            if (multi) {
                chunks.add(line.trim());
            } else {
                singleBody.append(line);
            }
        }
        if (!fetchErrors.isEmpty()) {
            StringBuilder errBody = new StringBuilder("Avisos al consultar:\n");
            for (String err : fetchErrors) {
                errBody.append("• ").append(err).append('\n');
            }
            if (multi) {
                chunks.add(errBody.toString().trim());
            } else {
                singleBody.append("\n").append(errBody);
            }
        }
        if (!multi) {
            chunks.add(singleBody.toString().trim());
        }
        return new Result(chunks);
    }

    private static Result buildTiepreResult(
            String query, List<SmnClient.Observation> observations, String reportHost) {
        String intro =
                "Condiciones actuales — «"
                        + query
                        + "»\n(Fuente: SMN datos abiertos tiepre — ssl.smn.gob.ar)\n("
                        + reportHost
                        + ")\n\n";
        boolean multi = observations.size() > 1;
        List<String> chunks = new ArrayList<>();
        if (multi) {
            chunks.add(
                    (intro + "Estaciones coincidentes en tiepre: " + observations.size()).trim());
        }
        StringBuilder single = new StringBuilder();
        if (!multi) {
            single.append(intro).append("Estaciones coincidentes en tiepre: 1\n");
        }
        for (SmnClient.Observation o : observations) {
            int tagId = TiepreOpenData.tiepreStationToTagId(o.stationName());
            String block = MailBodyFormatter.formatCurrentConditionsPlain(o, tagId);
            if (multi) {
                chunks.add(block);
            } else {
                single.append(block);
            }
        }
        if (!multi) {
            chunks.add(single.toString().trim());
        }
        return new Result(chunks);
    }

    private CurrentConditionsQuery() {}
}
