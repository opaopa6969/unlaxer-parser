package org.unlaxer.dsl.tooling;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import org.unlaxer.dsl.bootstrap.*;

/** Read-only source and pinned identity snapshots for CLI and authoring tools. */
public final class VocabularyOrigins {
    private VocabularyOrigins() {}
    public static JsonObject inspect(Path grammar) throws IOException { return inspect(grammar, Files::readString); }
    public static JsonObject inspect(Path grammar, UBNFModuleLoader.SourceReader reader) throws IOException {
        Path path = grammar.toAbsolutePath().normalize();
        String source = reader.read(path);
        var ast = UBNFMapper.parse(source);
        UBNFModuleLoader.resolve(ast, path, reader); // Enforce the complete module contract first.
        var resolver = new UBNFPackageResolver(path, reader);
        JsonArray modules = new JsonArray();
        for (var declaration : ast.grammars()) for (var imported : declaration.imports()) {
            Path target = resolver.importPath(path, imported.path());
            String text = resolver.read(target);
            var snapshot = UBNFMapper.parseWithSource(text);
            JsonObject module = new JsonObject();
            module.addProperty("alias", imported.alias());
            module.addProperty("grammar", declaration.name());
            module.add("identity", resolver.identity(target));
            module.addProperty("source", imported.path());
            module.addProperty("text", text);
            JsonArray definitions = new JsonArray();
            for (var token : snapshot.ast().grammars().get(0).tokens()) {
                var span = snapshot.spanOf(token).orElseThrow();
                JsonObject definition = new JsonObject();
                definition.addProperty("end", span.end());
                definition.addProperty("name", token.name());
                definition.addProperty("start", span.start());
                definitions.add(definition);
            }
            module.add("definitions", definitions); modules.add(module);
        }
        JsonArray whitespace = new JsonArray();
        for (var declaration : ast.grammars()) {
            String global = declaration.settings().stream().filter(setting -> setting.key().equals("whitespace") && setting.value() instanceof UBNFAST.StringSettingValue)
                .map(setting -> ((UBNFAST.StringSettingValue)setting.value()).value()).findFirst().orElse("none");
            whitespace.add(policy(declaration.name(), null, global));
            for (var rule : declaration.rules()) {
                String local = rule.annotations().stream().filter(annotation -> annotation instanceof UBNFAST.WhitespaceAnnotation)
                    .map(annotation -> ((UBNFAST.WhitespaceAnnotation)annotation).style().orElse("javaStyle")).reduce((first, last) -> last).orElse(null);
                if (local == null) local = rule.annotations().stream().anyMatch(annotation -> annotation instanceof UBNFAST.InterleaveAnnotation) ? "javaStyle" : global;
                whitespace.add(policy(declaration.name(), rule.name(), local));
            }
        }
        JsonObject result = new JsonObject(); result.add("modules", modules); result.addProperty("schemaVersion", 1); result.add("whitespace", whitespace);
        return sorted(result).getAsJsonObject();
    }
    private static JsonObject policy(String grammar, String rule, String style) {
        JsonObject value = new JsonObject(); value.addProperty("grammar", grammar);
        value.addProperty("policy", WhitespaceDefinitions.normalize(style)); value.addProperty("rule", rule); return value;
    }
    private static JsonElement sorted(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            new java.util.TreeMap<>(value.getAsJsonObject().asMap()).forEach((name, child) -> result.add(name, sorted(child)));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray(); value.getAsJsonArray().forEach(child -> result.add(sorted(child))); return result;
        }
        return value;
    }
}
