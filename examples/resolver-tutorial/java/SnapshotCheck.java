package example.resolvers;

import java.nio.file.*;
import java.util.*;

/** File updates affect the next resolve, never a previously resolved snapshot. */
public class SnapshotCheck {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("resolver-snapshot-");
        Path data = directory.resolve("words.json");
        try {
            Files.writeString(data, "{\"schema\":\"ubnf.words/v1\",\"revision\":\"v1\",\"words\":[\"𠮷野\"]}");
            var properties = new com.google.gson.JsonObject(); properties.addProperty("path", "words.json");
            var resolver = new Resolvers.FileResolver();
            var old = resolver.resolve(properties, directory);
            Files.writeString(data, "{\"schema\":\"ubnf.words/v1\",\"revision\":\"v2\",\"words\":[\"東京\"]}");
            var fresh = resolver.resolve(properties, directory);
            if (!old.words().equals(List.of("𠮷野")) || !old.revision().equals("v1")
                    || !fresh.words().equals(List.of("東京")) || !fresh.revision().equals("v2")) throw new AssertionError("snapshot changed");
            if (!Main.parse("𠮷野-12", old).get("accepted").getAsBoolean()
                    || Main.parse("𠮷野-12", fresh).get("accepted").getAsBoolean()) throw new AssertionError("parse differs from snapshot");
            try { old.words().clear(); throw new AssertionError("mutable words"); }
            catch (UnsupportedOperationException expected) { /* immutable */ }
        } finally { Files.deleteIfExists(data); Files.deleteIfExists(directory); }
        System.out.println("Java snapshot isolation: OK");
    }
}
