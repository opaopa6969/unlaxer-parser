package org.unlaxer.dsl;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Checks the fork's actual temp files and a real child, including exact stderr. */
public class CiTempDirectoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void forkAndChildUseTheSelectedDirectoryWithoutLauncherNoise() throws Exception {
        Path expected = Path.of(System.getProperty("unlaxer.test.tmpdir",
            System.getProperty("java.io.tmpdir"))).toRealPath();
        assertEquals(expected, Path.of(System.getProperty("java.io.tmpdir")).toRealPath());
        assertEquals(expected, temporary.getRoot().toPath().toRealPath().getParent());
        Path directory = Files.createTempDirectory("ci-temp-fork-");
        try {
            assertEquals(expected, directory.toRealPath().getParent());
            Path stdout = temporary.newFile("stdout").toPath();
            Path stderr = temporary.newFile("stderr").toPath();
            var child = TestProcesses.builder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            try {
                assertTrue("child timeout", child.waitFor(20, TimeUnit.SECONDS));
                assertEquals(Files.readString(stderr), 0, child.exitValue());
                assertEquals("", Files.readString(stderr));
                assertEquals(expected, Path.of(Files.readString(stdout).lines().findFirst().orElseThrow()).toRealPath());
            } finally {
                if (child.isAlive()) child.destroyForcibly();
            }
        } finally {
            Files.delete(directory);
        }
    }

    /** Native fixtures get a launcher path: the running JDK by default, the wrapper only on request. */
    @Test public void fixtureLauncherKeepsTheRunningJdk() throws Exception {
        Path repository = Path.of("..").toAbsolutePath().normalize();
        String launcher = TestProcesses.javaLauncher(repository);
        String requested = System.getProperty("unlaxer.test.tmpdir");
        if (requested == null) {
            assertEquals(Path.of(System.getProperty("java.home"), "bin", "java").toString(), launcher);
            return;
        }
        assertEquals(repository.resolve("scripts/ci/java-tmpdir/bin/java").toString(), launcher);
        Path stdout = temporary.newFile("launcher-stdout").toPath();
        Path stderr = temporary.newFile("launcher-stderr").toPath();
        // No java.io.tmpdir argument here: the wrapper must add it and must not switch to PATH's JDK.
        var child = new ProcessBuilder(launcher, "-cp", System.getProperty("java.class.path"), Probe.class.getName());
        child.environment().putAll(TestProcesses.builder("true").environment());
        var process = child.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertTrue("launcher timeout", process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(Files.readString(stderr), 0, process.exitValue());
            assertEquals("", Files.readString(stderr));
            var lines = Files.readString(stdout).lines().toList();
            assertEquals(Path.of(requested).toRealPath(), Path.of(lines.get(0)).toRealPath());
            assertEquals(Path.of(System.getProperty("java.home")).toRealPath(), Path.of(lines.get(1)).toRealPath());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Path directory = Files.createTempDirectory("ci-temp-child-");
            try { System.out.print(directory.toRealPath().getParent() + "\n" + System.getProperty("java.home")); }
            finally { Files.delete(directory); }
        }
    }
}
