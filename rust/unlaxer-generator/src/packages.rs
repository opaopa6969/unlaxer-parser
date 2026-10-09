//! Explicit artifact resolution and read-only pinned module loading. No runtime dependencies.
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Component, Path, PathBuf};

const MAX_BYTES: usize = 8 * 1024 * 1024;
const STANDARD: &[u8] =
    include_bytes!("../../../unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json");
fn error(message: impl std::fmt::Display) -> String {
    format!("E-PACKAGE: {message}")
}
fn object<'a>(value: &'a Value, name: &str) -> Result<&'a Map<String, Value>, String> {
    value
        .as_object()
        .ok_or_else(|| error(format!("expected object: {name}")))
}
fn string<'a>(value: &'a Value, name: &str) -> Result<&'a str, String> {
    value[name]
        .as_str()
        .ok_or_else(|| error(format!("expected string: {name}")))
}
fn versioned(value: &Value) -> Result<(), String> {
    if value["schemaVersion"] != 1 {
        return Err(error("unsupported schemaVersion"));
    }
    Ok(())
}
fn id(value: &str) -> Result<(), String> {
    if value.split('/').any(|part| {
        part.is_empty()
            || !part.as_bytes()[0].is_ascii_alphabetic()
            || !part
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c))
    }) {
        return Err(error("invalid package ID"));
    }
    Ok(())
}
fn version(value: &str) -> Result<(), String> {
    let (number, suffix) = value
        .split_once('-')
        .map_or((value, None), |(a, b)| (a, Some(b)));
    if number.split('.').count() != 3
        || number
            .split('.')
            .any(|v| v.is_empty() || !v.bytes().all(|c| c.is_ascii_digit()))
        || suffix.is_some_and(|s| {
            s.is_empty()
                || !s
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || b".-".contains(&c))
        })
    {
        return Err(error("exact package version required"));
    }
    Ok(())
}
fn file(value: &str) -> Result<(), String> {
    if value.chars().any(char::is_control)
        || value.contains(['\\', ':'])
        || value.split('/').any(|v| matches!(v, "" | "." | ".."))
    {
        return Err(error("package file path escapes boundary"));
    }
    Ok(())
}
fn parse(bytes: &[u8]) -> Result<Value, String> {
    if bytes.len() > MAX_BYTES {
        return Err(error("artifact exceeds 8 MiB"));
    }
    let value: Value = serde_json::from_slice(bytes).map_err(|_| error("invalid JSON"))?;
    object(&value, "document")?;
    Ok(value)
}
fn dependencies(value: &Value) -> Result<(), String> {
    for (name, dependency) in object(value, "dependencies")? {
        id(name)?;
        version(string(dependency, "version")?)?;
        string(dependency, "source")?;
    }
    Ok(())
}
fn artifact(bytes: &[u8]) -> Result<Value, String> {
    let value = parse(bytes)?;
    versioned(&value)?;
    id(string(&value, "id")?)?;
    version(string(&value, "version")?)?;
    let files = object(&value["files"], "files")?;
    if files.is_empty() || files.len() > 128 {
        return Err(error("package requires 1..128 files"));
    }
    for (name, content) in files {
        file(name)?;
        if !content.is_string() {
            return Err(error("package file must contain source text"));
        }
    }
    let entry = string(&value, "entry")?;
    file(entry)?;
    if !entry.ends_with(".ubnf") || !files.contains_key(entry) {
        return Err(error("missing UBNF package entry"));
    }
    dependencies(&value["dependencies"])?;
    Ok(value)
}
fn references(value: &Value) -> Result<Value, String> {
    let mut result = Map::new();
    for (name, dependency) in object(value, "dependencies")? {
        result.insert(
            name.clone(),
            Value::String(string(dependency, "version")?.into()),
        );
    }
    Ok(Value::Object(result))
}
fn hash(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
fn read_bytes(path: &Path) -> Result<Vec<u8>, String> {
    if std::fs::metadata(path)
        .map_err(|_| error("missing local artifact"))?
        .len()
        > MAX_BYTES as u64
    {
        return Err(error("artifact exceeds 8 MiB"));
    }
    std::fs::read(path).map_err(|_| error("cannot read local artifact"))
}
/// This command explicitly updates the dependency snapshot. Parsing never calls it.
pub fn resolve(manifest: &Path) -> Result<Value, String> {
    let directory = manifest.parent().unwrap_or_else(|| Path::new("."));
    let configuration = parse(&read_bytes(manifest)?)?;
    versioned(&configuration)?;
    let roots = &configuration["dependencies"];
    dependencies(roots)?;
    let mut packages = Map::new();
    let mut blobs = BTreeMap::new();
    for (name, dependency) in object(roots, "dependencies")? {
        resolve_one(
            name,
            dependency,
            directory,
            &mut packages,
            &mut blobs,
            &mut BTreeSet::new(),
            false,
        )?;
    }
    if packages.len() > 128 || blobs.values().map(Vec::len).sum::<usize>() > 64 * 1024 * 1024 {
        return Err(error("package graph exceeds cache limits"));
    }
    let lock = json!({"schemaVersion":1,"roots":references(roots)?,"packages":packages});
    let cache = directory.join(".ubnf-cache/packages");
    std::fs::create_dir_all(&cache).map_err(|_| error("cannot create package cache"))?;
    for (sha, bytes) in blobs {
        write(&cache.join(format!("{sha}.json")), &bytes)?;
    }
    let text = format!(
        "{}\n",
        serde_json::to_string(&lock).map_err(|_| error("cannot encode lock"))?
    );
    write(&directory.join("ubnf.lock.json"), text.as_bytes())?;
    Ok(lock)
}
fn write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    use std::io::Write;
    let directory = path.parent().unwrap();
    let mut temporary = None;
    for index in 0..64 {
        let candidate = directory.join(format!(".ubnf-{}-{index}.tmp", std::process::id()));
        match std::fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&candidate)
        {
            Ok(mut stream) => {
                if stream.write_all(bytes).is_err() {
                    let _ = std::fs::remove_file(&candidate);
                    return Err(error("cannot write package snapshot"));
                }
                temporary = Some(candidate);
                break;
            }
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(_) => return Err(error("cannot create package snapshot")),
        }
    }
    let temporary = temporary.ok_or_else(|| error("cannot allocate package snapshot"))?;
    let result =
        std::fs::rename(&temporary, path).map_err(|_| error("cannot replace package snapshot"));
    if result.is_err() {
        let _ = std::fs::remove_file(&temporary);
    }
    result
}
fn resolve_one(
    name: &str,
    dependency: &Value,
    directory: &Path,
    packages: &mut Map<String, Value>,
    blobs: &mut BTreeMap<String, Vec<u8>>,
    visiting: &mut BTreeSet<String>,
    remote: bool,
) -> Result<(), String> {
    if !visiting.insert(name.into()) {
        return Err(error(format!("cyclic package dependency: {name}")));
    }
    if visiting.len() > 64 {
        return Err(error("package dependency depth exceeds 64"));
    }
    if !packages.contains_key(name) && packages.len() >= 128 {
        return Err(error("package count exceeds 128"));
    }
    let source = string(dependency, "source")?;
    let builtin: Option<&[u8]> = match source {
        "builtin:std/layout@1.0.0" => Some(STANDARD),
        "builtin:lang/java@0.1.0" => Some(include_bytes!(
            "../../../unlaxer-dsl/src/main/resources/ubnf-packages/lang-java-0.1.0.json"
        )),
        "builtin:lang/typescript@0.1.0" => Some(include_bytes!(
            "../../../unlaxer-dsl/src/main/resources/ubnf-packages/lang-typescript-0.1.0.json"
        )),
        "builtin:lang/rust@0.1.0" => Some(include_bytes!(
            "../../../unlaxer-dsl/src/main/resources/ubnf-packages/lang-rust-0.1.0.json"
        )),
        _ => None,
    };
    let (bytes, child_directory) = if let Some(bytes) = builtin {
        (bytes.to_vec(), directory.to_owned())
    } else if let Some(relative) = source.strip_prefix("local:") {
        if remote {
            return Err(error("remote artifact cannot use local dependencies"));
        }
        let path = directory.join(relative);
        (read_bytes(&path)?, path.parent().unwrap().to_owned())
    } else if source.starts_with("https:") {
        (crate::package_fetch::fetch(source)?, directory.to_owned())
    } else {
        return Err(error(
            "unsupported artifact source; use HTTPS, local: or builtin:",
        ));
    };
    let value = artifact(&bytes)?;
    if string(&value, "id")? != name || string(&value, "version")? != string(dependency, "version")?
    {
        return Err(error(format!("package name/version mismatch: {name}")));
    }
    let sha = hash(&bytes);
    let pinned = json!({"id":name,"version":string(&value,"version")?,"sha256":sha,"dependencies":references(&value["dependencies"])?});
    if let Some(previous) = packages.get(name) {
        if previous != &pinned {
            return Err(error(format!("conflicting package identity: {name}")));
        }
    } else {
        if blobs.values().map(Vec::len).sum::<usize>() + bytes.len() > 64 * 1024 * 1024 {
            return Err(error("package graph exceeds cache limits"));
        }
        packages.insert(name.into(), pinned);
        blobs.insert(sha, bytes);
        for (name, dependency) in object(&value["dependencies"], "dependencies")? {
            resolve_one(
                name,
                dependency,
                &child_directory,
                packages,
                blobs,
                visiting,
                remote || source.starts_with("https:"),
            )?;
        }
    }
    visiting.remove(name);
    Ok(())
}

