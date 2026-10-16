package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;

/** Generates a DAP launcher whose owned workers stop after the disconnect reply is flushed. */
public class DAPLauncherGenerator implements CodeGenerator {
    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        org.unlaxer.dsl.bootstrap.RegexRetirement.requireValid(grammar);
        String packageName = grammar.settings().stream()
            .filter(setting -> "package".equals(setting.key()))
            .map(setting -> setting.value() instanceof StringSettingValue value ? value.value() : "")
            .findFirst().orElse("generated");
        String launcherClass = grammar.name() + "DapLauncher";
        String adapterClass = grammar.name() + "DebugAdapter";
        String body = """
            package %s;

            import java.io.FilterInputStream;
            import java.io.IOException;
            import java.io.InputStream;
            import java.io.OutputStream;
            import java.util.Objects;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.ExecutionException;
            import java.util.concurrent.Executors;
            import java.util.concurrent.TimeUnit;
            import java.util.concurrent.atomic.AtomicBoolean;
            import java.util.concurrent.atomic.AtomicReference;
            import org.eclipse.lsp4j.debug.launch.DSPLauncher;
            import org.eclipse.lsp4j.jsonrpc.JsonRpcException;
            import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugResponseMessage;
            import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;

            %spublic class %s {
                protected %s createAdapter() {
                    return new %s() {};
                }

                public static void main(String[] args) throws IOException {
                    int status = 0;
                    try { new %s().launchStdio(); }
                    catch (IOException error) { error.printStackTrace(System.err); status = 1; }
                    System.exit(status);
                }

                /** Starts a session without exiting the caller's process. */
                public void launch() throws IOException {
                    launch(System.in, System.out);
                }

                /** Waits for disconnect or peer EOF; never closes System.in/out or calls System.exit. */
                public void launchStdio() throws IOException {
                    try { launch(System.in, System.out).get(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    catch (ExecutionException error) { throw new IOException("DAP transport failed", error.getCause()); }
                }

                /** Owns only its worker threads. Both streams remain caller-owned. */
                public CompletableFuture<Void> launch(InputStream input, OutputStream output) throws IOException {
                    Objects.requireNonNull(input, "input");
                    Objects.requireNonNull(output, "output");
                    var adapter = createAdapter();
                    var stopped = new AtomicBoolean();
                    var failure = new AtomicReference<Throwable>();
                    var disconnectReply = new CompletableFuture<Void>();
                    var executor = Executors.newCachedThreadPool();
                    var completion = new CompletableFuture<Void>();
                    java.util.function.Consumer<Throwable> fail = error -> {
                        stopped.set(true);
                        failure.compareAndSet(null, error);
                        disconnectReply.completeExceptionally(error);
                        // An arbitrary borrowed InputStream may ignore interruption. Report the
                        // failure now; its owner must then close/release any such blocked read.
                        completion.completeExceptionally(error);
                        executor.shutdownNow();
                    };
                    // A disconnect request waits for its reply before returning to the read loop.
                    // The next read is logical EOF, so no borrowed stream must be closed to unblock it.
                    var connection = new FilterInputStream(input) {
                        @Override public int read() throws IOException {
                            if (stopped.get()) return -1;
                            try { return super.read(); }
                            catch (IOException error) { fail.accept(error); throw error; }
                        }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                            if (length == 0) return 0;
                            if (stopped.get()) return -1;
                            try { return super.read(bytes, offset, length); }
                            catch (IOException error) { fail.accept(error); throw error; }
                        }
                        @Override public void close() { /* The caller owns the underlying stream. */ }
                    };
                    try {
                        var launcher = DSPLauncher.createServerLauncher(adapter, connection, output, executor,
                            consumer -> message -> {
                                try {
                                    consumer.consume(message);
                                    // System.out is a PrintStream: it records IO failures instead
                                    // of throwing them, so detect a broken stdio peer explicitly.
                                    if (output instanceof java.io.PrintStream stream && stream.checkError()) {
                                        throw new JsonRpcException(new IOException("DAP output failed"));
                                    }
                                    if (message instanceof DebugResponseMessage response && "disconnect".equals(response.getMethod())) {
                                        // StreamMessageConsumer.consume writes and flushes before returning.
                                        stopped.set(true);
                                        disconnectReply.complete(null);
                                    } else if (message instanceof RequestMessage request && "disconnect".equals(request.getMethod())) {
                                        try { disconnectReply.get(5, TimeUnit.SECONDS); }
                                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new JsonRpcException(error); }
                                        catch (ExecutionException | java.util.concurrent.TimeoutException error) { throw new JsonRpcException(error); }
                                    }
                                } catch (RuntimeException error) {
                                    fail.accept(error);
                                    throw error;
                                }
                            });
                        adapter.connect(launcher.getRemoteProxy());
                        var listening = new CompletableFuture<java.util.concurrent.Future<Void>>();
                        // Register the joiner before starting IO: an immediate read failure may
                        // shut down the executor before any later task can be accepted.
                        executor.execute(() -> {
                            try {
                                listening.get().get();
                                if (failure.get() == null) completion.complete(null);
                                else completion.completeExceptionally(failure.get());
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                completion.completeExceptionally(error);
                            } catch (ExecutionException error) {
                                completion.completeExceptionally(error.getCause());
                            } finally { executor.shutdown(); }
                        });
                        listening.complete(launcher.startListening());
                        return completion;
                    } catch (RuntimeException | Error error) {
                        executor.shutdownNow();
                        throw error;
                    }
                }
            }
            """.formatted(packageName, CodeGenerator.generatedAnnotation("org.unlaxer.dsl.codegen.DAPLauncherGenerator"), launcherClass, adapterClass, adapterClass, launcherClass);
        return new GeneratedSource(packageName, launcherClass, body);
    }
}
