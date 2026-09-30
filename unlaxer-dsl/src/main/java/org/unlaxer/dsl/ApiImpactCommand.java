package org.unlaxer.dsl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.unlaxer.dsl.impact.ApiImpact;

/** Reads exactly two grammar files and prints a report; never saves generated files. */
final class ApiImpactCommand {
    private static final String HELP = "Usage: impact --target java|rust --before <old.ubnf> --after <new.ubnf> [--format json]";
    private ApiImpactCommand() {}
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 2 && args[1].equals("--help")) { out.println(HELP); return 0; }
        Map<String, String> options = new HashMap<>();
        try {
            for (int i = 1; i < args.length; i++) {
                String key = args[i];
                if (!Set.of("--target", "--before", "--after", "--format").contains(key)
                    || i + 1 == args.length || args[i + 1].startsWith("--")) throw new IllegalArgumentException(HELP);
                String value = args[++i];
                if (value.isEmpty() || options.putIfAbsent(key, value) != null) throw new IllegalArgumentException(HELP);
            }
            if (!Set.of("java", "rust").contains(options.getOrDefault("--target", ""))
                || !options.containsKey("--before") || !options.containsKey("--after")
                || !options.getOrDefault("--format", "json").equals("json")) throw new IllegalArgumentException(HELP);
        } catch (IllegalArgumentException error) { err.println(error.getMessage()); return 2; }
        try {
            String before = Files.readString(Path.of(options.get("--before")));
            String after = Files.readString(Path.of(options.get("--after")));
            var report = ApiImpact.compare(options.get("--target"), before, after);
            out.println(report.toJson());
            return report.ok() ? 0 : 3;
        } catch (IOException | InvalidPathException error) { err.println("I/O error: " + error.getMessage()); return 4; }
    }
}
