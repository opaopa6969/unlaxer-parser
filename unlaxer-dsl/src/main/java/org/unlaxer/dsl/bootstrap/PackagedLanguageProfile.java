package org.unlaxer.dsl.bootstrap;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.unlaxer.source.LanguageProfile;

/** Read-only root selection from a verified lock; never resolves dependencies or executes host code. */
public record PackagedLanguageProfile(LanguageProfile profile, String source, UBNFAST.UBNFFile ast,
                                     JsonObject identity, JsonObject vocabulary) {
    public static PackagedLanguageProfile load(Path manifest, String packageId) throws IOException {
        manifest = manifest.toAbsolutePath().normalize();
        if (!manifest.getFileName().toString().equals("ubnf.json")) throw new IllegalArgumentException("expected ubnf.json manifest");
        var resolver = new UBNFPackageResolver(manifest, Files::readString);
        Path entry = resolver.importPath(manifest, "pkg:" + packageId);
        var identity = resolver.identity(entry);
        var profile = LanguageProfile.parse(resolver.read(resolver.importPath(entry, "profile.tsv")));
        if (!entry.getFileName().toString().equals(profile.grammarFile())) throw new IllegalArgumentException("profile grammar/entry mismatch");
        String source = resolver.read(entry);
        var ast = UBNFModuleLoader.resolve(UBNFMapper.parse(source), entry, resolver);
        if (ast.grammars().size() != 1) throw new IllegalArgumentException("profile requires exactly one grammar");
        var grammar = ast.grammars().get(0);
        for (String rule : profile.entries().keySet()) {
            var language = profile.identity(rule);
            if (!language.packageId().equals(identity.get("id").getAsString()) || !language.version().equals(identity.get("version").getAsString()))
                throw new IllegalArgumentException("profile package identity mismatch");
            if (!language.grammar().equals(grammar.name()) || grammar.rules().stream().noneMatch(value -> value.name().equals(rule)))
                throw new IllegalArgumentException("profile grammar/entry mismatch");
        }
        resolver.read(resolver.importPath(entry, profile.fixtureFile()));
        return new PackagedLanguageProfile(profile, source, ast, identity,
            org.unlaxer.dsl.tooling.VocabularyOrigins.inspect(entry, source, resolver));
    }
}
