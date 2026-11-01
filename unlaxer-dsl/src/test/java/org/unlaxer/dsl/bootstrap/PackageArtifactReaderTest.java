package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Raw bytes and deterministic opener replacements exercise the shared disk reader. */
public class PackageArtifactReaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final int LIMIT = 8 * 1024 * 1024;
    private static final String MISSING = "E-PACKAGE: missing artifact; run unlaxer deps resolve --manifest ubnf.json";
    private static final String LARGE = "E-PACKAGE: artifact exceeds 8 MiB";

    @Test public void artifactsRejectBeforeOpenWithStableDiagnostics() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("artifact");
        UBNFPackageResolver.LocalInput unopened = path -> { fail("rejected artifact opened"); return null; };
        for (boolean cached : new boolean[] {true, false}) {
            assertEquals(cached ? MISSING : "E-PACKAGE: missing local artifact", assertThrows(IllegalArgumentException.class,
                () -> UBNFPackageResolver.readArtifact(file, cached, unopened)).getMessage());
            Files.createDirectory(file);
            assertEquals(cached ? MISSING : "E-PACKAGE: artifact is not a regular file",
                assertThrows(IllegalArgumentException.class,
                    () -> UBNFPackageResolver.readArtifact(file, cached, unopened)).getMessage());
            Files.delete(file);
            try (var random = new RandomAccessFile(file.toFile(), "rw")) { random.setLength(LIMIT + 1L); }
            assertEquals(LARGE, assertThrows(IllegalArgumentException.class,
                () -> UBNFPackageResolver.readArtifact(file, cached, unopened)).getMessage());
            Files.delete(file);
        }
    }

    @Test public void artifactBytesRemainExactAndGrowthStopsAtLimitPlusOne() throws Exception {
        Path file = temporary.newFile().toPath();
        byte[] raw = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', (byte) 0xff, '}'};
        Files.write(file, raw);
        for (boolean cached : new boolean[] {true, false}) {
            assertArrayEquals(raw, UBNFPackageResolver.readArtifact(file, cached, UBNFPackageResolver::openSource));
            try (var random = new RandomAccessFile(file.toFile(), "rw")) { random.setLength(LIMIT); }
            assertEquals(LIMIT, UBNFPackageResolver.readArtifact(file, cached, UBNFPackageResolver::openSource).length);
            var growing = new InputStream() {
                int count;
                boolean closed;
                @Override public int read() { fail("bulk reads expected"); return -1; }
                @Override public int read(byte[] bytes, int offset, int size) {
                    assertTrue("read past limit + 1", count + size <= LIMIT + 1);
                    java.util.Arrays.fill(bytes, offset, offset + size, (byte) 'a');
                    count += size;
                    return size;
                }
                @Override public void close() { closed = true; }
            };
            assertEquals(LARGE, assertThrows(IllegalArgumentException.class,
                () -> UBNFPackageResolver.readArtifact(file, cached,
                    path -> new UBNFPackageResolver.OpenedSource(growing, true, LIMIT))).getMessage());
            assertEquals(LIMIT + 1, growing.count);
            assertTrue(growing.closed);
            Files.write(file, raw);
        }
    }

    @Test public void artifactHandleChecksAndIoFailuresCloseTheStream() throws Exception {
        Path file = temporary.newFile().toPath();
        for (boolean cached : new boolean[] {true, false}) {
            for (boolean seekable : new boolean[] {false, true}) {
                var unread = new InputStream() {
                    boolean closed;
                    @Override public int read() { fail("rejected handle read"); return -1; }
                    @Override public void close() { closed = true; }
                };
                assertEquals(seekable ? LARGE : cached ? MISSING : "E-PACKAGE: artifact is not a regular file",
                    assertThrows(IllegalArgumentException.class, () -> UBNFPackageResolver.readArtifact(file, cached,
                        path -> new UBNFPackageResolver.OpenedSource(unread, seekable, LIMIT + 1L))).getMessage());
                assertTrue(unread.closed);
            }
            var broken = new InputStream() {
                boolean closed;
                @Override public int read() throws IOException { throw new IOException("private path detail"); }
                @Override public void close() { closed = true; }
            };
            assertEquals(cached ? "E-PACKAGE: cannot read artifact" : "E-PACKAGE: cannot read local artifact",
                assertThrows(IllegalArgumentException.class,
                () -> UBNFPackageResolver.readArtifact(file, cached,
                    path -> new UBNFPackageResolver.OpenedSource(broken, true, 0))).getMessage());
            assertTrue(broken.closed);
        }
    }

    @Test(timeout=20000) public void artifactReplacementRejectsWriterFifoBeforeReading() throws Exception {
        Path file = temporary.newFile().toPath();
        Path pipe = temporary.getRoot().toPath().resolve("pipe");
        Process make = org.unlaxer.dsl.TestProcesses.builder("mkfifo", pipe.toString()).start();
        try {
            assertTrue(make.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, make.exitValue());
        } finally { make.destroyForcibly(); }
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "artifact-replacement-test"); thread.setDaemon(true); return thread;
        });
        try (var writer = java.nio.channels.FileChannel.open(pipe, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertTrue(writer.isOpen());
            for (boolean cached : new boolean[] {true, false}) {
                var future = executor.submit(() -> assertThrows(IllegalArgumentException.class,
                    () -> UBNFPackageResolver.readArtifact(file, cached, path -> UBNFPackageResolver.openSource(pipe))));
                try {
                    assertEquals(cached ? MISSING : "E-PACKAGE: artifact is not a regular file",
                        future.get(5, java.util.concurrent.TimeUnit.SECONDS).getMessage());
                } finally { future.cancel(true); }
            }
        } finally { executor.shutdownNow(); }
        assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
    }
}
