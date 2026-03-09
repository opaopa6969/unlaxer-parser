package org.unlaxer.dsl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.ASTGenerator;
import org.unlaxer.dsl.codegen.CodeGenerator;
import org.unlaxer.dsl.codegen.DAPGenerator;
import org.unlaxer.dsl.codegen.DAPLauncherGenerator;
import org.unlaxer.dsl.codegen.EvaluatorGenerator;
import org.unlaxer.dsl.codegen.GrammarValidator;
import org.unlaxer.dsl.codegen.LSPGenerator;
import org.unlaxer.dsl.codegen.LSPLauncherGenerator;
import org.unlaxer.dsl.codegen.MapperGenerator;
import org.unlaxer.dsl.codegen.ParserGenerator;

/**
 * Orchestrates validation and generation steps for the CLI.
 */
final class CodegenRunner {

    private CodegenRunner() {}

    interface FileSystemPort {
        String readString(Path path) throws IOException;
        boolean exists(Path path);
        boolean deleteIfExists(Path path) throws IOException;
        void createDirectories(Path path) throws IOException;
        void writeString(Path path, String content) throws IOException;
    }

    static int execute(
        CodegenCliParser.CliOptions config,
        PrintStream out,
        PrintStream err,
        Clock clock,
        String toolVersion,
        String argsHash
    ) throws IOException {
        return execute(config, out, err, clock, toolVersion, argsHash, new JdkFileSystemPort());
    }

