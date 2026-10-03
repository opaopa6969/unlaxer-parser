package org.unlaxer.dsl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import org.unlaxer.dsl.codegen.rust.PlaygroundGenerator;

/** New-directory-only generation: grammar text cannot overwrite user files. */
final class PlaygroundCommand {
    private PlaygroundCommand() {}
    private static final String USAGE = "Usage: playground --grammar <file.ubnf> --output <new-directory> [--check]";

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 2 && args[1].equals("--help")) { out.println(USAGE); return 0; }
        Map<String, String> options = new HashMap<>();
        boolean check = false;
        try {
            for (int i = 1; i < args.length; i++) {
                String option = args[i];
                if (option.equals("--check")) {
                    if (check) throw new IllegalArgumentException("duplicate --check");
                    check = true; continue;
                }
                if ((!option.equals("--grammar") && !option.equals("--output")) || i + 1 == args.length
                    || args[i + 1].isBlank() || args[i + 1].startsWith("--")) throw new IllegalArgumentException(USAGE);
                if (options.putIfAbsent(option, args[++i]) != null) throw new IllegalArgumentException("duplicate " + option);
            }
            if (options.size() != 2) throw new IllegalArgumentException(USAGE);
        } catch (IllegalArgumentException e) { err.println(e.getMessage()); return 2; }
        try {
            var files = PlaygroundGenerator.generate(Path.of(options.get("--grammar")));
            Path given = Path.of(options.get("--output")).toAbsolutePath();
            rejectLinks(given);
            Path output = given.normalize();
            rejectLinks(output);
            if (check) {
                for (var entry : files.entrySet()) {
                    Path file = output.resolve(entry.getKey()); rejectLinks(file);
                    if (!Files.isRegularFile(file) || !Files.readString(file).equals(entry.getValue())) throw new IOException("generated file differs: " + file);
                }
            } else {
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("output already exists; choose a new directory: " + output);
                Files.createDirectories(output.getParent());
                Path staging = Files.createTempDirectory(output.getParent(), ".ubnf-playground-");
                try {
                    for (var entry : files.entrySet()) {
                        Path file = staging.resolve(entry.getKey()); Files.createDirectories(file.getParent());
                        Files.writeString(file, entry.getValue(), StandardOpenOption.CREATE_NEW);
                    }
                    Files.move(staging, output);
                } catch (IOException | RuntimeException e) {
                    // Retain this task-owned staging directory on failure so errors never trigger broad deletion.
                    throw new IOException("Generation failed; partial staging retained at " + staging + ": " + e.getMessage(), e);
                }
            }
            out.println((check ? "Verified " : "Generated ") + files.size() + " playground files in " + output);
            out.println("Next: cd <output>, npm run build, npm start. Rust wasm32 target and Node 20+ are required.");
            return 0;
        } catch (IllegalArgumentException e) { err.println(e.getMessage()); return 3; }
        catch (IOException | IllegalStateException e) { err.println(e.getMessage()); return 4; }
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("refusing symbolic link " + cursor);
        }
    }
}
