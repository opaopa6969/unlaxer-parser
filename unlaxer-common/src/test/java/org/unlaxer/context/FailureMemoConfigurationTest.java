package org.unlaxer.context;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Constructor-level configuration is process-local; no shared System properties are mutated. */
public class FailureMemoConfigurationTest {
    @Test
    public void sharedConfigurationFixtureUsesFreshProcesses() throws Exception {
        Path fixture = Path.of("../conformance/failure-memo-budget/configuration.tsv");
        int observed = 0;
        for (String line : Files.readAllLines(fixture)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] row = line.split("\t", -1);
            assertEquals(line, 5, row.length);
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
            if (!row[1].equals("-")) {
                String value = new String(HexFormat.of().parseHex(row[1]), StandardCharsets.UTF_8);
                for (String property : List.of("maxEntries", "maxBytes", "maxEntryBytes"))
                    command.add("-Dunlaxer.memo.failure." + property + "=" + value);
            }
            command.add("-cp");
            command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
            command.add(FailureMemoConfigurationTest.class.getName());
            command.addAll(List.of(row[2], row[3], row[4]));
            Path log = Files.createTempFile("failure-memo-configuration-", ".log");
            Process child = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
                for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
                    builder.environment().remove(key);
                child = builder.start();
                assertTrue(row[0] + " timed out", child.waitFor(15, TimeUnit.SECONDS));
                assertEquals(row[0] + ": " + Files.readString(log), 0, child.exitValue());
                assertTrue(row[0], Files.readString(log).contains("CONFIG_OK"));
                observed++;
            } finally {
                if (child != null && child.isAlive()) {
                    child.destroyForcibly();
                    child.waitFor(5, TimeUnit.SECONDS);
                }
                Files.deleteIfExists(log);
            }
        }
        assertEquals(25, observed);
    }

    private static long field(PackratMemoTable table, String name) throws Exception {
        Field field = PackratMemoTable.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((Number)field.get(table)).longValue();
    }

    public static void main(String[] args) throws Exception {
        PackratMemoTable table = new PackratMemoTable();
        assertEquals(Long.parseLong(args[0]), field(table, "maxFailureEntries"));
        assertEquals(Long.parseLong(args[1]), field(table, "maxFailureBytes"));
        assertEquals(Long.parseLong(args[2]), field(table, "maxFailureEntryBytes"));
        assertEquals(65536, field(table, "maxSuccessEntries"));
        assertEquals(16L * 1024 * 1024, field(table, "maxSuccessBytes"));
        assertEquals(2L * 1024 * 1024, field(table, "maxSuccessEntryBytes"));
        assertEquals(Memoization.OFF, ParseOptions.defaults().memoization());
        assertEquals(ParseOptions.Diagnostics.AUTO, ParseOptions.defaults().diagnostics());
        System.out.println("CONFIG_OK");
    }
}
