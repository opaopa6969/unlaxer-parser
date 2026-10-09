package org.unlaxer.dsl.bootstrap;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.*;

/** Network I/O belongs only to explicit resolution; credentials never enter project metadata. */
final class UBNFPackageFetcher {
    private static final int LIMIT = 8 * 1024 * 1024;
    private static IllegalArgumentException error(String message) {
        return new IllegalArgumentException("E-PACKAGE: " + message);
    }
    static byte[] fetch(String source) {
        URI uri;
        try { uri = URI.create(source); } catch (Exception failure) { throw error("invalid HTTPS artifact URL"); }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || !host.matches("[A-Za-z0-9.-]+")
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535)
            throw error("HTTPS artifact URL must omit credentials, query and fragment");
        String origin = "https://" + host.toLowerCase(Locale.ROOT)
            + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
        JsonObject credentials = credentials(origin);
        HttpClient.Builder client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5));
        if (credentials.has("caCertificate")) {
            JsonElement field = credentials.get("caCertificate");
            if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) throw error("invalid artifact credentials");
            client.sslContext(trust(field.getAsString()));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET();
        if (credentials.has("bearerToken")) {
            JsonElement field = credentials.get("bearerToken");
            if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()
                    || !field.getAsString().matches("[A-Za-z0-9._~+/=-]+")) throw error("invalid artifact credentials");
            request.header("Authorization", "Bearer " + field.getAsString());
        }
        CompletableFuture<HttpResponse<byte[]>> future = client.build().sendAsync(request.build(), ignored -> new BoundedBody());
        try {
            HttpResponse<byte[]> response = future.get(20, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw error("HTTPS artifact requires status 200; redirects are forbidden");
            return response.body();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); future.cancel(true); throw error("HTTPS artifact retrieval interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            future.cancel(true); throw error("HTTPS artifact retrieval failed (TLS, timeout or size limit)");
        }
    }
    private static JsonObject credentials(String origin) {
        String override = System.getenv("UBNF_CREDENTIALS_FILE");
        Path path = override == null ? Path.of(System.getProperty("user.home"), ".config/unlaxer/credentials.json") : Path.of(override);
        if (!Files.exists(path)) {
            if (override != null) throw error("artifact credentials file is missing");
            return new JsonObject();
        }
        try {
            if (Files.size(path) > 65536) throw error("artifact credentials file exceeds limit");
            JsonElement document = new GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(Files.readString(path), JsonElement.class);
            if (document == null || !document.isJsonObject()) throw error("invalid artifact credentials");
            JsonElement entry = document.getAsJsonObject().get(origin);
            if (entry == null) return new JsonObject();
            if (!entry.isJsonObject()) throw error("invalid artifact credentials");
            return entry.getAsJsonObject();
        } catch (IOException | JsonParseException failure) { throw error("cannot read artifact credentials"); }
    }
    private static SSLContext trust(String path) {
        try (InputStream stream = Files.newInputStream(Path.of(path))) {
            if (Files.size(Path.of(path)) > 65536) throw error("artifact CA file exceeds limit");
            KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType()); roots.load(null);
            int index = 0;
            for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(stream))
                roots.setCertificateEntry("artifact-ca-" + index++, certificate);
            if (index == 0) throw error("invalid artifact CA certificate");
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(roots);
            SSLContext context = SSLContext.getInstance("TLS"); context.init(null, trust.getTrustManagers(), null); return context;
        } catch (Exception failure) { throw error("cannot read artifact CA certificate"); }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long)bytes.size() + buffer.remaining() > LIMIT) {
                    subscription.cancel(); result.completeExceptionally(error("artifact exceeds 8 MiB")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(error("HTTPS artifact retrieval failed")); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
