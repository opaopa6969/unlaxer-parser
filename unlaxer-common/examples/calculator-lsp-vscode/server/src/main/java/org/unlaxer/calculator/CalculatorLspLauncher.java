package org.unlaxer.calculator;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Launcher for Calculator LSP Server.
 *
 * Usage:
 *   - Standard I/O mode (default): java CalculatorLspLauncher
 *   - Socket mode: java CalculatorLspLauncher --socket <port>
 */
public class CalculatorLspLauncher {

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--socket".equals(args[0])) {
            int port = Integer.parseInt(args[1]);
            startSocketMode(port);
        } else {
            startStdioMode();
        }
    }

    /**
     * Start LSP server using standard input/output.
     * This is the standard way for editors like VS Code.
     */
    private static void startStdioMode() throws Exception {
        System.err.println("Calculator LSP Server starting in stdio mode...");

        CalculatorLanguageServer server = new CalculatorLanguageServer();

        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(
            server,
            System.in,
            System.out
        );

        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);

        Future<?> startListening = launcher.startListening();
        System.err.println("Calculator LSP Server started.");

        startListening.get();
    }

    /**
     * Start LSP server on a TCP socket.
     * Useful for debugging and testing.
     */
    private static void startSocketMode(int port) throws Exception {
        System.err.println("Calculator LSP Server starting on port " + port + "...");

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.err.println("Waiting for client connection on port " + port + "...");

            ExecutorService threadPool = Executors.newCachedThreadPool();

            while (true) {
                Socket socket = serverSocket.accept();
                System.err.println("Client connected from " + socket.getRemoteSocketAddress());

                threadPool.submit(() -> {
                    try {
                        handleClient(socket);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        }
    }

    private static void handleClient(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        CalculatorLanguageServer server = new CalculatorLanguageServer();

        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(
            server,
            in,
            out
        );

        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);

        Future<?> startListening = launcher.startListening();
        System.err.println("Client session started.");

        startListening.get();
        System.err.println("Client session ended.");
    }
}
