package org.unlaxer.dsl.bootstrap;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit resolution writes a pinned cache; module loading only verifies and reads it. */
public final class UBNFPackageResolver {
    public static final String MANIFEST = "ubnf.json";
    public static final String LOCK = "ubnf.lock.json";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    private final Path directory;
    private final Path virtualRoot;
    private final UBNFModuleLoader.SourceReader reader;
    private JsonObject lock;
    private long loadedBytes;
    private final Map<String, JsonObject> artifacts = new TreeMap<>();

    public UBNFPackageResolver(Path grammar, UBNFModuleLoader.SourceReader reader) {
        this.directory = grammar.toAbsolutePath().normalize().getParent();
        this.virtualRoot = directory.resolve(".ubnf-cache/sources");
        this.reader = reader;
    }

    private static IllegalArgumentException error(String message) {
        return new IllegalArgumentException("E-PACKAGE: " + message);
    }
    private static JsonObject object(JsonElement value, String subject) {
        if (value == null || !value.isJsonObject()) throw error("expected object: " + subject);
        return value.getAsJsonObject();
    }
    private static String string(JsonObject value, String name) {
        JsonElement field = value.get(name);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString())
            throw error("expected string: " + name);
        return field.getAsString();
    }
    private static void versioned(JsonObject value) {
        if (value.get("schemaVersion") == null || !value.get("schemaVersion").toString().equals("1")) throw error("unsupported schemaVersion");
    }
    private static void id(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9._-]*(/[A-Za-z][A-Za-z0-9._-]*)*")) throw error("invalid package ID");
    }
    private static void version(String value) {
        if (!value.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) throw error("exact package version required");
    }
    private static String file(String value) {
        if (value.isEmpty() || value.contains("\\") || value.startsWith("/") || value.contains(":"))
            throw error("invalid package file path");
        for (String part : value.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw error("package file path escapes boundary");
        return value;
    }
    private static JsonObject parse(String source) {
        if (source.length() > MAX_BYTES) throw error("document exceeds 8 MiB");
        try { return object(JSON.fromJson(source, JsonObject.class), "document"); }
        catch (JsonParseException exception) { throw error("invalid JSON"); }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static JsonObject artifact(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw error("artifact exceeds 8 MiB");
        JsonObject value;
        try { value = parse(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()); }
        catch (java.nio.charset.CharacterCodingException exception) { throw error("invalid UTF-8 artifact"); }
        versioned(value);
        id(string(value, "id")); version(string(value, "version"));
        JsonObject files = object(value.get("files"), "files");
        if (files.size() == 0 || files.size() > 128) throw error("package requires 1..128 files");
        for (var entry : files.entrySet()) {
            file(entry.getKey());
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                throw error("package file must contain source text");
        }
        String entry = file(string(value, "entry"));
        if (!entry.endsWith(".ubnf") || !files.has(entry)) throw error("missing UBNF package entry");
        dependencies(object(value.get("dependencies"), "dependencies"));
        return value;
    }
    private static void dependencies(JsonObject values) {
        for (var entry : values.entrySet()) {
            id(entry.getKey());
            JsonObject dependency = object(entry.getValue(), "dependency");
            version(string(dependency, "version"));
            string(dependency, "source");
        }
    }

    /** Resolve/update is the only operation that may retrieve dependency artifacts. */
    public static JsonObject resolve(Path manifestPath) throws IOException {
        Path manifest = manifestPath.toAbsolutePath().normalize();
        JsonObject configuration = parse(Files.readString(manifest));
        versioned(configuration);
        JsonObject roots = object(configuration.get("dependencies"), "dependencies");
        dependencies(roots);
        Map<String, JsonObject> packages = new TreeMap<>();
        Map<String, byte[]> blobs = new TreeMap<>();
        for (var root : roots.entrySet()) resolveOne(root.getKey(), root.getValue().getAsJsonObject(),
            manifest.getParent(), packages, blobs, new LinkedHashSet<>());
        if (packages.size() > 128 || blobs.values().stream().mapToLong(b -> b.length).sum() > 64L * 1024 * 1024)
            throw error("package graph exceeds cache limits");
        JsonObject lock = new JsonObject();
        JsonObject packageValues = new JsonObject();
        packages.forEach(packageValues::add);
        lock.add("packages", packageValues);
        JsonObject rootValues = new JsonObject();
        new TreeMap<String, JsonElement>(roots.asMap()).forEach((name, dependency) ->
            rootValues.addProperty(name, string(dependency.getAsJsonObject(), "version")));
        lock.add("roots", rootValues);
        lock.addProperty("schemaVersion", 1);
        Path cache = manifest.getParent().resolve(".ubnf-cache/packages");
        Files.createDirectories(cache);
        for (var blob : blobs.entrySet()) write(cache.resolve(blob.getKey() + ".json"), blob.getValue());
        write(manifest.getParent().resolve(LOCK), (JSON.toJson(lock) + "\n").getBytes(StandardCharsets.UTF_8));
        return lock;
    }
    private static void write(Path path, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".ubnf-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void resolveOne(String name, JsonObject dependency, Path sourceDirectory,
            Map<String, JsonObject> packages, Map<String, byte[]> blobs, Set<String> visiting) throws IOException {
        if (!visiting.add(name)) throw error("cyclic package dependency: " + name);
        if (visiting.size() > 64) throw error("package dependency depth exceeds 64");
        if (!packages.containsKey(name) && packages.size() >= 128) throw error("package count exceeds 128");
        String source = string(dependency, "source");
        byte[] bytes;
        Path childDirectory = sourceDirectory;
        if (source.equals("builtin:std/layout@1.0.0")) {
            try (var stream = UBNFPackageResolver.class.getResourceAsStream("/ubnf-packages/std-layout-1.0.0.json")) {
                if (stream == null) throw error("missing bundled std/layout@1.0.0");
                bytes = stream.readNBytes(MAX_BYTES + 1);
            }
        } else if (source.startsWith("local:")) {
            Path path = sourceDirectory.resolve(source.substring(6)).normalize();
            if (Files.size(path) > MAX_BYTES) throw error("artifact exceeds 8 MiB");
            bytes = Files.readAllBytes(path);
            childDirectory = path.toAbsolutePath().getParent();
        } else throw error("unsupported artifact source; use explicit local: or builtin:");
        JsonObject artifact = artifact(bytes);
        if (!string(artifact, "id").equals(name) || !string(artifact, "version").equals(string(dependency, "version")))
            throw error("package name/version mismatch: " + name);
        String hash = sha256(bytes);
        JsonObject pinned = new JsonObject();
        JsonObject references = new JsonObject();
        JsonObject nested = artifact.getAsJsonObject("dependencies");
        new TreeMap<String, JsonElement>(nested.asMap()).forEach((id, value) ->
            references.addProperty(id, string(value.getAsJsonObject(), "version")));
        pinned.add("dependencies", references);
        pinned.addProperty("id", name);
        pinned.addProperty("sha256", hash);
        pinned.addProperty("version", string(artifact, "version"));
        JsonObject previous = packages.putIfAbsent(name, pinned);
        if (previous != null && !previous.equals(pinned)) throw error("conflicting package identity: " + name);
        if (previous == null) {
            if (blobs.values().stream().mapToLong(blob -> blob.length).sum() + bytes.length > 64L * 1024 * 1024)
                throw error("package graph exceeds cache limits");
            blobs.put(hash, bytes);
            for (var entry : nested.entrySet()) resolveOne(entry.getKey(), entry.getValue().getAsJsonObject(),
                childDirectory, packages, blobs, visiting);
        }
        visiting.remove(name);
    }

    private JsonObject lock() throws IOException {
        if (lock != null) return lock;
        Path lockPath = directory.resolve(LOCK);
        if (!Files.isRegularFile(lockPath)) throw error("missing lock; run unlaxer deps resolve --manifest ubnf.json");
        JsonObject configuration = parse(reader.read(directory.resolve(MANIFEST)));
        versioned(configuration);
        JsonObject dependencies = object(configuration.get("dependencies"), "dependencies");
        dependencies(dependencies);
        JsonObject value = parse(reader.read(lockPath));
        versioned(value);
        JsonObject roots = object(value.get("roots"), "roots");
        JsonObject expected = new JsonObject();
        dependencies.entrySet().forEach(entry -> expected.addProperty(entry.getKey(), string(entry.getValue().getAsJsonObject(), "version")));
        if (!roots.equals(expected)) throw error("stale lock; run unlaxer deps resolve --manifest ubnf.json");
        audit(value);
        lock = value;
        return lock;
    }
    private static void audit(JsonObject value) {
        JsonObject packages = object(value.get("packages"), "packages");
        if (packages.size() > 128) throw error("package count exceeds 128");
        for (var entry : packages.entrySet()) {
            id(entry.getKey());
            JsonObject pinned = object(entry.getValue(), "locked package");
            if (!entry.getKey().equals(string(pinned, "id"))) throw error("locked package ID mismatch");
            version(string(pinned, "version"));
            if (!string(pinned, "sha256").matches("[0-9a-f]{64}")) throw error("invalid locked SHA-256");
            for (var dependency : object(pinned.get("dependencies"), "dependencies").entrySet()) {
                id(dependency.getKey());
                if (!dependency.getValue().isJsonPrimitive() || !dependency.getValue().getAsJsonPrimitive().isString()) throw error("invalid locked dependency version");
                version(dependency.getValue().getAsString());
                if (!dependency.getValue().getAsString().equals(string(object(packages.get(dependency.getKey()), "locked dependency"), "version")))
                    throw error("locked transitive version mismatch");
            }
        }
        for (var root : object(value.get("roots"), "roots").entrySet()) {
            if (!root.getValue().getAsString().equals(string(object(packages.get(root.getKey()), "locked root"), "version"))) throw error("locked root version mismatch");
        }
        Set<String> done = new HashSet<>();
        for (String name : packages.keySet()) auditNode(name, packages, new HashSet<>(), done);
    }
    private static void auditNode(String name, JsonObject packages, Set<String> visiting, Set<String> done) {
        if (!visiting.add(name)) throw error("cyclic package dependency: " + name);
        if (visiting.size() > 64) throw error("package dependency depth exceeds 64");
        if (done.add(name)) for (String child : packages.getAsJsonObject(name).getAsJsonObject("dependencies").keySet()) auditNode(child, packages, visiting, done);
        visiting.remove(name);
    }

    private JsonObject pinned(String name) throws IOException {
        JsonObject pinned = object(lock().getAsJsonObject("packages").get(name), "locked package " + name);
        if (!name.equals(string(pinned, "id"))) throw error("locked package ID mismatch");
        version(string(pinned, "version"));
        if (!string(pinned, "sha256").matches("[0-9a-f]{64}")) throw error("invalid locked SHA-256");
        return pinned;
    }
    private JsonObject loaded(String name) throws IOException {
        if (artifacts.containsKey(name)) return artifacts.get(name);
        JsonObject pinned = pinned(name);
        Path cache = directory.resolve(".ubnf-cache/packages/" + string(pinned, "sha256") + ".json");
        if (!Files.isRegularFile(cache)) throw error("missing artifact; run unlaxer deps resolve --manifest ubnf.json");
        if (Files.size(cache) > MAX_BYTES) throw error("artifact exceeds 8 MiB");
        byte[] bytes = Files.readAllBytes(cache);
        loadedBytes += bytes.length;
        if (loadedBytes > 64L * 1024 * 1024) throw error("package graph exceeds cache limits");
        if (!sha256(bytes).equals(string(pinned, "sha256"))) throw error("artifact hash mismatch: " + name);
        JsonObject value = artifact(bytes);
        if (!name.equals(string(value, "id")) || !string(value, "version").equals(string(pinned, "version")))
            throw error("artifact identity differs from lock: " + name);
        JsonObject actual = new JsonObject();
        value.getAsJsonObject("dependencies").entrySet().forEach(entry -> actual.addProperty(entry.getKey(), string(entry.getValue().getAsJsonObject(), "version")));
        if (!actual.equals(object(pinned.get("dependencies"), "locked dependencies"))) throw error("artifact dependencies differ from lock");
        for (var entry : actual.entrySet()) if (!entry.getValue().getAsString().equals(string(pinned(entry.getKey()), "version")))
            throw error("locked transitive version mismatch");
        for (String dependency : actual.keySet()) loaded(dependency);
        artifacts.put(name, value); // Publish only after the complete dependency graph was verified.
        return value;
    }
    private String owner(Path path) throws IOException {
        if (!path.startsWith(virtualRoot)) return null;
        Path relative = virtualRoot.relativize(path);
        if (relative.getNameCount() < 2) throw error("invalid package source path");
        String hash = relative.getName(0).toString();
        for (var entry : lock().getAsJsonObject("packages").entrySet())
            if (hash.equals(string(entry.getValue().getAsJsonObject(), "sha256"))) return entry.getKey();
        throw error("unknown package source identity");
    }
    public Path importPath(Path current, String reference) throws IOException {
        String owner = owner(current);
        if (reference.startsWith("pkg:")) {
            String name = reference.substring(4); id(name);
            JsonObject allowed = owner == null ? lock().getAsJsonObject("roots") : object(pinned(owner).get("dependencies"), "dependencies");
            if (!allowed.has(name)) throw error("undeclared package dependency: " + name);
            JsonObject value = loaded(name);
            return virtualRoot.resolve(string(pinned(name), "sha256")).resolve(string(value, "entry"));
        }
        Path target = current.getParent().resolve(reference).normalize();
        if (owner != null && !target.startsWith(virtualRoot.resolve(string(pinned(owner), "sha256"))))
            throw error("relative import escapes package boundary");
        return target;
    }
    public String read(Path path) throws IOException {
        String owner = owner(path);
        if (owner == null) return reader.read(path);
        JsonObject value = loaded(owner);
        Path base = virtualRoot.resolve(string(pinned(owner), "sha256"));
        String file = base.relativize(path).toString().replace('\\', '/');
        JsonElement source = value.getAsJsonObject("files").get(file);
        if (source == null) throw error("missing package file: " + file);
        return source.getAsString();
    }
}
