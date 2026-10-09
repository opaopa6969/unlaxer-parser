package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.*;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Real TLS and the same manifest/artifact exercise both complete CLI resolvers. */
public class HttpsPackageConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repository = Path.of("..").toAbsolutePath().normalize();
    private static final String TOKEN = "conformance-only-token";
    private record Run(int code, String output) {}
    private Run run(List<String> command, Path credentials) throws Exception {
        Path log = temporary.newFile().toPath();
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (credentials != null) builder.environment().put("UBNF_CREDENTIALS_FILE", credentials.toString());
        Process process = builder.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("HTTPS fixture process timed out"); }
        String output = Files.readString(log);
        assertFalse("credential leaked into process output", output.contains(TOKEN));
        return new Run(process.exitValue(), output);
    }
    private List<String> command(boolean nativeRust, Path manifest) {
        List<String> result = nativeRust ? new ArrayList<>(List.of(repository.resolve("rust/target/debug/unlaxer").toString()))
            : new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", System.getProperty("java.class.path"), "org.unlaxer.dsl.CodegenMain"));
        result.addAll(List.of("deps", "resolve", "--manifest", manifest.toString())); return result;
    }
    private Path manifest(String source) throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path manifest = directory.resolve("ubnf.json");
        Files.writeString(manifest, new Gson().toJson(Map.of("schemaVersion", 1, "dependencies", Map.of("std/layout", Map.of("version", "1.0.0", "source", source)))));
        return manifest;
    }
    private void openssl(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("openssl")); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("openssl.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(Files.readString(directory.resolve("openssl.log")), 0, process.exitValue());
    }
    @Test public void secureExplicitFetchAndPinnedOfflineGenerationMatch() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] HTTPS package conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        assertEquals(0, run(List.of("cargo", "build", "--locked", "--manifest-path", repository.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), null).code());
        Path tls = temporary.newFolder().toPath();
        openssl(tls, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.pem", "-days", "1", "-subj", "/CN=fixture-ca");
        openssl(tls, "req", "-newkey", "rsa:2048", "-nodes", "-keyout", "server.key", "-out", "server.csr", "-subj", "/CN=localhost");
        Files.writeString(tls.resolve("extensions"), "subjectAltName=DNS:localhost\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n");
        openssl(tls, "x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key", "-CAcreateserial", "-out", "server.pem", "-days", "1", "-extfile", "extensions");
        openssl(tls, "pkcs12", "-export", "-inkey", "server.key", "-in", "server.pem", "-certfile", "ca.pem", "-out", "server.p12", "-passout", "pass:fixture");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(tls.resolve("server.p12"))) { keys.load(stream, "fixture".toCharArray()); }
        KeyManagerFactory keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keyManager.init(keys, "fixture".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS"); context.init(keyManager.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        ExecutorService executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        AtomicInteger requests = new AtomicInteger(), auth = new AtomicInteger(), redirectRequests = new AtomicInteger();
        HttpServer redirect = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        redirect.createContext("/", exchange -> { redirectRequests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); }); redirect.start();
        byte[] artifact = Files.readAllBytes(repository.resolve("unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json"));
        JsonObject malicious = JsonParser.parseString(new String(artifact, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        malicious.add("dependencies", JsonParser.parseString("{\"local/data\":{\"version\":\"1.0.0\",\"source\":\"local:private.json\"}}"));
        byte[] remoteLocal = malicious.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            boolean authorized = ("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            if (authorized) auth.incrementAndGet();
            try {
                String path = exchange.getRequestURI().getPath();
                if (!authorized) { exchange.sendResponseHeaders(401, -1); }
                else if (path.equals("/redirect")) {
                    exchange.getResponseHeaders().add("Location", "http://localhost:" + redirect.getAddress().getPort() + "/"); exchange.sendResponseHeaders(302, -1);
                } else {
                    if (path.equals("/stall")) {
                        exchange.sendResponseHeaders(200, 1);
                        try { Thread.sleep(30000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                        exchange.getResponseBody().write(0); return;
                    }
                    byte[] bytes = path.equals("/oversize") ? new byte[8 * 1024 * 1024 + 1] : path.equals("/remote-local") ? remoteLocal : artifact;
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                }
            } catch (java.io.IOException expectedDisconnect) { /* bounded clients cancel oversize bodies */ }
            finally { exchange.close(); }
        }); server.start();
        String origin = "https://localhost:" + server.getAddress().getPort();
        Path credentials = tls.resolve("credentials.json"), noTrust = tls.resolve("untrusted.json"), wrongAuth = tls.resolve("wrong-auth.json");
        Files.writeString(credentials, new Gson().toJson(Map.of(
            origin, Map.of("bearerToken", TOKEN, "caCertificate", tls.resolve("ca.pem").toString()),
            origin.replace("localhost", "127.0.0.1"), Map.of("bearerToken", TOKEN, "caCertificate", tls.resolve("ca.pem").toString()))));
        Files.writeString(noTrust, new Gson().toJson(Map.of(origin, Map.of("bearerToken", TOKEN))));
        Files.writeString(wrongAuth, new Gson().toJson(Map.of(origin + "9", Map.of("bearerToken", TOKEN, "caCertificate", tls.resolve("ca.pem").toString()), origin, Map.of("caCertificate", tls.resolve("ca.pem").toString()))));
        List<String> report = new ArrayList<>(List.of("fixture\tjava\trust"));
        Path[] valid = {manifest(origin + "/artifact"), manifest(origin + "/artifact")};
        try {
            for (int language = 0; language < 2; language++) {
                Run result = run(command(language == 1, valid[language]), credentials); assertEquals(result.output(), 0, result.code());
                assertFalse(Files.readString(valid[language]).contains(TOKEN));
                String lock = Files.readString(valid[language].resolveSibling("ubnf.lock.json")); assertFalse(lock.contains(TOKEN));
                assertTrue(lock.contains(UBNFPackageResolver.sha256(artifact)));
            }
            assertEquals(Files.readString(valid[0].resolveSibling("ubnf.lock.json")), Files.readString(valid[1].resolveSibling("ubnf.lock.json")));
            assertEquals(2, auth.get()); report.add("authenticated-pinned-artifact\tpass\tpass");
            Map<String, String> refused = new LinkedHashMap<>();
            refused.put("http", "http://localhost:" + server.getAddress().getPort() + "/artifact");
            refused.put("userinfo", "https://user:password@localhost:" + server.getAddress().getPort() + "/artifact");
            refused.put("query", origin + "/artifact?credential=forbidden"); refused.put("fragment", origin + "/artifact#fragment");
            refused.put("redirect", origin + "/redirect"); refused.put("oversize", origin + "/oversize"); refused.put("remote-local", origin + "/remote-local");
            for (var fixture : refused.entrySet()) {
                for (boolean nativeRust : new boolean[]{false, true}) {
                    Path invalid = manifest(fixture.getValue()); Run result = run(command(nativeRust, invalid), credentials);
                    assertNotEquals(fixture.getKey(), 0, result.code()); assertFalse(Files.exists(invalid.resolveSibling("ubnf.lock.json")));
                    if (fixture.getKey().equals("remote-local")) assertTrue(result.output(), result.output().contains("remote artifact cannot use local dependencies"));
                }
                report.add(fixture.getKey() + "\trefused\trefused");
            }
            for (Path config : List.of(noTrust, wrongAuth)) {
                for (boolean nativeRust : new boolean[]{false, true}) assertNotEquals(0, run(command(nativeRust, manifest(origin + "/artifact")), config).code());
                report.add(config.equals(noTrust) ? "untrusted-tls\trefused\trefused" : "origin-auth-separation\trefused\trefused");
            }
            for (boolean nativeRust : new boolean[]{false, true}) {
                assertNotEquals("TLS hostname mismatch accepted", 0, run(command(nativeRust, manifest(origin.replace("localhost", "127.0.0.1") + "/artifact")), credentials).code());
                long started = System.nanoTime();
                assertNotEquals("stalled HTTPS body accepted", 0, run(command(nativeRust, manifest(origin + "/stall")), credentials).code());
                assertTrue("global body deadline exceeded", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(28));
            }
            report.add("tls-hostname	refused	refused"); report.add("body-global-deadline	refused	refused");
            assertEquals("redirect origin was contacted", 0, redirectRequests.get());
        } finally { server.stop(0); redirect.stop(0); executor.shutdownNow(); }
        int before = requests.get();
        for (int language = 0; language < 2; language++) {
            Path directory = valid[language].getParent(), grammar = directory.resolve("root.ubnf");
            Files.writeString(grammar, "grammar G { @import layout from 'pkg:std/layout' @ubnf: v2 @whitespace: layout.SPACES_AND_COMMENTS @root @mapping(Root,params=[value]) Root ::= 'a' @value 'b'; }");
            var expected = new RustBackend().generate(UBNFModuleLoader.load(grammar).grammars().get(0));
            Run result = run(List.of(repository.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar", grammar.toString(), "--output", directory.resolve("generated").toString()), tls.resolve("missing-credentials.json"));
            assertEquals(result.output(), 0, result.code());
            for (var file : expected) assertEquals(file.relativePath(), file.content(), Files.readString(directory.resolve("generated").resolve(file.relativePath())));
        }
        assertEquals(before, requests.get()); report.add("offline-generation-after-server-stop\tpass\tpass");
        Files.write(Path.of("target/rust-package-https.tsv"), report);
    }
}
