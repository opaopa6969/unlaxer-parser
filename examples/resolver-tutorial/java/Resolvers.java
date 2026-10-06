package example.resolvers;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Tutorial-owned interfaces; not a standard resolver API in unlaxer-core. */
public final class Resolvers {
    public static final String SCHEMA = "ubnf.words/v1";
    public static final int LIMIT = 1_048_576;
    public record Snapshot(String revision, List<String> words) {
        public Snapshot { words = List.copyOf(words); }
    }
    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }
    @FunctionalInterface public interface Resolver {
        Snapshot resolve(JsonObject properties, Path configDirectory) throws Failure;
    }
    public static final class InlineResolver implements Resolver {
        @Override public Snapshot resolve(JsonObject properties, Path directory) throws Failure {
            return snapshot(properties);
        }
    }
    public static final class FileResolver implements Resolver {
        @Override public Snapshot resolve(JsonObject properties, Path directory) throws Failure {
            fields(properties, Set.of("path"), "E-CONFIG");
            return snapshot(object(read(directory.resolve(string(properties, "path", "E-CONFIG")), "E-DATA"), "E-DATA"));
        }
    }
    public static Snapshot load(Path configuration) throws Failure {
        return load(configuration, Map.of("inline/v1", new InlineResolver(), "file/v1", new FileResolver()));
    }
    public static Snapshot load(Path configuration, Map<String, Resolver> registry) throws Failure {
        JsonObject config = object(read(configuration, "E-CONFIG"), "E-CONFIG");
        fields(config, Set.of("version", "bindings", "resolvers"), "E-CONFIG");
        if (!config.get("version").isJsonPrimitive() || !config.getAsJsonPrimitive("version").isNumber()
                || !config.get("version").getAsString().equals("1")) throw new Failure("E-CONFIG");
        var bindings = object(config.get("bindings"), "E-CONFIG");
        if (!bindings.has(TownParser.BINDING)) throw new Failure("E-BINDING");
        var binding = object(bindings.get(TownParser.BINDING), "E-CONFIG");
        fields(binding, Set.of("resolver", "schema"), "E-CONFIG");
        if (!SCHEMA.equals(string(binding, "schema", "E-CONFIG"))) throw new Failure("E-CONFIG");
        String name = string(binding, "resolver", "E-CONFIG");
        var definitions = object(config.get("resolvers"), "E-CONFIG");
        if (!definitions.has(name)) throw new Failure("E-RESOLVER");
        var definition = object(definitions.get(name), "E-CONFIG");
        fields(definition, Set.of("provider", "properties"), "E-CONFIG");
        String provider = string(definition, "provider", "E-CONFIG");
        Resolver resolver = registry.get(provider);
        if (resolver == null) throw new Failure("E-PROVIDER");
        return resolver.resolve(object(definition.get("properties"), "E-CONFIG"), configuration.toAbsolutePath().getParent());
    }
    static Snapshot snapshot(JsonObject data) throws Failure {
        fields(data, Set.of("schema", "revision", "words"), "E-DATA");
        if (!SCHEMA.equals(string(data, "schema", "E-DATA"))) throw new Failure("E-DATA");
        String revision = string(data, "revision", "E-DATA");
        if (!data.get("words").isJsonArray()) throw new Failure("E-DATA");
        List<String> words = new ArrayList<>();
        for (JsonElement value : data.getAsJsonArray("words")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new Failure("E-DATA");
            String word = value.getAsString();
            if (word.isEmpty()) throw new Failure("E-DATA");
            words.add(word);
        }
        return new Snapshot(revision, words);
    }
    static void fields(JsonObject object, Set<String> keys, String code) throws Failure {
        if (!object.keySet().equals(keys)) throw new Failure(code);
    }
    static String string(JsonObject object, String key, String code) throws Failure {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty()) throw new Failure(code);
        return value.getAsString();
    }
    static JsonObject object(JsonElement value, String code) throws Failure {
        if (value == null || !value.isJsonObject()) throw new Failure(code);
        return value.getAsJsonObject();
    }
    static JsonElement read(Path path, String code) throws Failure {
        byte[] bytes;
        try (var stream = Files.newInputStream(path)) { bytes = stream.readNBytes(LIMIT + 1); }
        catch (IOException e) { throw new Failure("E-IO"); }
        if (bytes.length > LIMIT) throw new Failure(code);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            try (var reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false);
                JsonElement value = new Gson().getAdapter(JsonElement.class).read(reader);
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new Failure(code);
                scalars(value, code);
                return value;
            }
        } catch (IOException | RuntimeException e) { throw new Failure(code); }
    }
    static void scalars(JsonElement value, String code) throws Failure {
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                scalarString(entry.getKey(), code); scalars(entry.getValue(), code);
            }
        } else if (value.isJsonArray()) {
            for (var item : value.getAsJsonArray()) scalars(item, code);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            scalarString(value.getAsString(), code);
        }
    }
    static void scalarString(String value, String code) throws Failure {
        if (value.codePoints().anyMatch(cp -> cp >= 0xd800 && cp <= 0xdfff)) throw new Failure(code);
    }
}
