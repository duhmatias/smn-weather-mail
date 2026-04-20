package ar.gob.smn.weather;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Multipart {@code sendPhoto} to Telegram Bot API. */
final class TelegramPhotoSender {

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    void sendPhoto(String botToken, String chatId, Path file, String filename, String caption)
            throws IOException, InterruptedException {
        byte[] fileBytes = Files.readAllBytes(file);
        String boundary = "----JavaFormBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipart(boundary, chatId, fileBytes, filename, contentTypeFor(filename), caption);
        URI uri = URI.create("https://api.telegram.org/bot" + botToken + "/sendPhoto");
        HttpRequest req =
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Telegram sendPhoto HTTP " + res.statusCode() + ": " + res.body());
        }
        String respBody = res.body();
        if (respBody == null || !respBody.contains("\"ok\":true")) {
            throw new IOException("Telegram sendPhoto API error: " + respBody);
        }
    }

    private static byte[] buildMultipart(
            String boundary, String chatId, byte[] fileBytes, String filename, String contentType, String caption)
            throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(fileBytes.length + 512);
        String rn = "\r\n";
        String dash = "--";

        b.write((dash + boundary + rn).getBytes(StandardCharsets.UTF_8));
        b.write(("Content-Disposition: form-data; name=\"chat_id\"" + rn + rn).getBytes(StandardCharsets.UTF_8));
        b.write(chatId.getBytes(StandardCharsets.UTF_8));
        b.write(rn.getBytes(StandardCharsets.UTF_8));

        if (caption != null && !caption.isBlank()) {
            b.write((dash + boundary + rn).getBytes(StandardCharsets.UTF_8));
            b.write(("Content-Disposition: form-data; name=\"caption\"" + rn + rn).getBytes(StandardCharsets.UTF_8));
            b.write(caption.trim().getBytes(StandardCharsets.UTF_8));
            b.write(rn.getBytes(StandardCharsets.UTF_8));
        }

        b.write((dash + boundary + rn).getBytes(StandardCharsets.UTF_8));
        b.write(
                ("Content-Disposition: form-data; name=\"photo\"; filename=\""
                                + asciiFilename(filename)
                                + "\""
                                + rn)
                        .getBytes(StandardCharsets.UTF_8));
        b.write(("Content-Type: " + contentType + rn + rn).getBytes(StandardCharsets.UTF_8));
        b.write(fileBytes);
        b.write(rn.getBytes(StandardCharsets.UTF_8));
        b.write((dash + boundary + dash + rn).getBytes(StandardCharsets.UTF_8));
        return b.toByteArray();
    }

    /** Telegram multipart filenames are safest as ASCII. */
    private static String asciiFilename(String filename) {
        String f = filename == null || filename.isBlank() ? "image.jpg" : filename.trim();
        if (f.chars().allMatch(c -> c >= 32 && c < 127 && c != '"' && c != '\\')) {
            return f;
        }
        return "topes_centro.jpg";
    }

    private static String contentTypeFor(String filename) {
        String f = filename.toLowerCase(java.util.Locale.ROOT);
        if (f.endsWith(".png")) {
            return "image/png";
        }
        if (f.endsWith(".gif")) {
            return "image/gif";
        }
        if (f.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
