package org.unlaxer.dsl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test-only propagation: JVM system properties are not inherited by child JVMs. */
public final class TestProcesses {
    private TestProcesses() {}

    /** The JVM running this test; children keep using it unless a CI temp directory is requested. */
    public static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * A launcher path for native fixtures that accept only an executable. Without
     * {@code unlaxer.test.tmpdir} this is the running JDK's own {@code java}, so local runs keep
     * their default; with it, the CI wrapper adds {@code java.io.tmpdir} and still runs this JDK
     * through {@code UNLAXER_TEST_JAVA} set by {@link #builder}.
     */
    public static String javaLauncher(Path repository) {
        if (System.getProperty("unlaxer.test.tmpdir") == null) return javaExecutable();
        return repository.resolve("scripts/ci/java-tmpdir/bin/java").toAbsolutePath().normalize().toString();
    }

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
        if (temporary != null) {
            builder.environment().put("TMPDIR", temporary);
            builder.environment().put("UNLAXER_TEST_JAVA", javaExecutable());
        }
        return builder;
    }
}