    static int execute(
        CodegenCliParser.CliOptions config,
        PrintStream out,
        PrintStream err,
        Clock clock,
        String toolVersion,
        String argsHash,
        FileSystemPort fs
    ) throws IOException {
        String source = fs.readString(Path.of(config.grammarFile()));
        UBNFFile file = UBNFMapper.parse(source);

        Map<String, CodeGenerator> generatorMap = generatorMap();

        List<ReportJsonWriter.ValidationIssueRow> validationRows = collectValidationRows(file);
        List<ReportJsonWriter.ValidationIssueRow> sortedRows = sortValidationRows(validationRows);

        boolean hasErrors = hasErrorRows(sortedRows);
        boolean hasWarnings = hasWarningRows(sortedRows);
        int warningsCount = countWarnings(sortedRows);
        String validationPolicyFailReason = validationPolicyFailReason(config, warningsCount);
        boolean warningPolicyTriggered = validationPolicyFailReason != null;

        String generatedAt = Instant.now(clock).toString();
        List<FileEvent> fileEvents = new ArrayList<>();

        String warningJson = hasWarnings
            ? ReportJsonWriter.validationFailure(
                config.reportVersion(),
                toolVersion,
                argsHash,
                generatedAt,
                validationPolicyFailReason,
                sortedRows
            )
            : null;

        if (hasErrors || warningPolicyTriggered) {
            int exitCode = hasErrors ? CodegenMain.EXIT_VALIDATION_ERROR : CodegenMain.EXIT_STRICT_VALIDATION_ERROR;
            boolean emitJson = isJsonLike(config.reportFormat()) || (config.warningsAsJson() && !hasErrors);
            if (emitJson) {
                String json = warningJson != null
                    ? warningJson
                    : ReportJsonWriter.validationFailure(
                        config.reportVersion(),
                        toolVersion,
                        argsHash,
                        generatedAt,
                        validationPolicyFailReason,
                        sortedRows
                    );
                validateJsonIfRequested(config, json, true);
                writeReportIfNeeded(config.reportFile(), json, fs);
                if ("ndjson".equals(config.reportFormat())) {
                    err.println(ndjsonReportEvent(hasErrors ? "validate-failure" : "strict-failure", json));
                } else {
                    err.println(json);
                }
                writeManifestIfNeeded(
                    config,
                    fs,
                    "validate",
                    generatedAt,
                    toolVersion,
                    argsHash,
                    fileEvents,
                    warningsCount,
                    0,
                    0,
                    0,
                    0,
                    false,
                    validationPolicyFailReason,
                    exitCode
                );
                return exitCode;
            }

            String prefix = hasErrors
                ? "Grammar validation failed"
                : "Strict validation failed (warnings present)";
            List<String> validationMessages = sortedRows.stream()
                .map(row -> "grammar " + row.grammar() + ": " + toTextIssue(row))
                .toList();
            String text = prefix + ":\n - " + String.join("\n - ", validationMessages);
            writeReportIfNeeded(config.reportFile(), text, fs);
            err.println(text);
            writeManifestIfNeeded(
                config,
                fs,
                "validate",
                generatedAt,
                toolVersion,
                argsHash,
                fileEvents,
                warningsCount,
                0,
                0,
                0,
                0,
                false,
                validationPolicyFailReason,
                exitCode
            );
            return exitCode;
        }

        if (hasWarnings) {
            if (config.warningsAsJson() || "ndjson".equals(config.reportFormat())) {
                validateJsonIfRequested(config, warningJson, config.reportSchemaCheck());
                if ("ndjson".equals(config.reportFormat())) {
                    err.println(ndjsonReportEvent("warnings", warningJson));
                } else {
                    err.println(warningJson);
                }
            } else {
                emitWarnings(err, sortedRows);
            }
        }

        if (config.validateOnly()) {
            if (isJsonLike(config.reportFormat())) {
                String json = ReportJsonWriter.validationSuccess(
                    config.reportVersion(),
                    toolVersion,
                    argsHash,
                    generatedAt,
                    file.grammars().size(),
                    warningsCount
                );
                validateJsonIfRequested(config, json, true);
                if ("ndjson".equals(config.reportFormat())) {
                    out.println(ndjsonReportEvent("validate-success", json));
                } else {
                    out.println(json);
                }
                writeReportIfNeeded(config.reportFile(), json, fs);
            } else {
                String text = "Validation succeeded for " + file.grammars().size() + " grammar(s).";
                out.println(text);
                writeReportIfNeeded(config.reportFile(), text, fs);
            }
            writeManifestIfNeeded(
                config,
                fs,
                "validate",
                generatedAt,
                toolVersion,
                argsHash,
                fileEvents,
                warningsCount,
                0,
                0,
                0,
                0,
                true,
                null,
                CodegenMain.EXIT_OK
            );
            return CodegenMain.EXIT_OK;
        }

        Path outPath = Path.of(config.outputDir());
        if (config.cleanOutput() && isUnsafeCleanOutputPath(outPath)) {
            emitCliRuntimeError(
                config,
                out,
                err,
                "E-CLI-UNSAFE-CLEAN-OUTPUT",
                "Refusing --clean-output for unsafe path",
                outPath.toAbsolutePath().normalize().toString(),
                null
            );
            return CodegenMain.EXIT_CLI_ERROR;
        }

        List<String> generatedFiles = new ArrayList<>();
        int writtenCount = 0;
        int skippedCount = 0;
        int conflictCount = 0;
        int dryRunCount = 0;

        for (GrammarDecl grammar : file.grammars()) {
            for (String name : config.generators()) {
                String key = name.trim();
                CodeGenerator gen = generatorMap.get(key);
                if (gen == null) {
                    emitCliRuntimeError(
                        config,
                        out,
                        err,
                        "E-CLI-UNKNOWN-GENERATOR",
                        "Unknown generator: " + name,
                        null,
                        generatorMap.keySet().stream().sorted().toList()
                    );
                    return CodegenMain.EXIT_CLI_ERROR;
                }

                CodeGenerator.GeneratedSource src = gen.generate(grammar);
                Path pkgDir = outPath.resolve(src.packageName().replace('.', '/'));
                Path javaFile = pkgDir.resolve(src.className() + ".java");

                if (config.cleanOutput() && !config.dryRun()) {
                    if (fs.deleteIfExists(javaFile)) {
                        fileEvents.add(new FileEvent("cleaned", javaFile.toString()));
                        if ("ndjson".equals(config.reportFormat())) {
                            out.println(ndjsonFileEvent("cleaned", javaFile.toString()));
                        }
                    }
                }

                WriteAction action = writeGeneratedSource(config, fs, pkgDir, javaFile, src.source());
                if (action == WriteAction.CONFLICT) {
                    conflictCount++;
                    fileEvents.add(new FileEvent("conflict", javaFile.toString()));
                    if (!"ndjson".equals(config.reportFormat())) {
                        err.println("Conflict (not overwritten): " + javaFile);
                    }
                    if ("ndjson".equals(config.reportFormat())) {
                        out.println(ndjsonFileEvent("conflict", javaFile.toString()));
                    }
                    continue;
                }
                if (action == WriteAction.SKIPPED) {
                    skippedCount++;
                    fileEvents.add(new FileEvent("skipped", javaFile.toString()));
                    if (!"ndjson".equals(config.reportFormat())) {
                        out.println("Skipped (unchanged): " + javaFile);
                    }
                    if ("ndjson".equals(config.reportFormat())) {
                        out.println(ndjsonFileEvent("skipped", javaFile.toString()));
                    }
                    continue;
                }
                if (action == WriteAction.DRY_RUN) {
                    dryRunCount++;
                    fileEvents.add(new FileEvent("dry-run", javaFile.toString()));
                    if (!"ndjson".equals(config.reportFormat())) {
                        out.println("Dry-run: would generate " + javaFile);
                    }
                    if ("ndjson".equals(config.reportFormat())) {
                        out.println(ndjsonFileEvent("dry-run", javaFile.toString()));
                    }
                } else {
                    writtenCount++;
                    fileEvents.add(new FileEvent("written", javaFile.toString()));
                    if (!"ndjson".equals(config.reportFormat())) {
                        out.println("Generated: " + javaFile);
                    }
                    if ("ndjson".equals(config.reportFormat())) {
                        out.println(ndjsonFileEvent("written", javaFile.toString()));
                    }
                }
                generatedFiles.add(javaFile.toString());
            }
        }

        String generationFailReason = generationPolicyFailReason(config, fileEvents, skippedCount, conflictCount);
        boolean generationOk = generationFailReason == null;
        int generationExitCode = generationOk ? CodegenMain.EXIT_OK : CodegenMain.EXIT_GENERATION_ERROR;
        if (!generationOk) {
            emitGenerationPolicyFailureMessage(
                err,
                config.reportFormat(),
                generationFailReason,
                skippedCount,
                conflictCount,
                countEvents(fileEvents, "cleaned")
            );
        }

        if (isJsonLike(config.reportFormat())) {
            String json = ReportJsonWriter.generationResult(
                config.reportVersion(),
                toolVersion,
                argsHash,
                generatedAt,
                generationOk,
                generationFailReason,
                file.grammars().size(),
                generatedFiles,
                warningsCount,
                writtenCount,
                skippedCount,
                conflictCount,
                dryRunCount
            );
            validateJsonIfRequested(config, json, true);
            if ("ndjson".equals(config.reportFormat())) {
                out.println(ndjsonReportEvent("generate-summary", json));
            } else {
                if (generationOk) {
                    out.println(json);
                } else {
                    err.println(json);
                }
            }
            writeReportIfNeeded(config.reportFile(), json, fs);
        } else if (config.reportFile() != null) {
            String text = "Generated files:\n" + generatedFiles.stream()
                .map(p -> " - " + p)
                .collect(Collectors.joining("\n"));
            writeReportIfNeeded(config.reportFile(), text, fs);
        }

        writeManifestIfNeeded(
            config,
            fs,
            "generate",
            generatedAt,
            toolVersion,
            argsHash,
            fileEvents,
            warningsCount,
            writtenCount,
            skippedCount,
            conflictCount,
            dryRunCount,
            generationOk,
            generationFailReason,
            generationExitCode
        );

        return generationExitCode;
    }

