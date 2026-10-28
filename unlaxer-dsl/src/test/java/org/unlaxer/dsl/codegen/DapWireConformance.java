package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.unlaxer.dsl.codegen.ClassicDapProtocolConformanceTest.Wire;

/** #945: compare complete messages in arrival order; never search the event buffer. */
final class DapWireConformance {
    static void check(Wire wire, String route, String backend, Path directory) throws Exception {
        var saved = wire.failures; wire.failures = null;
        try { run(wire, route, backend, directory); } finally { wire.failures = saved; }
    }
    private static void run(Wire wire, String route, String backend, Path directory) throws Exception {
        var fixture = JsonParser.parseString(Files.readString(Path.of("../docs/fixtures/dap-failures/wire.json"))).getAsJsonObject().getAsJsonObject("routes").getAsJsonObject(route);
        assertEquals(Map.of("classic-ast", 90, "classic-token", 146, "generated", 120, "projected", 120).get(route).intValue(), fixture.getAsJsonArray("requests").size());
        var ids = new HashSet<String>();
        var paths = new LinkedHashMap<String,String>();
        paths.put("$PROGRAM", directory.resolve("wire-program.txt").toString());
        paths.put("$MISSING", directory.resolve("wire-missing.txt").toString());
        paths.put("$DIRECTORY", directory.resolve("wire-directory").toString());
        paths.put("$INVALID_UTF8", directory.resolve("wire-invalid-utf8.txt").toString());
        paths.put("$LOOP", directory.resolve("wire-loop").toString());
        Files.writeString(Path.of(paths.get("$PROGRAM")), fixture.get("input").getAsString());
        assertFalse(Files.exists(Path.of(paths.get("$MISSING"))));
        Files.createDirectories(Path.of(paths.get("$DIRECTORY")));
        Files.write(Path.of(paths.get("$INVALID_UTF8")), new byte[]{(byte)0xff});
        // ELOOP; with "$PROGRAM//child/" (ENOTDIR) and a 300-byte name (ENAMETOOLONG) these use
        // Java's UnixException text, and "$PROGRAM/" shows Path.of dropping a trailing slash.
        Files.deleteIfExists(Path.of(paths.get("$LOOP")));
        Files.createSymbolicLink(Path.of(paths.get("$LOOP")), Path.of("wire-loop"));
        var evidence = new ArrayList<String>(); var raw = new ArrayList<String>();
        Path out = Path.of("target/dap-wire-"+route+"-"+backend);
        Files.deleteIfExists(Path.of(out+".tsv"));
        try {
            assertTrue("leftover buffered messages: "+wire.pending, wire.pending.isEmpty());
            for (var item : fixture.getAsJsonArray("requests")) {
                var row = item.getAsJsonObject(); String id = row.get("id").getAsString();
                assertTrue("duplicate fixture id: "+id, ids.add(id));
                String command = row.get("command").getAsString();
                if (row.has("programBytes")) Files.write(Path.of(paths.get("$INVALID_UTF8")),
                    HexFormat.of().parseHex(row.get("programBytes").getAsString()));
                String args = row.get("arguments").toString();
                for (var path : paths.entrySet()) args = args.replace(path.getKey(), path.getValue());
                int seq = wire.sendOrdered(command, JsonParser.parseString(args), true, true);
                raw.add(id+"\tsend\t"+wire.lastPayload);
                var expected = row.getAsJsonArray(backend.startsWith("rust") && row.has("rustMessages") ? "rustMessages" : "messages");
                var messages = new JsonArray(); int responses = 0;
                // Read exactly the expected messages, without selecting by event/response type.
                // In particular, finish initialize before sending any further request (DAP).
                for (int i = 0; i < expected.size(); i++) {
                    var message = wire.read(); raw.add(id+"\treceive\t"+message);
                    if (message.get("type").getAsString().equals("response")) {
                        assertEquals(message.toString(),seq,message.get("request_seq").getAsInt());
                        assertEquals(command,message.get("command").getAsString()); responses++;
                    }
                    var normalized = message.deepCopy(); normalized.remove("seq"); normalized.remove("request_seq");
                    String text = normalized.toString();
                    for (var path : paths.entrySet()) text = text.replace(path.getValue(),path.getKey());
                    messages.add(JsonParser.parseString(text));
                }
                int barrier = wire.sendOrdered("threads", Map.of(), true, true);
                raw.add(id+"\tbarrier-send\t"+wire.lastPayload);
                var reply = wire.read(); raw.add(id+"\tbarrier-receive\t"+reply);
                assertEquals("unexpected extra message: "+reply, "response", reply.get("type").getAsString());
                assertEquals(barrier, reply.get("request_seq").getAsInt());
                assertEquals("threads", reply.get("command").getAsString());
                assertTrue(reply.toString(), reply.get("success").getAsBoolean());
                assertEquals(id,1,responses);
                evidence.add(id+"\t"+messages);
                assertEquals(route+"/"+backend+" "+id, expected, messages);
            }
            Files.write(Path.of(out+".tsv"),evidence);
        } finally { Files.write(Path.of(out+"-raw.tsv"),raw); }
    }
}
