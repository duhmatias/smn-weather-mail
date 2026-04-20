package ar.gob.smn.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Fetches the latest “Topes Nubosos — Sectorizada Centro” frame: SMN JSON API + static image host.
 */
final class SmnTopesCentroImageFetcher {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Chrome-like UA; some SMN hosts are picky about clients. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";
    private static final String PAGE_REFERER = "https://www.smn.gob.ar/satelite";
    private static final String SATELLITE_LIST_URL = "https://ws1.smn.gob.ar/v1/images/satellite/TOP_C13_CEN_ALTA";
    private static final String STATIC_BASE = "https://estaticos.smn.gob.ar/vmsr/satelite/";

    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    SmnTopesCentroImageFetcher() {}

    /**
     * When {@code overrideImageUrl} is non-blank, downloads that URL. Otherwise resolves the latest filename from the SMN
     * API and downloads {@code estaticos.smn.gob.ar/.../FILENAME.jpg}.
     */
    Path downloadToTempFile(String overrideImageUrl) throws IOException, InterruptedException {
        String url = resolveImageUrl(overrideImageUrl);
        return downloadImage(url);
    }

    private String resolveImageUrl(String overrideImageUrl) throws IOException, InterruptedException {
        if (overrideImageUrl != null && !overrideImageUrl.isBlank()) {
            return overrideImageUrl.trim();
        }
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(SATELLITE_LIST_URL))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .GET()
                        .timeout(Duration.ofSeconds(30))
                        .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("SMN satellite list HTTP " + res.statusCode());
        }
        JsonNode root = JSON.readTree(res.body());
        JsonNode list = root.path("list");
        if (!list.isArray() || list.size() == 0) {
            throw new IOException("SMN satellite API returned no images for TOP_C13_CEN_ALTA.");
        }
        String file = list.get(0).asText("");
        if (file.isEmpty()) {
            throw new IOException("SMN satellite API returned an empty filename.");
        }
        return STATIC_BASE + file;
    }

    private Path downloadImage(String imageUrl) throws IOException, InterruptedException {
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(imageUrl))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        .header("Accept-Language", "es-AR,es;q=0.9")
                        .header("Referer", PAGE_REFERER)
                        .header("Origin", "https://www.smn.gob.ar")
                        .GET()
                        .timeout(Duration.ofSeconds(45))
                        .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IOException(
                    "SMN imagen HTTP "
                            + res.statusCode()
                            + ". Si persiste, configure smn.topes.centro.image.url / SMN_TOPES_CENTRO_IMAGE_URL con la URL"
                            + " directa de la imagen.");
        }
        byte[] body = res.body();
        if (body == null || body.length == 0) {
            throw new IOException("Respuesta vacía al descargar la imagen.");
        }
        if (looksLikeHtmlChallenge(body)) {
            throw new IOException(
                    "El servidor devolvió HTML (posible bloqueo Cloudflare). Configure smn.topes.centro.image.url con la"
                            + " URL directa de la imagen o reintente más tarde.");
        }
        if (body.length > 12_000_000) {
            throw new IOException("Imagen demasiado grande (" + body.length + " bytes).");
        }
        if (!looksLikeImageBytes(body)) {
            throw new IOException("La descarga no parece una imagen JPEG/PNG/GIF/WebP.");
        }
        String ext = guessExtension(imageUrl, res.headers().firstValue("Content-Type").orElse(""));
        Path tmp = Files.createTempFile("smn-topes-centro-", ext);
        Files.write(tmp, body);
        return tmp;
    }

    private static boolean looksLikeHtmlChallenge(byte[] body) {
        int n = Math.min(body.length, 512);
        String head = new String(body, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        String lower = head.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("<!doctype html")
                || lower.contains("just a moment")
                || lower.contains("cf-mitigated")
                || lower.contains("challenge-platform");
    }

    private static boolean looksLikeImageBytes(byte[] body) {
        if (body.length >= 3 && body[0] == (byte) 0xff && body[1] == (byte) 0xd8 && body[2] == (byte) 0xff) {
            return true;
        }
        if (body.length >= 8
                && body[0] == (byte) 0x89
                && body[1] == 'P'
                && body[2] == 'N'
                && body[3] == 'G') {
            return true;
        }
        if (body.length >= 6
                && body[0] == 'G'
                && body[1] == 'I'
                && body[2] == 'F'
                && body[3] == '8') {
            return true;
        }
        return body.length >= 12
                && body[0] == 'R'
                && body[1] == 'I'
                && body[2] == 'F'
                && body[3] == 'F'
                && body[8] == 'W'
                && body[9] == 'E'
                && body[10] == 'B'
                && body[11] == 'P';
    }

    private static String guessExtension(String url, String contentType) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        if (u.contains(".png")) {
            return ".png";
        }
        if (u.contains(".jpg") || u.contains(".jpeg")) {
            return ".jpg";
        }
        if (u.contains(".gif")) {
            return ".gif";
        }
        if (u.contains(".webp")) {
            return ".webp";
        }
        String ct = contentType.toLowerCase(java.util.Locale.ROOT);
        if (ct.contains("png")) {
            return ".png";
        }
        if (ct.contains("jpeg") || ct.contains("jpg")) {
            return ".jpg";
        }
        if (ct.contains("gif")) {
            return ".gif";
        }
        if (ct.contains("webp")) {
            return ".webp";
        }
        return ".jpg";
    }
}
