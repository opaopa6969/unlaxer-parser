package org.unlaxer.dsl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal CLI for parser IR schema/conformance validation.
 */
public class ParserIrSchemaMain {
    static final int EXIT_OK = 0;
    static final int EXIT_CLI_ERROR = 2;
    static final int EXIT_VALIDATION_ERROR = 3;
    static final int EXIT_IO_ERROR = 4;

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != EXIT_OK) {
            System.exit(code);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String path = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage(out);
                return EXIT_OK;
            }
            if ("--ir".equals(arg)) {
                if (i + 1 >= args.length) {
                    err.println("Missing value for --ir");
                    printUsage(err);
                    return EXIT_CLI_ERROR;
                }
                path = args[++i];
                continue;
            }
            err.println("Unknown argument: " + arg);
            printUsage(err);
            return EXIT_CLI_ERROR;
        }
        if (path == null) {
            printUsage(err);
            return EXIT_CLI_ERROR;
        }
        try {
            String payload = Files.readString(Path.of(path));
            ParserIrSchemaValidator.validate(payload);
            out.println("OK: " + path);
            return EXIT_OK;
        } catch (ReportSchemaValidationException e) {
            err.println(e.code() + ": " + e.getMessage());
            return EXIT_VALIDATION_ERROR;
        } catch (IOException e) {
            err.println("I/O error: " + e.getMessage());
            return EXIT_IO_ERROR;
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: ParserIrSchemaMain --ir <parser-ir.json>");
    }
}
