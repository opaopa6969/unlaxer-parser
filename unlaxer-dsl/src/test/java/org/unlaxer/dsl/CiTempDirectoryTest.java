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
                assertEquals(expected, Path.of(Files.readString(stdout)).toRealPath());
            } finally {
                if (child.isAlive()) child.destroyForcibly();
            }
        } finally {
            Files.delete(directory);
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Path directory = Files.createTempDirectory("ci-temp-child-");
            try { System.out.print(directory.toRealPath().getParent()); }
            finally { Files.delete(directory); }
        }
    }
}