    static boolean hasErrorRows(List<ReportJsonWriter.ValidationIssueRow> rows) {
        return rows.stream().anyMatch(row -> !"WARNING".equals(row.severity()));
    }

    static boolean hasWarningRows(List<ReportJsonWriter.ValidationIssueRow> rows) {
        return rows.stream().anyMatch(row -> "WARNING".equals(row.severity()));
    }

    private static int countWarnings(List<ReportJsonWriter.ValidationIssueRow> rows) {
        int count = 0;
        for (ReportJsonWriter.ValidationIssueRow row : rows) {
            if ("WARNING".equals(row.severity())) {
                count++;
            }
        }
        return count;
    }

    private static int countEvents(List<FileEvent> events, String action) {
        int count = 0;
        for (FileEvent event : events) {
            if (action.equals(event.action())) {
                count++;
            }
        }
        return count;
    }

    private static boolean isJsonLike(String reportFormat) {
        return "json".equals(reportFormat) || "ndjson".equals(reportFormat);
    }

    private static Map<String, CodeGenerator> generatorMap() {
        Map<String, CodeGenerator> generatorMap = new LinkedHashMap<>();
        generatorMap.put("AST", new ASTGenerator());
        generatorMap.put("Parser", new ParserGenerator());
        generatorMap.put("Mapper", new MapperGenerator());
        generatorMap.put("Evaluator", new EvaluatorGenerator());
        generatorMap.put("LSP", new LSPGenerator());
        generatorMap.put("Launcher", new LSPLauncherGenerator());
        generatorMap.put("DAP", new DAPGenerator());
        generatorMap.put("DAPLauncher", new DAPLauncherGenerator());
        return generatorMap;
    }

