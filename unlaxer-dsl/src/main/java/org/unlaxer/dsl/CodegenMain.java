package org.unlaxer.dsl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.ir.GrammarToParserIrExporter;
import org.unlaxer.dsl.ir.ParserIrConformanceValidator;
import org.unlaxer.dsl.ir.ParserIrJsonWriter;

/**
 * CLI tool entry point for UBNF validation and source generation.
 */
public class CodegenMain {
    static final int EXIT_OK = 0;
    static final int EXIT_CLI_ERROR = 2;
    static final int EXIT_VALIDATION_ERROR = 3;
    static final int EXIT_GENERATION_ERROR = 4;
    static final int EXIT_STRICT_VALIDATION_ERROR = 5;

    private static final String TOOL_VERSION = resolveToolVersion();

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != EXIT_OK) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return runWithClock(args, out, err, Clock.systemUTC());
    }

    static int runWithClock(String[] args, PrintStream out, PrintStream err, Clock clock) {
        boolean ndjsonRequested = isNdjsonRequested(args);
        try {
            CodegenCliParser.CliOptions config = CodegenCliParser.parse(args);
            if (config.help()) {
                printUsage(out);
                return EXIT_OK;
            }
            if (config.version()) {
                out.println(TOOL_VERSION);
                return EXIT_OK;
            }
            if (config.validateParserIrFile() != null) {
                return runParserIrValidation(config, out, err, ndjsonRequested);
            }
            if (config.exportParserIrFile() != null) {
                return runParserIrExport(config, out, err, ndjsonRequested);
            }
            return CodegenRunner.execute(config, out, err, clock, TOOL_VERSION, ArgsHashUtil.fromOptions(config));
        } catch (CodegenCliParser.UsageException e) {
            if (ndjsonRequested) {
                emitNdjsonCliError(
                    out,
                    "E-CLI-USAGE",
                    normalizeMessage("CLI usage error", e.getMessage()),
                    e.showUsage() ? "Use --help to view usage." : null
                );
                return EXIT_CLI_ERROR;
            }
            if (e.getMessage() != null && !e.getMessage().isBlank()) {
                err.println(e.getMessage());
            }
            if (e.showUsage()) {
                printUsage(err);
            }
            return EXIT_CLI_ERROR;
        } catch (ReportSchemaValidationException e) {
            if (ndjsonRequested) {
                emitNdjsonCliError(out, e.code(), normalizeMessage("Schema validation error", e.getMessage()), null);
                return EXIT_GENERATION_ERROR;
            }
            err.println(e.code() + ": " + e.getMessage());
            return EXIT_GENERATION_ERROR;
        } catch (IOException e) {
            if (ndjsonRequested) {
                emitNdjsonCliError(out, "E-IO", normalizeMessage("I/O error", e.getMessage()), null);
                return EXIT_GENERATION_ERROR;
            }
            err.println("I/O error: " + e.getMessage());
            return EXIT_GENERATION_ERROR;
        } catch (RuntimeException e) {
            if (ndjsonRequested) {
                emitNdjsonCliError(out, "E-RUNTIME", normalizeMessage("Generation failed", e.getMessage()), null);
                return EXIT_GENERATION_ERROR;
            }
            err.println("Generation failed: " + e.getMessage());
            return EXIT_GENERATION_ERROR;
        }
    }

    private static void emitNdjsonCliError(PrintStream out, String code, String message, String detail) {
        out.println(NdjsonErrorEventWriter.cliErrorEvent(code, message, detail, List.of()));
    }

    private static int runParserIrValidation(
        CodegenCliParser.CliOptions config,
        PrintStream out,
        PrintStream err,
        boolean ndjsonRequested
    ) throws IOException {
        try {
            String payload = Files.readString(Path.of(config.validateParserIrFile()));
            ParserIrSchemaValidator.validate(payload);
            if (ndjsonRequested) {
                out.println("{\"event\":\"parser-ir-validate\",\"ok\":true}");
            } else {
                out.println("Parser IR validation succeeded");
            }
            return EXIT_OK;
        } catch (ReportSchemaValidationException e) {
            if (ndjsonRequested) {
                emitNdjsonCliError(out, e.code(), normalizeMessage("Parser IR validation error", e.getMessage()), null);
                return EXIT_VALIDATION_ERROR;
            }
            err.println(e.code() + ": " + e.getMessage());
            return EXIT_VALIDATION_ERROR;
        }
    }

    private static int runParserIrExport(
        CodegenCliParser.CliOptions config,
        PrintStream out,
        PrintStream err,
        boolean ndjsonRequested
    ) throws IOException {
        try {
            String source = Files.readString(Path.of(config.grammarFile()));
            UBNFFile ubnf = UBNFMapper.parse(source);
            if (ubnf.grammars().isEmpty()) {
                throw new IllegalArgumentException("no grammar blocks found for parser ir export");
            }
            var document = GrammarToParserIrExporter.exportAll(ubnf.grammars(), config.grammarFile());
            ParserIrConformanceValidator.validate(document);
            String json = ParserIrJsonWriter.toJson(document.payload());
            Files.writeString(Path.of(config.exportParserIrFile()), json);
            int grammarCount = ubnf.grammars().size();
            int nodeCount = getArraySize(document.payload(), "nodes");
            int annotationCount = getArraySize(document.payload(), "annotations");
            if (ndjsonRequested) {
                out.println(
                    "{\"event\":\"parser-ir-export\",\"ok\":true,\"source\":\""
                        + escapeJson(config.grammarFile()) + "\",\"output\":\""
                        + escapeJson(config.exportParserIrFile()) + "\",\"grammarCount\":"
                        + grammarCount + ",\"nodeCount\":"
                        + nodeCount + ",\"annotationCount\":" + annotationCount + "}"
                );
            } else {
                out.println("Parser IR export succeeded: " + config.exportParserIrFile());
            }
            return EXIT_OK;
        } catch (IllegalArgumentException e) {
            if (ndjsonRequested) {
                emitNdjsonCliError(out, "E-PARSER-IR-EXPORT", normalizeMessage("Parser IR export error", e.getMessage()), null);
            } else {
                err.println("E-PARSER-IR-EXPORT: " + e.getMessage());
            }
            return EXIT_VALIDATION_ERROR;
        }
    }

    private static int getArraySize(java.util.Map<String, Object> payload, String key) {
        Object raw = payload.get(key);
        if (!(raw instanceof java.util.List<?> list)) {
            return 0;
        }
        return list.size();
    }

    private static String escapeJson(String s) {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static boolean isNdjsonRequested(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--report-format".equals(arg)) {
                if (i + 1 < args.length && "ndjson".equals(args[i + 1])) {
                    return true;
                }
                continue;
            }
            if (arg.startsWith("--report-format=") && "ndjson".equals(arg.substring("--report-format=".length()))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeMessage(String fallback, String message) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message;
    }

    private static void printUsage(PrintStream err) {
        err.println(
            "Usage: CodegenMain [--help] [--version] --grammar <file.ubnf> --output <dir>"
                + " [--generators AST,Parser,Mapper,Evaluator,LSP,Launcher,DAP,DAPLauncher]"
                + " [--validate-parser-ir <parser-ir.json>]"
                + " [--export-parser-ir <parser-ir.json>]"
                + " [--validate-only]"
                + " [--dry-run]"
                + " [--clean-output]"
                + " [--overwrite never|if-different|always]"
                + " [--fail-on none|warning|skipped|conflict|cleaned|warnings-count>=N]"
                + " [--strict]"
                + " [--report-format text|json|ndjson]"
                + " [--report-file <path>]"
                + " [--output-manifest <path>]"
                + " [--manifest-format json|ndjson]"
                + " [--report-version 1]"
                + " [--report-schema-check]"
                + " [--warnings-as-json]"
        );
    }

    private static String resolveToolVersion() {
        Package pkg = CodegenMain.class.getPackage();
        if (pkg == null) {
            return "dev";
        }
        String version = pkg.getImplementationVersion();
        if (version == null || version.isBlank()) {
            return "dev";
        }
        return version;
    }

}