fn audit(lock: &Value) -> Result<(), String> {
    let packages = object(&lock["packages"], "packages")?;
    if packages.len() > 128 {
        return Err(error("package count exceeds 128"));
    }
    for (name, pinned) in packages {
        id(name)?;
        if name != string(pinned, "id")? {
            return Err(error("locked package ID mismatch"));
        }
        version(string(pinned, "version")?)?;
        let sha = string(pinned, "sha256")?;
        if sha.len() != 64
            || !sha
                .bytes()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
        {
            return Err(error("invalid locked SHA-256"));
        }
        for (name, required) in object(&pinned["dependencies"], "dependencies")? {
            id(name)?;
            version(
                required
                    .as_str()
                    .ok_or_else(|| error("invalid locked dependency version"))?,
            )?;
            if packages.get(name).and_then(|p| p.get("version")) != Some(required) {
                return Err(error("locked transitive version mismatch"));
            }
        }
    }
    for (name, required) in object(&lock["roots"], "roots")? {
        if packages.get(name).and_then(|p| p.get("version")) != Some(required) {
            return Err(error("locked root version mismatch"));
        }
    }
    let mut done = BTreeSet::new();
    for name in packages.keys() {
        audit_node(name, packages, &mut BTreeSet::new(), &mut done)?;
    }
    Ok(())
}
fn audit_node(
    name: &str,
    packages: &Map<String, Value>,
    visiting: &mut BTreeSet<String>,
    done: &mut BTreeSet<String>,
) -> Result<(), String> {
    if !visiting.insert(name.into()) {
        return Err(error(format!("cyclic package dependency: {name}")));
    }
    if visiting.len() > 64 {
        return Err(error("package dependency depth exceeds 64"));
    }
    if done.insert(name.into()) {
        for child in object(&packages[name]["dependencies"], "dependencies")?.keys() {
            audit_node(child, packages, visiting, done)?;
        }
    }
    visiting.remove(name);
    Ok(())
}

