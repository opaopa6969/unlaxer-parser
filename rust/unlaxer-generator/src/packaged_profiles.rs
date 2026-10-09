//! Explicit package roots share the pinned token resolver but never expand token import privileges.
use std::path::Path;
use unlaxer_runtime::language_profile::LanguageProfile;

pub struct PackagedProfile {
    pub profile: LanguageProfile,
    pub source: String,
    pub ast: unlaxer_ubnf::UbnfFile,
    pub identity: serde_json::Value,
    pub vocabulary: serde_json::Value,
}

pub fn load(manifest: &Path, package_id: &str) -> Result<PackagedProfile, String> {
    let manifest = std::path::absolute(manifest).map_err(|e| e.to_string())?;
    if manifest.file_name().and_then(|name| name.to_str()) != Some("ubnf.json") {
        return Err("expected ubnf.json manifest".into());
    }
    let mut resolver = crate::packages::Resolver::new(&manifest);
    let entry = resolver.import_path(&manifest, &format!("pkg:{package_id}"))?;
    let identity = resolver
        .identity(&entry)?
        .ok_or("missing package identity")?;
    let profile_path = resolver.import_path(&entry, "profile.tsv")?;
    let profile = LanguageProfile::parse(&resolver.read(&profile_path)?)?;
    if entry.file_name().and_then(|name| name.to_str()) != Some(profile.grammar_file()) {
        return Err("profile grammar/entry mismatch".into());
    }
    let source = resolver.read(&entry)?;
    let fixture = resolver.import_path(&entry, profile.fixture_file())?;
    resolver.read(&fixture)?;
    let ast = crate::modules::resolve(&source, &entry, &mut resolver)?;
    let vocabulary = crate::vocabulary_origins::inspect_resolved(&entry, &source, &mut resolver)?;
    if ast.grammars.len() != 1 {
        return Err("profile requires exactly one grammar".into());
    }
    let grammar = &ast.grammars[0];
    for rule in profile.entries.keys() {
        let language = profile.identity(rule)?;
        if identity["id"] != language.package_id || identity["version"] != language.version {
            return Err("profile package identity mismatch".into());
        }
        if language.grammar != grammar.name
            || !grammar.rules.iter().any(|value| value.name == *rule)
        {
            return Err("profile grammar/entry mismatch".into());
        }
    }
    Ok(PackagedProfile {
        profile,
        source,
        ast,
        identity,
        vocabulary,
    })
}
