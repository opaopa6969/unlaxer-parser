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
        assertEquals(LIMIT, UBNFPackageResolver.readLocalSource(file, path -> exact).length());
        assertEquals(LIMIT, exact.count);
        assertTrue(exact.closed);
        // The stat still sees LIMIT; the opened stream has grown and never reaches EOF.
        var growing = new CountingInput(Long.MAX_VALUE);
        var failure = assertThrows(IOException.class, () -> UBNFPackageResolver.readLocalSource(file, path -> growing));
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
