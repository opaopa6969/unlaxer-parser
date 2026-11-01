package org.unlaxer.dsl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test-only propagation: JVM system properties are not inherited by child JVMs. */
public final class TestProcesses {
    private TestProcesses() {}

    public static ProcessBuilder builder(String... command) {
        return builder(List.of(command));
    }

    public static ProcessBuilder builder(List<String> command) {
        var args = new ArrayList<>(command);
        String temporary = System.getProperty("unlaxer.test.tmpdir");
        if (temporary != null && !args.isEmpty()) {
            String executable = Path.of(args.get(0)).getFileName().toString();
            if (executable.equals("java") || executable.equals("java.exe")) {
                args.add(1, "-Djava.io.tmpdir=" + temporary);
            } else if (executable.equals("javac") || executable.equals("javac.exe")) {
                args.add(1, "-J-Djava.io.tmpdir=" + temporary);
            }
        }
        var builder = new ProcessBuilder(args);
        if (temporary != null) builder.environment().put("TMPDIR", temporary);
        return builder;
    }
}