pub struct Resolver {
    directory: PathBuf,
    virtual_root: PathBuf,
    lock: Option<Value>,
    artifacts: BTreeMap<String, Value>,
    loaded_bytes: usize,
}
fn normalize(path: &Path) -> PathBuf {
    let mut result = PathBuf::new();
    for component in path.components() {
        match component {
            Component::ParentDir => {
                result.pop();
            }
            Component::CurDir => {}
            value => result.push(value),
        }
    }
    result
}
impl Resolver {
    pub fn new(grammar: &Path) -> Self {
        let directory = grammar.parent().unwrap().to_owned();
        Self {
            virtual_root: directory.join(".ubnf-cache/sources"),
            directory,
            lock: None,
            artifacts: BTreeMap::new(),
            loaded_bytes: 0,
        }
    }
    fn lock(&mut self) -> Result<&Value, String> {
        if self.lock.is_none() {
            let configuration = std::fs::read(self.directory.join("ubnf.json")).map_err(|_| {
                error("missing manifest; run unlaxer deps resolve --manifest ubnf.json")
            })?;
            let configuration = parse(&configuration)?;
            versioned(&configuration)?;
            dependencies(&configuration["dependencies"])?;
            let bytes = std::fs::read(self.directory.join("ubnf.lock.json")).map_err(|_| {
                error("missing lock; run unlaxer deps resolve --manifest ubnf.json")
            })?;
            let lock = parse(&bytes)?;
            versioned(&lock)?;
            if lock["roots"] != references(&configuration["dependencies"])? {
                return Err(error(
                    "stale lock; run unlaxer deps resolve --manifest ubnf.json",
                ));
            }
            audit(&lock)?;
            self.lock = Some(lock);
        }
        Ok(self.lock.as_ref().unwrap())
    }
    fn pinned(&mut self, name: &str) -> Result<Value, String> {
        let value = self.lock()?["packages"][name].clone();
        object(&value, "locked package")?;
        if string(&value, "id")? != name {
            return Err(error("locked package ID mismatch"));
        }
        version(string(&value, "version")?)?;
        let sha = string(&value, "sha256")?;
        if sha.len() != 64
            || !sha
                .bytes()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
        {
            return Err(error("invalid locked SHA-256"));
        }
        Ok(value)
    }
    fn loaded(&mut self, name: &str) -> Result<Value, String> {
        if let Some(value) = self.artifacts.get(name) {
            return Ok(value.clone());
        }
        let pinned = self.pinned(name)?;
        let path = self.directory.join(format!(
            ".ubnf-cache/packages/{}.json",
            string(&pinned, "sha256")?
        ));
        let bytes = read_bytes(&path).map_err(|_| {
            error("missing artifact; run unlaxer deps resolve --manifest ubnf.json")
        })?;
        self.loaded_bytes += bytes.len();
        if self.loaded_bytes > 64 * 1024 * 1024 {
            return Err(error("package graph exceeds cache limits"));
        }
        if hash(&bytes) != string(&pinned, "sha256")? {
            return Err(error(format!("artifact hash mismatch: {name}")));
        }
        let value = artifact(&bytes)?;
        if string(&value, "id")? != name
            || string(&value, "version")? != string(&pinned, "version")?
        {
            return Err(error(format!(
                "artifact identity differs from lock: {name}"
            )));
        }
        let actual = references(&value["dependencies"])?;
        if actual != pinned["dependencies"] {
            return Err(error("artifact dependencies differ from lock"));
        }
        for (name, version) in object(&actual, "dependencies")? {
            if version != &self.pinned(name)?["version"] {
                return Err(error("locked transitive version mismatch"));
            }
        }
        for name in object(&actual, "dependencies")?.keys() {
            self.loaded(name)?;
        }
        // A failed dependency must not leave a parent reusable as verified.
        self.artifacts.insert(name.into(), value.clone());
        Ok(value)
    }
    fn owner(&mut self, path: &Path) -> Result<Option<String>, String> {
        let Ok(relative) = path.strip_prefix(&self.virtual_root) else {
            return Ok(None);
        };
        let hash = relative
            .components()
            .next()
            .ok_or_else(|| error("invalid package source path"))?
            .as_os_str()
            .to_string_lossy();
        for (name, package) in object(&self.lock()?["packages"], "packages")? {
            if string(package, "sha256")? == hash {
                return Ok(Some(name.clone()));
            }
        }
        Err(error("unknown package source identity"))
    }
    /// Return only identities whose cached artifact has already passed hash validation.
    pub fn identity(&mut self, path: &Path) -> Result<Option<Value>, String> {
        let Some(name) = self.owner(path)? else {
            return Ok(None);
        };
        self.loaded(&name)?;
        let pinned = self.pinned(&name)?;
        let base = self.virtual_root.join(string(&pinned, "sha256")?);
        let file = path
            .strip_prefix(base)
            .map_err(|_| error("package boundary"))?
            .to_string_lossy()
            .replace('\\', "/");
        self.read(path)?;
        Ok(Some(
            json!({"file":file,"id":name,"sha256":string(&pinned,"sha256")?,"version":string(&pinned,"version")?}),
        ))
    }

