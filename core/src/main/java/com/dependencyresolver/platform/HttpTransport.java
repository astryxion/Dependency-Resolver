package com.dependencyresolver.platform;

import com.dependencyresolver.log.ResolverLog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public interface HttpTransport {
    Response send(Request request) throws IOException;

    record Request(URI uri, Map<String, String> headers, Duration timeout, Path downloadTo, Progress progress) {
        public Request {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }

        public static Request get(URI uri, Map<String, String> headers) {
            return new Request(uri, headers, Duration.ofSeconds(30), null, null);
        }
    }

    interface Progress {
        void bytes(long read, long total);
    }

    record Response(int status, byte[] body, Map<String, String> headers) {
        public Response {
            body = body == null ? new byte[0] : body;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    final class JdkHttpTransport implements HttpTransport {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        @Override
        public Response send(Request request) throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                    .timeout(request.timeout())
                    .GET();
            request.headers().forEach(builder::header);
            HttpRequest httpRequest = builder.build();
            try {
                if (request.downloadTo() == null) {
                    HttpResponse<byte[]> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
                    return new Response(response.statusCode(), response.body(), headerMap(response.headers()));
                }
                HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    try (InputStream ignored = response.body()) {
                        return new Response(response.statusCode(), new byte[0], headerMap(response.headers()));
                    }
                }
                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                Files.createDirectories(request.downloadTo().getParent());
                long read = 0L;
                try (InputStream input = response.body(); var output = Files.newOutputStream(request.downloadTo())) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, count);
                        read += count;
                        if (request.progress() != null) {
                            request.progress().bytes(read, total);
                        }
                    }
                }
                return new Response(response.statusCode(), new byte[0], headerMap(response.headers()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", exception);
            }
        }

        private static Map<String, String> headerMap(java.net.http.HttpHeaders headers) {
            Map<String, String> map = new LinkedHashMap<>();
            headers.map().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    map.put(key, values.get(0));
                }
            });
            return map;
        }
    }

    static String userAgent() {
        return "DependencyResolver/1.0 (Minecraft 1.20.1; Forge)";
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ResolverLog.error("Interrupted while waiting for a platform retry", exception);
        }
    }
}