    private static List<ReportJsonWriter.ValidationIssueRow> collectValidationRows(UBNFFile file) {
        List<ReportJsonWriter.ValidationIssueRow> validationRows = new ArrayList<>();
        for (GrammarDecl grammar : file.grammars()) {
            List<GrammarValidator.ValidationIssue> issues = GrammarValidator.validate(grammar);
            for (GrammarValidator.ValidationIssue issue : issues) {
                validationRows.add(new ReportJsonWriter.ValidationIssueRow(
                    grammar.name(),
                    issue.rule(),
                    issue.code(),
                    issue.severity(),
                    issue.category(),
                    issue.message(),
                    issue.hint()
                ));
            }
        }
        return validationRows;
    }

    private static List<ReportJsonWriter.ValidationIssueRow> sortValidationRows(
        List<ReportJsonWriter.ValidationIssueRow> rows
    ) {
        return rows.stream()
            .sorted(
                Comparator.comparing(ReportJsonWriter.ValidationIssueRow::grammar)
                    .thenComparing(
                        ReportJsonWriter.ValidationIssueRow::rule,
                        Comparator.nullsFirst(String::compareTo)
                    )
                    .thenComparing(ReportJsonWriter.ValidationIssueRow::code)
                    .thenComparing(ReportJsonWriter.ValidationIssueRow::message)
            )
            .toList();
    }

    private static String toTextIssue(ReportJsonWriter.ValidationIssueRow row) {
        return row.message() + " [code: " + row.code() + "] [hint: " + row.hint() + "]";
    }

    private static void emitWarnings(PrintStream err, List<ReportJsonWriter.ValidationIssueRow> rows) {
        List<ReportJsonWriter.ValidationIssueRow> warnings = rows.stream()
            .filter(row -> "WARNING".equals(row.severity()))
            .toList();
        if (warnings.isEmpty()) {
            return;
        }
        err.println("Validation warnings:");
        for (ReportJsonWriter.ValidationIssueRow row : warnings) {
            err.println(" - grammar " + row.grammar() + ": " + toTextIssue(row));
        }
    }

    private static String ndjsonFileEvent(String action, String filePath) {
        return "{\"event\":\"file\",\"action\":\""
            + ReportJsonWriter.escapeJson(action)
            + "\",\"path\":\""
            + ReportJsonWriter.escapeJson(filePath)
            + "\"}";
    }

    private static String ndjsonReportEvent(String event, String payloadJson) {
        return "{\"event\":\"" + ReportJsonWriter.escapeJson(event) + "\",\"payload\":" + payloadJson + "}";
    }

    private static void emitCliRuntimeError(
        CodegenCliParser.CliOptions config,
        PrintStream out,
        PrintStream err,
        String code,
        String message,
        String detail,
        List<String> availableGenerators
    ) {
        if ("ndjson".equals(config.reportFormat())) {
            out.println(NdjsonErrorEventWriter.cliErrorEvent(code, message, detail, availableGenerators));
            return;
        }
        err.println(message);
        if (detail != null && !detail.isBlank()) {
            err.println("Detail: " + detail);
        }
        if (availableGenerators != null && !availableGenerators.isEmpty()) {
            err.println("Available: " + String.join(", ", availableGenerators));
        }
    }

    private static boolean shouldFailOn(CodegenCliParser.CliOptions config, String key) {
        if ("warning".equals(key)) {
            return config.strict() || "warning".equals(config.failOn());
        }
        return Objects.equals(config.failOn(), key);
    }

    private static boolean shouldFailOnWarningsThreshold(CodegenCliParser.CliOptions config, int warningsCount) {
        if (!"warnings-count".equals(config.failOn())) {
            return false;
        }
        return warningsCount >= config.failOnWarningsThreshold();
    }

    private static String validationPolicyFailReason(CodegenCliParser.CliOptions config, int warningsCount) {
        if (warningsCount > 0 && shouldFailOnWarningsThreshold(config, warningsCount)) {
            return "FAIL_ON_WARNINGS_COUNT";
        }
        if (shouldFailOn(config, "warning") && warningsCount > 0) {
            return "FAIL_ON_WARNING";
        }
        return null;
    }

