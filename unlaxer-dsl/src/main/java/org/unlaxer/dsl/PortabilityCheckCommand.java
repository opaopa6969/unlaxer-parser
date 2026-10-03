package org.unlaxer.dsl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot.Span;

/** CLI for read-only portability analysis. */
final class PortabilityCheckCommand {
    private PortabilityCheckCommand() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 2 && "--help".equals(args[1])) {
            out.println("Usage: check --target rust --grammar <file.ubnf> [--format json]");
            return CodegenMain.EXIT_OK;
        }
        Map<String, String> options = new HashMap<>();
        try {
            for (int i = 1; i < args.length; i++) {
                String option = args[i];
                if (!Set.of("--target", "--grammar", "--format").contains(option)
                    || i + 1 == args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("unknown or incomplete option " + option);
                }
                String value = args[++i];
                if (value.isEmpty()) throw new IllegalArgumentException("empty value for " + option);
                if (options.putIfAbsent(option, value) != null) throw new IllegalArgumentException("duplicate " + option);
            }
            if (!"rust".equals(options.get("--target")) || !options.containsKey("--grammar")
                || !"json".equals(options.getOrDefault("--format", "json"))) {
                throw new IllegalArgumentException("required: --target rust --grammar <file.ubnf> [--format json]");
            }
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            return CodegenMain.EXIT_CLI_ERROR;
        }
        try {
            PortabilityCheck.Result result = PortabilityCheck.checkFile(Path.of(options.get("--grammar")));
            out.println(toJson(result));
            return result.portable() ? CodegenMain.EXIT_OK : CodegenMain.EXIT_VALIDATION_ERROR;
        } catch (IOException | InvalidPathException error) {
            err.println("I/O error: " + error.getMessage());
            return CodegenMain.EXIT_GENERATION_ERROR;
        }
    }

    private static String toJson(PortabilityCheck.Result result) {
        StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"target\":\"rust\",\"portable\":")
            .append(result.portable()).append(",\"structure\":\"").append(result.structure())
            .append("\",\"diagnostics\":[");
        for (int i = 0; i < result.diagnostics().size(); i++) {
            if (i != 0) json.append(',');
            PortabilityCheck.Diagnostic diagnostic = result.diagnostics().get(i);
            json.append("{\"code\":").append(quote(diagnostic.code()))
                .append(",\"severity\":").append(quote(diagnostic.severity())).append(",\"span\":");
            Span span = diagnostic.span();
            if (span == null) json.append("null");
            else json.append("{\"start\":").append(span.start()).append(",\"end\":").append(span.end()).append('}');
            json.append(",\"subject\":").append(quote(diagnostic.subject())).append('}');
        }
        return json.append("]}").toString();
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20) json.append(String.format("\\u%04x", (int) ch));
                    else json.append(ch);
                }
            }
        }
        return json.append('"').toString();
    }
}