    pub fn import_path(&mut self, current: &Path, reference: &str) -> Result<PathBuf, String> {
        let owner = self.owner(current)?;
        if let Some(name) = reference.strip_prefix("pkg:") {
            id(name)?;
            let allowed = if let Some(owner) = owner {
                self.pinned(&owner)?["dependencies"].clone()
            } else {
                self.lock()?["roots"].clone()
            };
            if allowed.get(name).is_none() {
                return Err(error(format!("undeclared package dependency: {name}")));
            }
            let value = self.loaded(name)?;
            let pinned = self.pinned(name)?;
            return Ok(self
                .virtual_root
                .join(string(&pinned, "sha256")?)
                .join(string(&value, "entry")?));
        }
        let target = normalize(&current.parent().unwrap().join(reference));
        if let Some(owner) = owner {
            let pinned = self.pinned(&owner)?;
            if !target.starts_with(self.virtual_root.join(string(&pinned, "sha256")?)) {
                return Err(error("relative import escapes package boundary"));
            }
        }
        Ok(target)
    }
    pub fn read(&mut self, path: &Path) -> Result<String, String> {
        let Some(owner) = self.owner(path)? else {
            return std::fs::read_to_string(path).map_err(|e| e.to_string());
        };
        let value = self.loaded(&owner)?;
        let pinned = self.pinned(&owner)?;
        let base = self.virtual_root.join(string(&pinned, "sha256")?);
        let relative = path
            .strip_prefix(base)
            .map_err(|_| error("package boundary"))?
            .to_string_lossy()
            .replace('\\', "/");
        value["files"][&relative]
            .as_str()
            .map(str::to_owned)
            .ok_or_else(|| error(format!("missing package file: {relative}")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn failed_transitive_verification_cannot_publish_a_parent() {
        let fixture: Value = serde_json::from_str(include_str!(
            "../../../unlaxer-dsl/src/test/resources/packages/retry.json"
        ))
        .unwrap();
        let directory = std::env::temp_dir().join(format!(
            "ubnf-package-retry-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&directory).unwrap();
        for (file, content) in fixture["files"].as_object().unwrap() {
            std::fs::write(directory.join(file), content.as_str().unwrap()).unwrap();
        }
        resolve(&directory.join("ubnf.json")).unwrap();
        let cached = directory.join(format!(".ubnf-cache/packages/{}.json", hash(STANDARD)));
        let mut corrupted = STANDARD.to_vec();
        corrupted.push(b' ');
        std::fs::write(&cached, corrupted).unwrap();
        let root = directory.join("root.ubnf");
        let mut resolver = Resolver::new(&root);
        for _ in 0..2 {
            assert!(resolver
                .import_path(&root, "pkg:team/layout")
                .unwrap_err()
                .contains(fixture["message"].as_str().unwrap()));
        }
        std::fs::write(cached, STANDARD).unwrap();
        assert!(resolver.import_path(&root, "pkg:team/layout").is_ok());
        std::fs::remove_dir_all(directory).unwrap();
    }
}
