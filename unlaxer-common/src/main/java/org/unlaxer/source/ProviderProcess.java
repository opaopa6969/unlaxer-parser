package org.unlaxer.source;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.unlaxer.source.LanguageRegions.Operation;
import org.unlaxer.source.ProviderProtocol.*;

/** Explicitly registered analysis command. No shell, workspace discovery, or user-program execution. */
public final class ProviderProcess implements LanguageQueries.Provider {
    private final List<String> command;
    private final Identity identity;
    private final Set<Operation> operations;
    private final Duration timeout;
    public ProviderProcess(List<String> command, Identity identity, Set<Operation> operations, Duration timeout) {
        this.command = List.copyOf(command); this.identity = identity; this.operations = Set.copyOf(operations); this.timeout = timeout;
        if (command.isEmpty() || command.get(0).isEmpty() || timeout.toMillis() < 1 || timeout.toMillis() > 120000) { throw new IllegalArgumentException("invalid analysis command"); }
    }
    @Override public Set<Operation> capabilities() { return operations; }
    private static Response empty(Status status) { return new Response(status, Set.of(), List.of(), List.of()); }
    public Response invoke(Request request) {
        if (false == identity.equals(request.provider())) { throw new IllegalArgumentException("wrong provider identity"); }
        if (request.executeUserCode() || false == operations.contains(request.operation())) { return empty(Status.UNSUPPORTED); }
        Frame frame = ProviderProtocol.encode(request);
        Process process;
        try { process = new ProcessBuilder(command).start(); }
        catch (IOException error) { return empty(Status.UNAVAILABLE); }
        ExecutorService executor = Executors.newFixedThreadPool(3, task -> { Thread thread = new Thread(task); thread.setDaemon(true); return thread; });
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            Future<byte[]> output = executor.submit(() -> read(process.getInputStream()));
            Future<byte[]> errors = executor.submit(() -> read(process.getErrorStream()));
            Future<?> input = executor.submit(() -> {
                try (var stream = process.getOutputStream()) { stream.write(frame.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                return null;
            });
            input.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (false == process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) { return empty(Status.TIMEOUT); }
            byte[] stdout = output.get(remaining(deadline), TimeUnit.NANOSECONDS);
            errors.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) { return empty(Status.FAILED); }
            String wire = new String(stdout, java.nio.charset.StandardCharsets.UTF_8);
            if (false == java.util.Arrays.equals(stdout, wire.getBytes(java.nio.charset.StandardCharsets.UTF_8))) { throw new IllegalArgumentException("invalid response UTF-8"); }
            return ProviderProtocol.decode(request, frame, wire);
        } catch (TimeoutException error) { return empty(Status.TIMEOUT); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return empty(Status.FAILED); }
        catch (ExecutionException error) { return empty(Status.FAILED); }
        finally {
            process.descendants().forEach(child -> child.destroyForcibly());
            process.destroyForcibly(); executor.shutdownNow();
        }
    }
    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime(); if (remaining <= 0) { throw new TimeoutException(); } return remaining;
    }
    private static byte[] read(InputStream stream) throws IOException {
        try (stream) {
            byte[] result = stream.readNBytes(4 * 1024 * 1024 + 1);
            if (result.length > 4 * 1024 * 1024) { throw new IOException("provider response exceeds limit"); }
            return result;
        }
    }
    public EmbeddedLanguages.Grammar grammar(LanguageRegions.Language language, LanguageQueries.Project project, java.util.Map<String, String> parameters) {
        java.util.Map<String, String> options = java.util.Map.copyOf(parameters);
        return new EmbeddedLanguages.Grammar() {
            @Override public String name() { return language.grammar(); }
            @Override public EmbeddedLanguages.Parsed parse(String entry, DocumentSnapshot snapshot) {
                if (false == entry.equals(language.entry())) { return new EmbeddedLanguages.Parsed(snapshot, LanguageRegions.State.UNSUPPORTED, List.of()); }
                Response response = invoke(new Request(snapshot.uri(), identity, language, snapshot.uri(), snapshot, project, Operation.PARSE, 0, options, false));
                LanguageRegions.State state = response.status() == Status.DIAGNOSTICS ? LanguageRegions.State.FAILED : state(response.status());
                return new EmbeddedLanguages.Parsed(snapshot, state, List.of());
            }
        };
    }
    private static LanguageRegions.State state(Status status) {
        return switch (status) {
            case OK -> LanguageRegions.State.COMPLETE;
            case DIAGNOSTICS -> LanguageRegions.State.PARTIAL;
            case UNAVAILABLE -> LanguageRegions.State.UNAVAILABLE;
            case UNSUPPORTED -> LanguageRegions.State.UNSUPPORTED;
            case TIMEOUT -> LanguageRegions.State.TIMEOUT;
            case FAILED -> LanguageRegions.State.FAILED;
        };
    }
    @Override public LanguageQueries.Response query(LanguageQueries.Request request) {
        Response response = invoke(new Request(request.region().id(), identity, request.region().language(), request.region().id(), request.region().sourceMap().output(), request.project(), request.operation(), request.cursor(), request.parameters(), false));
        LanguageRegions.State state = state(response.status());
        var items = new java.util.ArrayList<>(response.items());
        if (request.operation() == Operation.VALIDATE) for (var diagnostic : response.diagnostics())
            items.add(new LanguageQueries.Item(diagnostic.code(), diagnostic.message(), diagnostic.locations(), List.of()));
        return new LanguageQueries.Response(request.region().sourceMap().output(), request.project().id(), request.project().version(), state, items);
    }
    @Override public LanguageQueries.DiagnosticResponse diagnostics(LanguageQueries.Request request) {
        if (request.operation() != Operation.VALIDATE) throw new IllegalArgumentException("diagnostics require VALIDATE");
        var response = invoke(new Request(request.region().id(), identity, request.region().language(), request.region().id(), request.region().sourceMap().output(), request.project(), Operation.VALIDATE, 0, request.parameters(), false));
        return new LanguageQueries.DiagnosticResponse(request.region().sourceMap().output(), request.project().id(), request.project().version(), state(response.status()), response.diagnostics());
    }

}
