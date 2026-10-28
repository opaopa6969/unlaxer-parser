package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PackageDocumentReaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final int LIMIT = 8 * 1024 * 1024;

    @Test public void documentsRejectBeforeOpenAndNameTheirSubject() throws Exception {
        for (String name : new String[] {"ubnf.json", "ubnf.lock.json"}) {
            String subject = name.equals("ubnf.json") ? "manifest" : "lock";
            Path file = temporary.getRoot().toPath().resolve(name);
            assertEquals("E-PACKAGE: missing " + subject + "; run unlaxer deps resolve --manifest ubnf.json",
                assertThrows(IllegalArgumentException.class, () -> UBNFPackageResolver.readPackageSource(file)).getMessage());
            Files.createDirectory(file);
            assertEquals("E-PACKAGE: " + subject + " is not a regular file",
                assertThrows(IllegalArgumentException.class, () -> UBNFPackageResolver.readPackageSource(file)).getMessage());
            Files.delete(file);
            try (var random = new RandomAccessFile(file.toFile(), "rw")) { random.setLength(LIMIT + 1L); }
            assertEquals("E-PACKAGE: " + subject + " exceeds 8 MiB",
                assertThrows(IllegalArgumentException.class, () -> UBNFPackageResolver.readPackageSource(file)).getMessage());
        }
        Path file = temporary.newFile().toPath();
        try (var random = new RandomAccessFile(file.toFile(), "rw")) { random.setLength(LIMIT + 1L); }
        assertEquals("large", assertThrows(IOException.class, () -> UBNFPackageResolver.readSource(file,
            LIMIT, "non-file", "large", path -> { fail("oversized source opened"); return null; })).getMessage());
    }

    @Test public void documentBoundIsInclusiveAndStopsGrowthAtOneExtraByte() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("ubnf.json");
        try (var random = new RandomAccessFile(file.toFile(), "rw")) { random.setLength(LIMIT); }
        assertEquals(LIMIT, UBNFPackageResolver.readPackageSource(file).length());
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
        assertEquals("large", assertThrows(IOException.class, () -> UBNFPackageResolver.readSource(file,
            LIMIT, "non-file", "large", path -> new UBNFPackageResolver.OpenedSource(growing, true, LIMIT))).getMessage());
        assertEquals(LIMIT + 1, growing.count);
        assertTrue(growing.closed);
    }

    @Test public void openedHandleIsCheckedBeforeReadingAndAlwaysClosed() throws Exception {
        Path file = temporary.newFile().toPath();
        for (boolean seekable : new boolean[] {false, true}) {
            var unread = new InputStream() {
                boolean closed;
                @Override public int read() { fail("rejected handle read"); return -1; }
                @Override public void close() { closed = true; }
            };
            String message = assertThrows(IOException.class, () -> UBNFPackageResolver.readSource(file,
                LIMIT, "non-file", "large", path -> new UBNFPackageResolver.OpenedSource(unread, seekable, LIMIT + 1L))).getMessage();
            assertEquals(seekable ? "large" : "non-file", message);
            assertTrue(unread.closed);
        }
    }

    @Test public void lockIoPrecedesManifestAndCustomReadersStayInjectable() throws Exception {
        Path folder = temporary.getRoot().toPath(), root = folder.resolve("root.ubnf");
        Files.createDirectory(folder.resolve("ubnf.json"));
        var disk = new UBNFPackageResolver(root, UBNFPackageResolver.diskReader());
        assertEquals("E-PACKAGE: missing lock; run unlaxer deps resolve --manifest ubnf.json",
            assertThrows(IllegalArgumentException.class, () -> disk.importPath(root, "pkg:team/layout")).getMessage());
        Files.delete(folder.resolve("ubnf.json"));
        // Neither document exists on disk: a trusted injected reader still supplies both.
        var calls = new java.util.ArrayList<String>();
        var custom = new UBNFPackageResolver(root, path -> {
            calls.add(path.getFileName().toString());
            return path.getFileName().toString().equals("ubnf.json")
                ? "{\"schemaVersion\":1,\"dependencies\":{}}"
                : "{\"schemaVersion\":1,\"roots\":{},\"packages\":{}}";
        });
        assertEquals("E-PACKAGE: undeclared package dependency: team/layout",
            assertThrows(IllegalArgumentException.class, () -> custom.importPath(root, "pkg:team/layout")).getMessage());
        assertEquals(java.util.List.of("ubnf.lock.json", "ubnf.json"), calls);
    }
}
