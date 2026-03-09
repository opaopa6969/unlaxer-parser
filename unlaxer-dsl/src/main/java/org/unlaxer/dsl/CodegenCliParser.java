package org.unlaxer.dsl;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Parses CodegenMain CLI arguments.
 */
final class CodegenCliParser {

    private CodegenCliParser() {}

    static final int DEFAULT_REPORT_VERSION = 1;

    static CliOptions parse(String[] args) throws UsageException {
        String grammarFile = null;
        String outputDir = null;
        List<String> generators = List.of("Parser", "LSP", "Launcher");
        boolean validateOnly = false;
        boolean dryRun = false;
        boolean cleanOutput = false;
        boolean strict = false;
        boolean help = false;
        boolean version = false;
        String reportFormat = "text";
        String reportFile = null;
        String outputManifest = null;
        String validateParserIrFile = null;
        String exportParserIrFile = null;
        String manifestFormat = "json";
        int reportVersion = DEFAULT_REPORT_VERSION;
        boolean reportSchemaCheck = false;
        boolean warningsAsJson = false;
        String overwrite = "always";
        String failOn = "conflict";
        int failOnWarningsThreshold = -1;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--grammar" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --grammar", true);
                    }
                    grammarFile = args[++i];
                }
                case "--output" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --output", true);
                    }
                    outputDir = args[++i];
                }
                case "--generators" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --generators", true);
                    }
                    generators =
                        Arrays.stream(args[++i].split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                    if (generators.isEmpty()) {
                        throw new UsageException("No generators specified for --generators", false);
                    }
                }
                case "--validate-only" -> validateOnly = true;
                case "--dry-run" -> dryRun = true;
                case "--clean-output" -> cleanOutput = true;
                case "--strict" -> strict = true;
                case "--help", "-h" -> help = true;
                case "--version", "-v" -> version = true;
                case "--report-format" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --report-format", true);
                    }
                    reportFormat = args[++i].trim().toLowerCase();
                    if (!"text".equals(reportFormat) && !"json".equals(reportFormat) && !"ndjson".equals(reportFormat)) {
                        throw new UsageException(
                            "Unsupported --report-format: " + reportFormat + "\nAllowed values: text, json, ndjson",
                            false
                        );
                    }
                }
                case "--report-file" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --report-file", true);
                    }
                    reportFile = args[++i];
                }
                case "--output-manifest" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --output-manifest", true);
                    }
                    outputManifest = args[++i];
                }
                case "--validate-parser-ir" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --validate-parser-ir", true);
                    }
                    validateParserIrFile = args[++i];
                }
                case "--export-parser-ir" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --export-parser-ir", true);
                    }
                    exportParserIrFile = args[++i];
                }
                case "--report-version" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --report-version", true);
                    }
                    String raw = args[++i].trim();
                    try {
                        reportVersion = Integer.parseInt(raw);
                    } catch (NumberFormatException e) {
                        throw new UsageException(
                            "Unsupported --report-version: " + raw + "\nAllowed values: 1",
                            false
                        );
                    }
                    if (reportVersion != 1) {
                        throw new UsageException(
                            "Unsupported --report-version: " + reportVersion + "\nAllowed values: 1",
                            false
                        );
                    }
                }
                case "--report-schema-check" -> reportSchemaCheck = true;
                case "--warnings-as-json" -> warningsAsJson = true;
                case "--overwrite" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --overwrite", true);
                    }
                    overwrite = args[++i].trim().toLowerCase();
                    if (!"never".equals(overwrite) && !"if-different".equals(overwrite) && !"always".equals(overwrite)) {
                        throw new UsageException(
                            "Unsupported --overwrite: " + overwrite + "\nAllowed values: never, if-different, always",
                            false
                        );
                    }
                }
                case "--fail-on" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --fail-on", true);
                    }
                    failOn = args[++i].trim().toLowerCase();
                    if (failOn.startsWith("warnings-count>=")) {
                        String raw = failOn.substring("warnings-count>=".length()).trim();
                        try {
                            failOnWarningsThreshold = Integer.parseInt(raw);
                        } catch (NumberFormatException e) {
                            throw new UsageException(
                                "Unsupported --fail-on: " + failOn
                                + "\nAllowed values: none, warning, skipped, conflict, cleaned, warnings-count>=N",
                                false
                            );
                        }
                        if (failOnWarningsThreshold < 0) {
                            throw new UsageException(
                                "Unsupported --fail-on: " + failOn
                                + "\nAllowed values: none, warning, skipped, conflict, cleaned, warnings-count>=N",
                                false
                            );
                        }
                        failOn = "warnings-count";
                    } else if (!"none".equals(failOn) && !"warning".equals(failOn)
                        && !"skipped".equals(failOn) && !"conflict".equals(failOn) && !"cleaned".equals(failOn)) {
                        throw new UsageException(
                            "Unsupported --fail-on: " + failOn
                                + "\nAllowed values: none, warning, skipped, conflict, cleaned, warnings-count>=N",
                            false
                        );
                    }
                }
                case "--manifest-format" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Missing value for --manifest-format", true);
                    }
                    manifestFormat = args[++i].trim().toLowerCase();
                    if (!"json".equals(manifestFormat) && !"ndjson".equals(manifestFormat)) {
                        throw new UsageException(
                            "Unsupported --manifest-format: " + manifestFormat + "\nAllowed values: json, ndjson",
                            false
                        );
                    }
                }
                default -> throw new UsageException("Unknown argument: " + args[i], true);
            }
        }

        if (help || version) {
            return new CliOptions(
                grammarFile,
                outputDir,
                generators,
                validateOnly,
                dryRun,
                cleanOutput,
                strict,
                help,
                version,
                reportFormat,
                reportFile,
                outputManifest,
                validateParserIrFile,
                exportParserIrFile,
                manifestFormat,
                reportVersion,
                reportSchemaCheck,
                warningsAsJson,
                overwrite,
                failOn,
                failOnWarningsThreshold
            );
        }

        if (validateParserIrFile != null) {
            if (grammarFile != null || outputDir != null || validateOnly || exportParserIrFile != null) {
                throw new UsageException(
                    "--validate-parser-ir must not be combined with --grammar/--output/--validate-only/--export-parser-ir",
                    false
                );
            }
            return new CliOptions(
                grammarFile,
                outputDir,
                generators,
                validateOnly,
                dryRun,
                cleanOutput,
                strict,
                help,
                version,
                reportFormat,
                reportFile,
                outputManifest,
                validateParserIrFile,
                exportParserIrFile,
                manifestFormat,
                reportVersion,
                reportSchemaCheck,
                warningsAsJson,
                overwrite,
                failOn,
                failOnWarningsThreshold
            );
        }

        if (exportParserIrFile != null) {
            if (grammarFile == null || outputDir != null || validateOnly) {
                throw new UsageException(
                    "--export-parser-ir requires --grammar and must not be combined with --output/--validate-only",
                    false
                );
            }
            return new CliOptions(
                grammarFile,
                outputDir,
                generators,
                validateOnly,
                dryRun,
                cleanOutput,
                strict,
                help,
                version,
                reportFormat,
                reportFile,
                outputManifest,
                validateParserIrFile,
                exportParserIrFile,
                manifestFormat,
                reportVersion,
                reportSchemaCheck,
                warningsAsJson,
                overwrite,
                failOn,
                failOnWarningsThreshold
            );
        }

        if (grammarFile == null || (!validateOnly && outputDir == null)) {
            throw new UsageException(null, true);
        }

        return new CliOptions(
            grammarFile,
            outputDir,
            generators,
            validateOnly,
            dryRun,
            cleanOutput,
            strict,
            help,
            version,
            reportFormat,
            reportFile,
            outputManifest,
            validateParserIrFile,
            exportParserIrFile,
            manifestFormat,
            reportVersion,
            reportSchemaCheck,
            warningsAsJson,
            overwrite,
            failOn,
            failOnWarningsThreshold
        );
    }

    record CliOptions(
        String grammarFile,
        String outputDir,
        List<String> generators,
        boolean validateOnly,
        boolean dryRun,
        boolean cleanOutput,
        boolean strict,
        boolean help,
        boolean version,
        String reportFormat,
        String reportFile,
        String outputManifest,
        String validateParserIrFile,
        String exportParserIrFile,
        String manifestFormat,
        int reportVersion,
        boolean reportSchemaCheck,
        boolean warningsAsJson,
        String overwrite,
        String failOn,
        int failOnWarningsThreshold
    ) {}

    static final class UsageException extends Exception {
        private final boolean showUsage;

        UsageException(String message, boolean showUsage) {
            super(message);
            this.showUsage = showUsage;
        }

        boolean showUsage() {
            return showUsage;
        }
    }
}