    private static String generationPolicyFailReason(
        CodegenCliParser.CliOptions config,
        List<FileEvent> fileEvents,
        int skippedCount,
        int conflictCount
    ) {
        if (shouldFailOn(config, "skipped") && skippedCount > 0) {
            return "FAIL_ON_SKIPPED";
        }
        if (shouldFailOn(config, "conflict") && conflictCount > 0) {
            return "FAIL_ON_CONFLICT";
        }
        int cleanedCount = countEvents(fileEvents, "cleaned");
        if (shouldFailOn(config, "cleaned") && cleanedCount > 0) {
            return "FAIL_ON_CLEANED";
        }
        return null;
    }

    private static void emitGenerationPolicyFailureMessage(
        PrintStream err,
        String reportFormat,
        String failReasonCode,
        int skippedCount,
        int conflictCount,
        int cleanedCount
    ) {
        if ("ndjson".equals(reportFormat)) {
            return;
        }
        switch (failReasonCode) {
            case "FAIL_ON_SKIPPED" -> err.println("Fail-on policy triggered: skipped=" + skippedCount);
            case "FAIL_ON_CONFLICT" -> err.println("Fail-on policy triggered: conflict=" + conflictCount);
            case "FAIL_ON_CLEANED" -> err.println("Fail-on policy triggered: cleaned=" + cleanedCount);
            default -> err.println("Fail-on policy triggered: " + failReasonCode);
        }
    }

    private static void validateJsonIfRequested(
        CodegenCliParser.CliOptions config,
        String json,
        boolean shouldValidate
    ) {
        if (!shouldValidate || !config.reportSchemaCheck()) {
            return;
        }
        ReportJsonSchemaValidator.validate(config.reportVersion(), json);
    }

    private static void writeReportIfNeeded(String reportFile, String content, FileSystemPort fs) throws IOException {
        if (reportFile == null) {
            return;
        }
        Path reportPath = Path.of(reportFile);
        Path parent = reportPath.getParent();
        if (parent != null) {
            fs.createDirectories(parent);
        }
        fs.writeString(reportPath, content);
    }

    private static final class JdkFileSystemPort implements FileSystemPort {
        @Override
        public String readString(Path path) throws IOException {
            return Files.readString(path);
        }

        @Override
        public boolean exists(Path path) {
            return Files.exists(path);
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            return Files.deleteIfExists(path);
        }

        @Override
        public void createDirectories(Path path) throws IOException {
            Files.createDirectories(path);
        }

        @Override
        public void writeString(Path path, String content) throws IOException {
            Files.writeString(path, content);
        }
    }

    private enum WriteAction {
        WRITTEN,
        SKIPPED,
        DRY_RUN,
        CONFLICT
    }

    private static WriteAction writeGeneratedSource(
        CodegenCliParser.CliOptions config,
        FileSystemPort fs,
        Path pkgDir,
        Path javaFile,
        String source
    ) throws IOException {
        if (config.dryRun()) {
            return WriteAction.DRY_RUN;
        }

        fs.createDirectories(pkgDir);

        boolean exists = fs.exists(javaFile);
        if (!exists) {
            fs.writeString(javaFile, source);
            return WriteAction.WRITTEN;
        }

        return switch (config.overwrite()) {
            case "always" -> {
                fs.writeString(javaFile, source);
                yield WriteAction.WRITTEN;
            }
            case "if-different" -> {
                String existing = fs.readString(javaFile);
                if (existing.equals(source)) {
                    yield WriteAction.SKIPPED;
                }
                fs.writeString(javaFile, source);
                yield WriteAction.WRITTEN;
            }
            case "never" -> WriteAction.CONFLICT;
            default -> throw new IllegalArgumentException("Unsupported overwrite policy: " + config.overwrite());
        };
    }

    private static boolean isUnsafeCleanOutputPath(Path outPath) {
        Path normalized = outPath.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            return true;
        }
        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        if (normalized.equals(home)) {
            return true;
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        return normalized.equals(cwd);
    }

    private static void writeManifestIfNeeded(
        CodegenCliParser.CliOptions config,
        FileSystemPort fs,
        String mode,
        String generatedAt,
        String toolVersion,
        String argsHash,
        List<FileEvent> fileEvents,
        int warningsCount,
        int writtenCount,
        int skippedCount,
        int conflictCount,
        int dryRunCount,
        boolean ok,
        String failReasonCode,
        int exitCode
    ) throws IOException {
        if (config.outputManifest() == null) {
            return;
        }
        Path manifestPath = Path.of(config.outputManifest());
        Path parent = manifestPath.getParent();
        if (parent != null) {
            fs.createDirectories(parent);
        }
        String payload;
        if ("ndjson".equals(config.manifestFormat())) {
            payload = manifestNdjson(
                mode,
                generatedAt,
                toolVersion,
                argsHash,
                fileEvents,
                warningsCount,
                writtenCount,
                skippedCount,
                conflictCount,
                dryRunCount,
                ok,
                failReasonCode,
                exitCode
            );
        } else {
            payload = manifestJson(
                mode,
                generatedAt,
                toolVersion,
                argsHash,
                fileEvents,
                warningsCount,
                writtenCount,
                skippedCount,
                conflictCount,
                dryRunCount,
                ok,
                failReasonCode,
                exitCode
            );
        }
        validateManifestIfRequested(config, payload);
        fs.writeString(manifestPath, payload);
    }

