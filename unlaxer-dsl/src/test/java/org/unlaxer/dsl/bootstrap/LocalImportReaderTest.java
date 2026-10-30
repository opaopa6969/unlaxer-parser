package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalImportReaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final int LIMIT = 64 * 1024 * 1024;

    @Test public void rejectsBeforeOpeningNonFilesAndOversizedFiles() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var nonFile = assertThrows(IOException.class, () -> UBNFPackageResolver.readLocalSource(directory,
            path -> { fail("non-file opened"); return null; }));
        assertEquals("E-MODULE: local import is not a regular file", nonFile.getMessage());
        Path file = temporary.newFile().toPath();
        try (var random = new RandomAccessFile(file.toFile(), "rw")) { random.setLength(LIMIT + 1L); }
        var oversized = assertThrows(IOException.class, () -> UBNFPackageResolver.readLocalSource(file,
            path -> { fail("oversized file opened"); return null; }));
        assertEquals("E-MODULE: local import exceeds 64 MiB", oversized.getMessage());
    }

    @Test public void exactLimitIsReadAndGrowthAfterStatStopsAtOneExtraByte() throws Exception {
        Path file = temporary.newFile().toPath();
        try (var random = new RandomAccessFile(file.toFile(), "rw")) { random.setLength(LIMIT); }
        assertEquals(LIMIT, UBNFPackageResolver.readLocalSource(file).length());
        var exact = new CountingInput(LIMIT);
        assertEquals(LIMIT, UBNFPackageResolver.readLocalSource(file, path -> new UBNFPackageResolver.OpenedSource(exact, true, LIMIT)).length());
        assertEquals(LIMIT, exact.count);
        assertTrue(exact.closed);
        // The stat still sees LIMIT; the opened stream has grown and never reaches EOF.
        var growing = new CountingInput(Long.MAX_VALUE);
        var failure = assertThrows(IOException.class, () -> UBNFPackageResolver.readLocalSource(file, path -> new UBNFPackageResolver.OpenedSource(growing, true, LIMIT)));
        assertEquals("E-MODULE: local import exceeds 64 MiB", failure.getMessage());
        assertEquals(LIMIT + 1L, growing.count);
        assertTrue(growing.closed);
    }

    @Test public void utf8RemainsStrictAndSymlinksUseTheirTarget() throws Exception {
        Path file = temporary.newFile().toPath();
        Files.writeString(file, "grammar Module { }\n");
        Path link = temporary.getRoot().toPath().resolve("source-link");
        Files.createSymbolicLink(link, file);
        assertEquals(Files.readString(file), UBNFPackageResolver.readLocalSource(link));
        Files.write(file, new byte[] {(byte) 0xc3, 0x28});
        assertThrows(java.nio.charset.CharacterCodingException.class, () -> UBNFPackageResolver.readLocalSource(link));
    }

    @Test(timeout=15000) public void openedReplacementRejectsWriterFifoSocketAndOversizedFile() throws Exception {
        Path file = temporary.newFile().toPath();
        Path pipe = temporary.getRoot().toPath().resolve("pipe");
        Process make = new ProcessBuilder("mkfifo", pipe.toString()).start();
        assertTrue(make.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, make.exitValue());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "replacement-test"); thread.setDaemon(true); return thread;
        });
        // O_RDWR keeps a writer connected, but supplies no bytes. A reader must reject
        // the opened FIFO before read; mutations fail by deadline rather than hanging Maven.
        try (var writer = java.nio.channels.FileChannel.open(pipe, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertTrue(writer.isOpen());
            var future = executor.submit(() -> assertThrows(IOException.class,
                () -> UBNFPackageResolver.readLocalSource(file, path -> UBNFPackageResolver.openSource(pipe))));
            try {
                assertEquals("E-MODULE: local import is not a regular file",
                    future.get(5, java.util.concurrent.TimeUnit.SECONDS).getMessage());
            } finally { future.cancel(true); }
        } finally { executor.shutdownNow(); }
        assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        try (var socket = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            Path endpoint = temporary.getRoot().toPath().resolve("s");
            socket.bind(java.net.UnixDomainSocketAddress.of(endpoint));
            assertEquals("E-MODULE: local import is not a regular file", assertThrows(IOException.class,
                () -> UBNFPackageResolver.readLocalSource(file, path -> {
                    Files.delete(path); Files.createSymbolicLink(path, endpoint);
                    return UBNFPackageResolver.openSource(path);
                })).getMessage());
        }
        Files.delete(file); Files.createFile(file);
        Path large = temporary.newFile().toPath();
        try (var random = new RandomAccessFile(large.toFile(), "rw")) { random.setLength(LIMIT + 1L); }
        assertEquals("E-MODULE: local import exceeds 64 MiB", assertThrows(IOException.class,
            () -> UBNFPackageResolver.readLocalSource(file, path -> UBNFPackageResolver.openSource(large))).getMessage());
    }

    private static final class CountingInput extends InputStream {
        final long length;
        long count;
        boolean closed;
        CountingInput(long length) { this.length = length; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0];
        }
        @Override public int read(byte[] bytes, int offset, int size) throws IOException {
            if (size == 0) return 0;
            if (count == length) return -1;
            int n = (int) Math.min(size, length - count);
            if (count + n > LIMIT + 1L) throw new IOException("reader exceeded limit + 1");
            Arrays.fill(bytes, offset, offset + n, (byte) 'a');
            count += n;
            return n;
        }
        @Override public void close() { closed = true; }
    }
}
