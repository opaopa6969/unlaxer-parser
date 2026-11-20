package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import javax.tools.*;

/** Real generated stdio launchers must complete disconnect before releasing their workers. */
public class DapLauncherLifecycleConformanceTest {
    @Before
    public void requiresJava21() {
        org.junit.Assume.assumeTrue(
                "generated DAP lifecycle verification requires JDK21",
                Runtime.version().feature() >= 21);
    }

    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final String GRAMMAR =
            "grammar Lifecycle { @package: wire.lifecycle @whitespace: none @root @mapping(Value)"
                    + " Root ::= 'x'; }";
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    public void generatedJavaAndRustCompleteTheirOwnedTransports() throws Exception {
        var grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
        List<String> java = javaCommand("wire.lifecycle.LifecycleDapLauncher", null);
        exercise(java, "java");
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println(
                    "[assumption] native DAP lifecycle requires -DrustConformance=true; Java"
                            + " transport oracle ran");
            return;
        }
        Path project = temporary.newFolder("native").toPath();
        Path modules = project.resolve("src/generated");
        Files.createDirectories(modules);
        for (var file :
                new org.unlaxer.dsl.codegen.rust.RustBackend()
                        .generateWithProtocols(grammar, false, true)) {
            Files.writeString(modules.resolve(file.relativePath()), file.content());
        }
        Files.writeString(
                project.resolve("Cargo.toml"),
                "[package]\n"
                        + "name=\"dap-lifecycle-fixture\"\n"
                        + "version=\"0.1.0\"\n"
                        + "edition=\"2021\"\n"
                        + "[dependencies]\n"
                        + "unlaxer-runtime={path=\""
                        + ROOT.resolve("rust/unlaxer-runtime")
                        + "\"}\nunlaxer-dap={path=\""
                        + ROOT.resolve("rust/unlaxer-dap")
                        + "\"}\n");
        Files.writeString(
                project.resolve("src/main.rs"),
                "mod generated; fn main()->std::io::Result<()> {generated::dap::serve_stdio()}\n");
        Path buildLog = temporary.newFile("cargo.log").toPath();
        Path target = ROOT.resolve("rust/target");
        var builder =
                org.unlaxer.dsl.TestProcesses.builder(
                                "cargo",
                                "build",
                                "--offline",
                                "--quiet",
                                "--manifest-path",
                                project.resolve("Cargo.toml").toString(),
                                "--target-dir",
                                target.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(buildLog.toFile());
        var process = builder.start();
        try {
            assertTrue("native build timeout", process.waitFor(120, TimeUnit.SECONDS));
            assertEquals(Files.readString(buildLog), 0, process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        exercise(List.of(target.resolve("debug/dap-lifecycle-fixture").toString()), "rust");
        Files.createDirectories(Path.of("target"));
        Files.writeString(
                Path.of("target/dap-launcher-lifecycle.tsv"),
                "backend\tmode\tdisconnect_response\tclient_eof_required\tprocess_exit\n"
                        + "java\tast/token\tsuccess\tfalse\t0\n"
                        + "rust\tast/token\tsuccess\tfalse\t0\n");
    }

    @Test
    public void librarySessionsPreserveCallerStreamsIncludingDelayedDisconnect() throws Exception {
        String harness =
                """
                package wire.lifecycle;
                import java.io.*;
                import java.nio.charset.StandardCharsets;
                import java.util.concurrent.*;
                public final class Caller {
                    static class Input extends ByteArrayInputStream {
                        boolean closed;
                        Input(byte[] bytes) { super(bytes); }
                        @Override public void close() { closed = true; }
                    }
                    static class Output extends ByteArrayOutputStream {
                        boolean closed;
                        @Override public void close() { closed = true; }
                    }
                    static byte[] requests() throws Exception {
                        var bytes = new ByteArrayOutputStream();
                        String body = "{\\"seq\\":1,\\"type\\":\\"request\\",\\"command\\":\\"disconnect\\",\\"arguments\\":{}}";
                        bytes.write(("Content-Length: " + body.length() + "\\r\\n\\r\\n" + body + "TAIL").getBytes(StandardCharsets.UTF_8));
                        return bytes.toByteArray();
                    }
                    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
                    public static void main(String[] args) throws Exception {
                        var launcher = new LifecycleDapLauncher() {
                            @Override protected LifecycleDebugAdapter createAdapter() {
                                return new LifecycleDebugAdapter() {
                                    @Override public CompletableFuture<Void> disconnect(org.eclipse.lsp4j.debug.DisconnectArguments args) {
                                        return CompletableFuture.runAsync(() -> super.disconnect(args).join(), CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS));
                                    }
                                };
                            }
                        };
                        for (int i = 0; i < 2; i++) {
                            var input = new Input(requests()); var output = new Output();
                            launcher.launch(input, output).get(5, TimeUnit.SECONDS);
                            check(!input.closed && !output.closed);
                            check(new String(input.readAllBytes(), StandardCharsets.UTF_8).equals("TAIL"));
                            check(output.toString(StandardCharsets.UTF_8).contains("\\"success\\":true"));
                            output.write('!');
                        }
                        var originalInput = System.in; var originalOutput = System.out;
                        var input = new Input(requests()); var output = new Output(); var standardOutput = new PrintStream(output);
                        try {
                            System.setIn(input); System.setOut(standardOutput);
                            launcher.launchStdio();
                            check(!input.closed && !output.closed);
                            check(new String(input.readAllBytes(), StandardCharsets.UTF_8).equals("TAIL"));
                        } finally { System.setIn(originalInput); System.setOut(originalOutput); }
                        System.out.print("caller survived");
                    }
                }
                """;
        List<String> command = javaCommand("wire.lifecycle.Caller", harness);
        Path errors = temporary.newFile("caller-stderr.log").toPath();
        var process = org.unlaxer.dsl.TestProcesses.builder(command).redirectError(errors.toFile()).start();
        try {
            assertTrue("library session worker leak", process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(Files.readString(errors), 0, process.exitValue());
            assertEquals(
                    "caller survived",
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("", Files.readString(errors));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    public void libraryTransportFailuresAndStalledDisconnectAreBounded() throws Exception {
        String harness =
                """
                package wire.lifecycle;
                import java.io.*;
                import java.nio.charset.StandardCharsets;
                import java.util.concurrent.*;
                public final class Failures {
                    static class Input extends ByteArrayInputStream {
                        boolean closed;
                        Input(byte[] bytes) { super(bytes); }
                        @Override public void close() { closed = true; }
                    }
                    static class Output extends ByteArrayOutputStream {
                        boolean closed;
                        @Override public void close() { closed = true; }
                    }
                    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
                    static Input request() {
                        String body = "{\\"seq\\":1,\\"type\\":\\"request\\",\\"command\\":\\"disconnect\\",\\"arguments\\":{}}";
                        return new Input(("Content-Length: " + body.length() + "\\r\\n\\r\\n" + body).getBytes(StandardCharsets.UTF_8));
                    }
                    static void failure(CompletableFuture<Void> completion) throws Exception {
                        try { completion.get(8, TimeUnit.SECONDS); throw new AssertionError("failure lost"); }
                        catch (ExecutionException expected) { check(expected.getCause() != null); }
                    }
                    public static void main(String[] args) throws Exception {
                        var input = request();
                        var brokenOutput = new OutputStream() {
                            @Override public void write(int ignored) throws IOException { throw new IOException("fixture write failure"); }
                            @Override public void close() { throw new AssertionError("borrowed output closed"); }
                        };
                        failure(new LifecycleDapLauncher().launch(input, brokenOutput));
                        check(!input.closed);
                        var output = new Output();
                        var brokenInput = new InputStream() {
                            @Override public int read() throws IOException { throw new IOException("fixture read failure"); }
                            @Override public void close() { throw new AssertionError("borrowed input closed"); }
                        };
                        failure(new LifecycleDapLauncher().launch(brokenInput, output));
                        check(!output.closed);
                        var stalled = new LifecycleDapLauncher() {
                            @Override protected LifecycleDebugAdapter createAdapter() {
                                return new LifecycleDebugAdapter() {
                                    @Override public CompletableFuture<Void> disconnect(org.eclipse.lsp4j.debug.DisconnectArguments args) { return new CompletableFuture<>(); }
                                };
                            }
                        };
                        input = request(); output = new Output();
                        failure(stalled.launch(input, output));
                        check(!input.closed && !output.closed);
                        System.out.print("caller recovered");
                    }
                }
                """;
        var process =
                org.unlaxer.dsl.TestProcesses.builder(javaCommand("wire.lifecycle.Failures", harness))
                        .redirectError(temporary.newFile("failure-stderr.log"))
                        .start();
        try {
            assertTrue(
                    "transport failure must release owned workers",
                    process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals(
                    "caller recovered",
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    public void delayedResponseWriteFailureDoesNotRetainHalfOpenReader() throws Exception {
        String harness =
                """
                package wire.lifecycle;
                import java.io.*;
                import java.nio.charset.StandardCharsets;
                import java.util.concurrent.*;
                public final class HalfOpen {
                    public static void main(String[] args) throws Exception {
                        var input = new PipedInputStream(); var peer = new PipedOutputStream(input);
                        var launcher = new LifecycleDapLauncher() {
                            @Override protected LifecycleDebugAdapter createAdapter() {
                                return new LifecycleDebugAdapter() {
                                    @Override public CompletableFuture<org.eclipse.lsp4j.debug.Capabilities> initialize(org.eclipse.lsp4j.debug.InitializeRequestArguments args) {
                                        return CompletableFuture.supplyAsync(() -> super.initialize(args).join(), CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));
                                    }
                                };
                            }
                        };
                        var broken = new OutputStream() {
                            @Override public void write(int value) throws IOException { throw new IOException("half-open response write failure"); }
                        };
                        var completion = launcher.launch(input, broken);
                        String body = "{\\"seq\\":1,\\"type\\":\\"request\\",\\"command\\":\\"initialize\\",\\"arguments\\":{}}";
                        peer.write(("Content-Length: " + body.length() + "\\r\\n\\r\\n" + body).getBytes(StandardCharsets.UTF_8)); peer.flush();
                        try {
                            try { completion.get(3, TimeUnit.SECONDS); throw new AssertionError("write failure lost"); }
                            catch (ExecutionException expected) { }
                            System.out.print("half-open failure released");
                        } finally { peer.close(); input.close(); }
                    }
                }
                """;
        Path errors = temporary.newFile("half-open-stderr.log").toPath();
        var process =
                org.unlaxer.dsl.TestProcesses.builder(javaCommand("wire.lifecycle.HalfOpen", harness))
                        .redirectError(errors.toFile())
                        .start();
        try {
            assertTrue("half-open failure worker leak", process.waitFor(8, TimeUnit.SECONDS));
            assertEquals(Files.readString(errors), 0, process.exitValue());
            assertEquals(
                    "half-open failure released",
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    public void asynchronousReverseRequestRemainsFullDuplex() throws Exception {
        String harness =
                """
                package wire.lifecycle;
                import java.util.concurrent.CompletableFuture;
                public final class Reverse {
                    public static void main(String[] args) throws Exception {
                        new LifecycleDapLauncher() {
                            @Override protected LifecycleDebugAdapter createAdapter() {
                                return new LifecycleDebugAdapter() {
                                    @Override public CompletableFuture<org.eclipse.lsp4j.debug.Capabilities> initialize(org.eclipse.lsp4j.debug.InitializeRequestArguments args) {
                                        var terminal = new org.eclipse.lsp4j.debug.RunInTerminalRequestArguments();
                                        terminal.setCwd("."); terminal.setArgs(new String[] {"fixture"});
                                        return client.runInTerminal(terminal).thenApply(response -> {
                                            if (response.getProcessId() != 42) throw new AssertionError("reverse response lost");
                                            return super.initialize(args).join();
                                        });
                                    }
                                };
                            }
                        }.launchStdio();
                    }
                }
                """;
        Path errors = temporary.newFile("reverse-stderr.log").toPath();
        try (var wire =
                new Wire(
                        org.unlaxer.dsl.TestProcesses.builder(javaCommand("wire.lifecycle.Reverse", harness))
                                .redirectError(errors.toFile())
                                .start())) {
            int initialize = wire.sendRequest("initialize", "{}");
            var reverse = wire.read();
            assertEquals("request", reverse.get("type").getAsString());
            assertEquals("runInTerminal", reverse.get("command").getAsString());
            var response = new JsonObject();
            response.addProperty("seq", ++wire.sequence);
            response.addProperty("type", "response");
            response.addProperty("request_seq", reverse.get("seq").getAsInt());
            response.addProperty("command", "runInTerminal");
            response.addProperty("success", true);
            var body = new JsonObject();
            body.addProperty("processId", 42);
            response.add("body", body);
            wire.send(response.toString());
            var initialized = wire.read();
            assertEquals(initialize, initialized.get("request_seq").getAsInt());
            assertTrue(initialized.toString(), initialized.get("success").getAsBoolean());
            assertTrue(wire.request("disconnect", "{}").get("success").getAsBoolean());
            assertTrue(
                    "reverse request left owned threads",
                    wire.process.waitFor(3, TimeUnit.SECONDS));
            assertEquals(Files.readString(errors), 0, wire.process.exitValue());
            assertEquals("", Files.readString(errors));
        }
    }

    @Test
    public void ownedStdioMainReportsBrokenOutputAndExitsWithOpenInput() throws Exception {
        Path errors = temporary.newFile("broken-stdout-stderr.log").toPath();
        try (var wire =
                new Wire(
                        org.unlaxer.dsl.TestProcesses.builder(javaCommand("wire.lifecycle.LifecycleDapLauncher", null))
                                .redirectError(errors.toFile())
                                .start())) {
            wire.input.close();
            wire.sendRequest("initialize", "{}");
            assertTrue(
                    "owned stdio main must exit when the client closes stdout, even with stdin"
                        + " open",
                    wire.process.waitFor(3, TimeUnit.SECONDS));
            assertEquals(Files.readString(errors), 1, wire.process.exitValue());
            assertFalse(Files.readString(errors).isEmpty());
        }
    }

    private List<String> javaCommand(String main, String harness) throws Exception {
        var grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
        var units = new ArrayList<JavaFileObject>();
        for (var generator :
                List.of(
                        new ParserGenerator(),
                        new ASTGenerator(),
                        new MapperGenerator(),
                        new DAPGenerator(),
                        new DAPLauncherGenerator())) {
            var generated = generator.generate(grammar);
            units.add(
                    source(
                            generated.packageName() + "." + generated.className(),
                            generated.source()));
        }
        if (harness != null) units.add(source(main, harness));
        Path classes = temporary.newFolder().toPath();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean compiled =
                    compiler.getTask(
                                    null,
                                    manager,
                                    diagnostics,
                                    List.of(
                                            "--release",
                                            "21",
                                            "-classpath",
                                            System.getProperty("java.class.path"),
                                            "-d",
                                            classes.toString()),
                                    null,
                                    units)
                            .call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                classes + File.pathSeparator + System.getProperty("java.class.path"),
                main);
    }

    private void exercise(List<String> command, String backend) throws Exception {
        Path program = temporary.newFile(backend + "-program.txt").toPath();
        Files.writeString(program, "x");
        for (String mode : List.of("uninitialized", "ast", "token")) {
            Path errors = temporary.newFile().toPath();
            try (var wire =
                    new Wire(org.unlaxer.dsl.TestProcesses.builder(command).redirectError(errors.toFile()).start())) {
                if (!mode.equals("uninitialized")) {
                    assertTrue(
                            wire.request("initialize", "{\"adapterID\":\"lifecycle\"}")
                                    .get("success")
                                    .getAsBoolean());
                    assertTrue(
                            wire.request(
                                            "launch",
                                            new com.google.gson.Gson()
                                                    .toJson(
                                                            Map.of(
                                                                    "program",
                                                                    program.toString(),
                                                                    "steppingMode",
                                                                    mode,
                                                                    "runtimeMode",
                                                                    mode,
                                                                    "stopOnEntry",
                                                                    true)))
                                    .get("success")
                                    .getAsBoolean());
                    assertTrue(
                            wire.request("configurationDone", "{}").get("success").getAsBoolean());
                    assertTrue(
                            wire.request("stackTrace", "{\"threadId\":1}")
                                            .get("body")
                                            .getAsJsonObject()
                                            .getAsJsonArray("stackFrames")
                                            .size()
                                    > 0);
                }
                assertTrue(wire.request("disconnect", "{}").get("success").getAsBoolean());
                assertTrue(
                        backend
                                + "/"
                                + mode
                                + " must exit after flushed disconnect without client EOF",
                        wire.process.waitFor(3, TimeUnit.SECONDS));
                assertEquals(0, wire.process.exitValue());
                assertEquals(-1, wire.input.read());
                assertEquals("", Files.readString(errors));
            }
        }
        for (boolean initialize : List.of(false, true)) {
            Path errors = temporary.newFile().toPath();
            try (var wire =
                    new Wire(org.unlaxer.dsl.TestProcesses.builder(command).redirectError(errors.toFile()).start())) {
                if (initialize)
                    assertTrue(
                            wire.request("initialize", "{\"adapterID\":\"eof\"}")
                                    .get("success")
                                    .getAsBoolean());
                wire.output.close();
                assertTrue(
                        backend + " peer EOF must release workers",
                        wire.process.waitFor(3, TimeUnit.SECONDS));
                assertEquals(0, wire.process.exitValue());
                assertEquals(-1, wire.input.read());
                assertEquals("", Files.readString(errors));
            }
        }
        if (backend.equals("rust")) {
            Path errors = temporary.newFile().toPath();
            try (var wire =
                    new Wire(org.unlaxer.dsl.TestProcesses.builder(command).redirectError(errors.toFile()).start())) {
                wire.input.close();
                wire.sendRequest("initialize", "{}");
                assertTrue(
                        "native owned stdio must stop on broken output",
                        wire.process.waitFor(3, TimeUnit.SECONDS));
                assertEquals(Files.readString(errors), 1, wire.process.exitValue());
            }
        }
    }

    private static JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(
                java.net.URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignored) {
                return text;
            }
        };
    }

    private static final class Wire implements AutoCloseable {
        final Process process;
        final InputStream input;
        final OutputStream output;
        final ExecutorService reader = Executors.newSingleThreadExecutor();
        int sequence;

        Wire(Process process) {
            this.process = process;
            input = new BufferedInputStream(process.getInputStream());
            output = process.getOutputStream();
        }

        JsonObject request(String command, String arguments) throws Exception {
            int id = sendRequest(command, arguments);
            while (true) {
                var message = read();
                if (message.get("type").getAsString().equals("response")
                        && message.get("request_seq").getAsInt() == id) return message;
            }
        }

        int sendRequest(String command, String arguments) throws IOException {
            int id = ++sequence;
            send(
                    "{\"seq\":"
                            + id
                            + ",\"type\":\"request\",\"command\":\""
                            + command
                            + "\",\"arguments\":"
                            + arguments
                            + "}");
            return id;
        }

        void send(String json) throws IOException {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            output.write(
                    ("Content-Length: " + body.length + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }

        JsonObject read() throws Exception {
            return reader.submit(
                            () -> {
                                int length = -1;
                                var line = new StringBuilder();
                                while (true) {
                                    int b = input.read();
                                    if (b < 0) throw new EOFException();
                                    line.append((char) b);
                                    if (b == '\n') {
                                        String header = line.toString();
                                        line.setLength(0);
                                        if (header.equals("\r\n")) break;
                                        if (header.startsWith("Content-Length:"))
                                            length = Integer.parseInt(header.substring(15).trim());
                                    }
                                }
                                if (length < 0 || length > 4 * 1024 * 1024)
                                    throw new IOException("invalid frame length");
                                byte[] bytes = input.readNBytes(length);
                                if (bytes.length != length) throw new EOFException();
                                return JsonParser.parseString(
                                                new String(bytes, StandardCharsets.UTF_8))
                                        .getAsJsonObject();
                            })
                    .get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            output.close();
            if (process.isAlive()) process.destroyForcibly();
            reader.shutdownNow();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }
}