    private static String manifestJson(
        String mode,
        String generatedAt,
        String toolVersion,
        String argsHash,
        List<FileEvent> fileEvents,
        int warningsCount,
        int writtenCount,
        int skippedCount,
        int conflictCount,
        int dryRunCount,
        boolean ok,
        String failReasonCode,
        int exitCode
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
            .append("\"mode\":\"").append(ReportJsonWriter.escapeJson(mode)).append("\",")
            .append("\"generatedAt\":\"").append(ReportJsonWriter.escapeJson(generatedAt)).append("\",")
            .append("\"toolVersion\":\"").append(ReportJsonWriter.escapeJson(toolVersion)).append("\",")
            .append("\"argsHash\":\"").append(ReportJsonWriter.escapeJson(argsHash)).append("\",")
            .append("\"ok\":").append(ok).append(",")
            .append("\"failReasonCode\":");
        if (failReasonCode == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(ReportJsonWriter.escapeJson(failReasonCode)).append("\"");
        }
        sb.append(",")
            .append("\"exitCode\":").append(exitCode).append(",")
            .append("\"warningsCount\":").append(warningsCount).append(",")
            .append("\"writtenCount\":").append(writtenCount).append(",")
            .append("\"skippedCount\":").append(skippedCount).append(",")
            .append("\"conflictCount\":").append(conflictCount).append(",")
            .append("\"dryRunCount\":").append(dryRunCount).append(",")
            .append("\"files\":[");
        for (int i = 0; i < fileEvents.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            FileEvent e = fileEvents.get(i);
            sb.append("{\"action\":\"").append(ReportJsonWriter.escapeJson(e.action())).append("\",")
                .append("\"path\":\"").append(ReportJsonWriter.escapeJson(e.path())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String manifestNdjson(
        String mode,
        String generatedAt,
        String toolVersion,
        String argsHash,
        List<FileEvent> fileEvents,
        int warningsCount,
        int writtenCount,
        int skippedCount,
        int conflictCount,
        int dryRunCount,
        boolean ok,
        String failReasonCode,
        int exitCode
    ) {
        StringBuilder sb = new StringBuilder();
        for (FileEvent e : fileEvents) {
            sb.append("{\"event\":\"file\",\"action\":\"")
                .append(ReportJsonWriter.escapeJson(e.action()))
                .append("\",\"path\":\"")
                .append(ReportJsonWriter.escapeJson(e.path()))
                .append("\"}\n");
        }
        sb.append("{\"event\":\"manifest-summary\",")
            .append("\"mode\":\"").append(ReportJsonWriter.escapeJson(mode)).append("\",")
            .append("\"generatedAt\":\"").append(ReportJsonWriter.escapeJson(generatedAt)).append("\",")
            .append("\"toolVersion\":\"").append(ReportJsonWriter.escapeJson(toolVersion)).append("\",")
            .append("\"argsHash\":\"").append(ReportJsonWriter.escapeJson(argsHash)).append("\",")
            .append("\"ok\":").append(ok).append(",")
            .append("\"failReasonCode\":");
        if (failReasonCode == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(ReportJsonWriter.escapeJson(failReasonCode)).append("\"");
        }
        sb.append(",")
            .append("\"exitCode\":").append(exitCode).append(",")
            .append("\"warningsCount\":").append(warningsCount).append(",")
            .append("\"writtenCount\":").append(writtenCount).append(",")
            .append("\"skippedCount\":").append(skippedCount).append(",")
            .append("\"conflictCount\":").append(conflictCount).append(",")
            .append("\"dryRunCount\":").append(dryRunCount)
            .append("}");
        return sb.toString();
    }

    private static void validateManifestIfRequested(CodegenCliParser.CliOptions config, String payload) {
        if (!config.reportSchemaCheck()) {
            return;
        }
        ManifestSchemaValidator.validate(config.manifestFormat(), payload);
    }

    private record FileEvent(String action, String path) {}
}
