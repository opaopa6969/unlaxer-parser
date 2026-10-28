package org.unlaxer.dsl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.GrammarValidator;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Experimental subcommand; the existing Java flag-based CLI stays unchanged. */
final class RustGenerateCommand {
    private RustGenerateCommand() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> options = new HashMap<>();
        boolean check = false;
        boolean lsp = false;
        boolean dap = false;
        boolean strict = false;
        try {
            for (int i = 1; i < args.length; i++) {
                String option = args[i];
                // Warnings fail generation (#715); --fail-on warning is the flag CLI's spelling.
                if (option.equals("--strict") || option.equals("--fail-on")) {
                    if (strict) throw new IllegalArgumentException("duplicate --strict / --fail-on");
                    if (option.equals("--fail-on") && (i + 1 == args.length || !args[++i].equals("warning")))
                        throw new IllegalArgumentException("--fail-on accepts only warning");
                    strict = true;
                    continue;
                }
                if (option.equals("--help")) {
                    out.println("Usage: generate --target rust --grammar <file.ubnf> --output <module-directory> [--check] [--lsp] [--dap] [--strict | --fail-on warning]");
                    return 0;
                }
                if (option.equals("--dap")) {
                    if (dap) throw new IllegalArgumentException("duplicate --dap");
                    dap = true;
                    continue;
                }
                if (option.equals("--lsp")) {
                    if (lsp) throw new IllegalArgumentException("duplicate --lsp");
                    lsp = true;
                    continue;
                }
                if (option.equals("--check")) {
                    if (check) throw new IllegalArgumentException("duplicate --check");
                    check = true;
                    continue;
                }
                if (!java.util.Set.of("--target", "--grammar", "--output").contains(option)
                    || i + 1 == args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("unknown or incomplete option " + option);
                }
                if (options.putIfAbsent(option, args[++i]) != null) throw new IllegalArgumentException("duplicate " + option);
            }
            if (!"rust".equals(options.get("--target")) || !options.containsKey("--grammar") || !options.containsKey("--output")) {
                throw new IllegalArgumentException("required: --target rust --grammar <file.ubnf> --output <directory>");
            }
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            return CodegenMain.EXIT_CLI_ERROR;
        }
        try {
            Path grammarPath = Path.of(options.get("--grammar")).toAbsolutePath().normalize();
            var snapshot = UBNFMapper.parseWithSource(Files.readString(grammarPath));
            var file = org.unlaxer.dsl.bootstrap.UBNFModuleLoader.resolve(snapshot, grammarPath, org.unlaxer.dsl.bootstrap.UBNFPackageResolver.diskReader());
            org.unlaxer.dsl.codegen.GrammarFileLayout.validate(file, snapshot);
            // Java class-resolution warnings do not define Rust token support: lowering
            // enforces an explicit Rust allowlist. Common grammar errors remain fatal.
            for (int index = 0; index < file.grammars().size(); index++) {
                var grammar = file.grammars().get(index);
                // Explicit "none" is part of the Rust backend contract, whereas Java's
                // common validator only accepts named trivia implementations. Validate it
                // as absent here; lowering still checks the original settings/duplicates.
                var validationGrammar = new org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl(
                    grammar.name(), grammar.imports(), grammar.settings().stream().filter(setting ->
                        !(setting.key().equals("whitespace")
                            && setting.value() instanceof org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue value
                            && value.value().equals("none"))).toList(), grammar.tokens(), grammar.rules());
                var errors = GrammarValidator.validateWithoutClassLoading(validationGrammar).stream()
                    .filter(issue -> "ERROR".equals(issue.severity())).map(GrammarValidator.ValidationIssue::format).toList();
                if (!errors.isEmpty()) throw org.unlaxer.dsl.codegen.GrammarFileLayout.failure(file, index, snapshot,
                    new IllegalArgumentException("Grammar validation failed:\n - " + String.join("\n - ", errors)));
            }
            var generated = new RustBackend().generateFile(file, lsp, dap, snapshot);
            // Errors come first; under --strict a warning fails before anything is written.
            var warnings = org.unlaxer.dsl.codegen.GenerationWarnings.collect(snapshot.source());
            if (!warnings.isEmpty()) err.println(org.unlaxer.dsl.codegen.GenerationWarnings.report(warnings, strict));
            if (strict && !warnings.isEmpty()) return CodegenMain.EXIT_STRICT_VALIDATION_ERROR;
            Path output = Path.of(options.get("--output"));
            if (Files.isSymbolicLink(output)) throw new IOException("output directory must not be a symbolic link: " + output);
            // Preflight every file before writing: never replace handwritten code or follow file symlinks.
            for (var artifact : generated) {
                Path destination = output.resolve(artifact.relativePath());
                validateGeneratedParents(output, destination);
                if (Files.isSymbolicLink(destination)) throw new IOException("refusing symbolic link " + destination);
                if (check) {
                    if (!Files.isRegularFile(destination) || !Files.readString(destination).equals(artifact.content())) {
                        throw new IOException("generated file differs: " + destination);
                    }
                } else if (Files.exists(destination)
                    && (!Files.isRegularFile(destination) || !Files.readString(destination).startsWith("// Generated by unlaxer RustBackend."))) {
                    throw new IOException("refusing to overwrite non-generated file " + destination);
                }
            }
            if (!check) writeArtifacts(output, generated);
            out.println((check ? "Verified " : "Generated ") + generated.size() + " Rust module files in " + output);
            return CodegenMain.EXIT_OK;
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            return CodegenMain.EXIT_VALIDATION_ERROR;
        } catch (IOException error) {
            err.println(error.getMessage());
            return CodegenMain.EXIT_GENERATION_ERROR;
        }
    }
    // Only generated descendants are owned by this command. Existing output ancestors are caller-owned.
    private static void validateGeneratedParents(Path output, Path destination) throws IOException {
        for (Path parent = destination.getParent(); parent != null && !parent.equals(output); parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IOException("refusing symbolic link " + parent);
            if (Files.exists(parent) && !Files.isDirectory(parent))
                throw new IOException("refusing non-directory " + parent);
        }
    }

    static void writeArtifacts(Path output, java.util.List<RustBackend.GeneratedFile> generated) throws IOException {
        for (var artifact : generated) {
            Path destination = output.resolve(artifact.relativePath());
            try {
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, artifact.content());
            } catch (IOException error) {
                throw new IOException("cannot write " + destination + ": " + error.getMessage(), error);
            }
        }
    }
}
