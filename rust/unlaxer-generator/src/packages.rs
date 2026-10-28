//! Explicit artifact resolution and read-only pinned module loading. No runtime dependencies.
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;
use std::path::{Component, Path, PathBuf};

const MAX_BYTES: usize = 8 * 1024 * 1024;
// Share the package graph budget; individual pinned artifacts still have their 8 MiB limit.
const MAX_LOCAL_BYTES: usize = 64 * 1024 * 1024;
const NOT_REGULAR: &str = "E-MODULE: local import is not a regular file";
const TOO_LARGE: &str = "E-MODULE: local import exceeds 64 MiB";

fn read_local_source(path: &Path) -> Result<String, String> {
    read_local_with(path, open_local)
}

/// An opened local source and the type/size seen by fstat on that same handle.
struct OpenedLocal<R> {
    reader: R,
    regular: bool,
    len: u64,
}

fn open_local(path: &Path) -> std::io::Result<OpenedLocal<std::fs::File>> {
    let mut options = std::fs::OpenOptions::new();
    options.read(true);
    // A path replaced by a FIFO after the stat must not block in open(2). O_NONBLOCK
    // does not change reads of regular files; anything else is rejected below.
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::custom_flags(&mut options, libc::O_NONBLOCK);
    let file = options.open(path)?;
    let metadata = file.metadata()?;
    Ok(OpenedLocal {
        regular: metadata.is_file(),
        len: metadata.len(),
        reader: file,
    })
}

fn read_local_with<R: Read>(
    path: &Path,
    open: impl FnOnce(&Path) -> std::io::Result<OpenedLocal<R>>,
) -> Result<String, String> {
    let bytes = read_source_with(
        path,
        MAX_LOCAL_BYTES,
        NOT_REGULAR,
        TOO_LARGE,
        |e| e.to_string(),
        open,
    )?;
    String::from_utf8(bytes).map_err(|_| "stream did not contain valid UTF-8".into())
}

fn read_source_with<R: Read>(
    path: &Path,
    limit: usize,
    not_regular: &str,
    too_large: &str,
    io_error: impl Fn(std::io::Error) -> String,
    open: impl FnOnce(&Path) -> std::io::Result<OpenedLocal<R>>,
) -> Result<Vec<u8>, String> {
    // Reject by path first so the usual devices/FIFOs are never opened at all.
    let metadata = std::fs::metadata(path).map_err(&io_error)?;
    if !metadata.is_file() {
        return Err(not_regular.into());
    }
    if metadata.len() > limit as u64 {
        return Err(too_large.into());
    }
    // Re-check the opened handle, then bound growth after the fstat to limit + 1.
    let opened = open(path).map_err(|e| {
        // Sockets can fail during open; normalize a visible non-file replacement.
        if std::fs::metadata(path).is_ok_and(|m| !m.is_file()) {
            not_regular.to_owned()
        } else {
            io_error(e)
        }
    })?;
    if !opened.regular {
        return Err(not_regular.into());
    }
    if opened.len > limit as u64 {
        return Err(too_large.into());
    }
    let mut bytes = Vec::new();
    opened
        .reader
        .take((limit + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(io_error)?;
    if bytes.len() > limit {
        return Err(too_large.into());
    }
    Ok(bytes)
}

fn read_document(path: &Path, subject: &str) -> Result<Vec<u8>, String> {
    read_document_with(path, subject, open_local)
}

fn read_document_with<R: Read>(
    path: &Path,
    subject: &str,
    open: impl FnOnce(&Path) -> std::io::Result<OpenedLocal<R>>,
) -> Result<Vec<u8>, String> {
    read_source_with(
        path,
        MAX_BYTES,
        &error(format!("{subject} is not a regular file")),
        &error(format!("{subject} exceeds 8 MiB")),
        |e| {
            if e.kind() == std::io::ErrorKind::NotFound {
                error(format!(
                    "missing {subject}; run unlaxer deps resolve --manifest ubnf.json"
                ))
            } else {
                error(format!("cannot read {subject}"))
            }
        },
        open,
    )
}

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
    if bytes.len() > MAX_BYTES {
        return Err(error("artifact exceeds 8 MiB"));
    }
    // Match Java's strict decoder before JSON parsing. Cached callers verify the
    // raw hash first; do not decode in the reader or change manifest/lock parsing.
    std::str::from_utf8(bytes).map_err(|_| error("invalid UTF-8 artifact"))?;
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
fn read_bytes<R: Read>(
    path: &Path,
    cached: bool,
    open: impl FnOnce(&Path) -> std::io::Result<OpenedLocal<R>>,
) -> Result<Vec<u8>, String> {
    // Preserve the Java cache diagnostic; explicit local sources keep the existing Rust
    // local wording, since a missing source cannot be fixed by running deps resolve again.
    let (missing, not_regular, unreadable) = if cached {
        let missing = error("missing artifact; run unlaxer deps resolve --manifest ubnf.json");
        (missing.clone(), missing, error("cannot read artifact"))
    } else {
        (
            error("missing local artifact"),
            error("artifact is not a regular file"),
            error("cannot read local artifact"),
        )
    };
    read_source_with(
        path,
        MAX_BYTES,
        &not_regular,
        &error("artifact exceeds 8 MiB"),
        |e| {
            if e.kind() == std::io::ErrorKind::NotFound {
                missing.clone()
            } else {
                unreadable.clone()
            }
        },
        open,
    )
}
/// This command explicitly updates the dependency snapshot. Parsing never calls it.
pub fn resolve(manifest: &Path) -> Result<Value, String> {
    let directory = manifest.parent().unwrap_or_else(|| Path::new("."));
    let configuration = parse(&read_document(manifest, "manifest")?)?;
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
    if packages.len() > 128 || blobs.values().map(Vec::len).sum::<usize>() > MAX_LOCAL_BYTES {
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
        (
            read_bytes(&path, false, open_local)?,
            path.parent().unwrap().to_owned(),
        )
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
        if blobs.values().map(Vec::len).sum::<usize>() + bytes.len() > MAX_LOCAL_BYTES {
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
    sources: BTreeMap<PathBuf, String>,
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
            sources: BTreeMap::new(),
        }
    }
    /// Read-only editor snapshots for local modules. Verified package sources
    /// always take precedence; these buffers never replace manifest/lock/artifacts.
    pub fn with_sources(grammar: &Path, sources: BTreeMap<PathBuf, String>) -> Self {
        Self {
            sources: sources
                .into_iter()
                .map(|(p, s)| (normalize(&p), s))
                .collect(),
            ..Self::new(grammar)
        }
    }
    fn lock(&mut self) -> Result<&Value, String> {
        if self.lock.is_none() {
            // Lock I/O first (including missing lock), then manifest validation, then lock JSON.
            let bytes = read_document(&self.directory.join("ubnf.lock.json"), "lock")?;
            let configuration = read_document(&self.directory.join("ubnf.json"), "manifest")?;
            let configuration = parse(&configuration)?;
            versioned(&configuration)?;
            dependencies(&configuration["dependencies"])?;
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
        let bytes = read_bytes(&path, true, open_local)?;
        self.loaded_bytes += bytes.len();
        if self.loaded_bytes > MAX_LOCAL_BYTES {
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
            if let Some(source) = self.sources.get(&normalize(path)) {
                return Ok(source.clone());
            }
            return read_local_source(path);
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
    fn artifact_utf8_fixtures_preserve_hash_and_json_order() {
        let cases: Value = serde_json::from_str(include_str!(
            "../../../spec-corpus/ubnf-authoring-lsp/package-artifact-utf8.json"
        ))
        .unwrap();
        let cases = cases.as_array().unwrap();
        assert_eq!(cases.len(), 29);
        for case in cases {
            let fixture = LocalFixture::new();
            let manifest = fixture.0.join("ubnf.json");
            std::fs::write(&manifest, r#"{"schemaVersion":1,"dependencies":{"std/layout":{"version":"1.0.0","source":"local:artifact.json"}}}"#).unwrap();
            std::fs::write(fixture.0.join("artifact.json"), STANDARD).unwrap();
            let mut lock = resolve(&manifest).unwrap();
            let hex = case["bytesHex"].as_str().unwrap();
            let bytes: Vec<u8> = (0..hex.len())
                .step_by(2)
                .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
                .collect();
            let (actual, expected) = if case["mode"] == "cache" {
                let digest = hash(if case["hashMismatch"] == true {
                    STANDARD
                } else {
                    &bytes
                });
                lock["packages"]["std/layout"]["sha256"] = digest.clone().into();
                std::fs::write(fixture.0.join("ubnf.lock.json"), lock.to_string()).unwrap();
                std::fs::write(
                    fixture
                        .0
                        .join(format!(".ubnf-cache/packages/{digest}.json")),
                    &bytes,
                )
                .unwrap();
                let root = fixture.0.join("root.ubnf");
                (
                    Resolver::new(&root)
                        .import_path(&root, "pkg:std/layout")
                        .unwrap_err(),
                    &case["loadError"],
                )
            } else {
                std::fs::write(fixture.0.join("artifact.json"), &bytes).unwrap();
                (resolve(&manifest).unwrap_err(), &case["resolveError"])
            };
            assert_eq!(actual, error(expected.as_str().unwrap()), "{}", case["id"]);
        }
        // The pre-existing byte limit still wins even for malformed encoding.
        assert_eq!(
            artifact(&vec![0xff; MAX_BYTES + 1]).unwrap_err(),
            error("artifact exceeds 8 MiB")
        );
    }

    struct LocalFixture(std::path::PathBuf);
    impl LocalFixture {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "ubnf-local-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ));
            std::fs::create_dir(&path).unwrap();
            Self(path)
        }
    }
    impl Drop for LocalFixture {
        fn drop(&mut self) {
            std::fs::remove_dir_all(&self.0).unwrap();
        }
    }

    #[test]
    fn local_reader_rejects_before_open() {
        let fixture = LocalFixture::new();
        let unopened = |_: &Path| -> std::io::Result<OpenedLocal<std::io::Empty>> {
            panic!("rejected file opened")
        };
        assert_eq!(
            read_local_with(&fixture.0, unopened).unwrap_err(),
            NOT_REGULAR
        );
        let file = fixture.0.join("large");
        std::fs::File::create(&file)
            .unwrap()
            .set_len((MAX_LOCAL_BYTES + 1) as u64)
            .unwrap();
        assert_eq!(read_local_with(&file, unopened).unwrap_err(), TOO_LARGE);
    }

    #[test]
    fn local_reader_exact_limit_and_growth_after_stat() {
        let fixture = LocalFixture::new();
        let file = fixture.0.join("source");
        std::fs::File::create(&file)
            .unwrap()
            .set_len(MAX_LOCAL_BYTES as u64)
            .unwrap();
        assert_eq!(read_local_source(&file).unwrap().len(), MAX_LOCAL_BYTES);
        let regular = |reader| OpenedLocal {
            reader,
            regular: true,
            len: MAX_LOCAL_BYTES as u64,
        };
        let exact = read_local_with(&file, |_| {
            Ok(regular(std::io::repeat(b'a').take(MAX_LOCAL_BYTES as u64)))
        })
        .unwrap();
        assert_eq!(exact.len(), MAX_LOCAL_BYTES);
        struct Growing {
            count: usize,
        }
        impl Read for Growing {
            fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
                assert!(
                    self.count + bytes.len() <= MAX_LOCAL_BYTES + 1,
                    "read past limit + 1"
                );
                bytes.fill(b'a');
                self.count += bytes.len();
                Ok(bytes.len())
            }
        }
        let mut growing = Growing { count: 0 };
        assert_eq!(
            read_local_with(&file, |_| Ok(OpenedLocal {
                reader: &mut growing,
                regular: true,
                len: MAX_LOCAL_BYTES as u64,
            }))
            .unwrap_err(),
            TOO_LARGE
        );
        assert_eq!(growing.count, MAX_LOCAL_BYTES + 1);
    }

    #[cfg(unix)]
    #[test]
    fn local_reader_rechecks_the_opened_handle_after_replacement() {
        let fixture = LocalFixture::new();
        let file = fixture.0.join("source");
        std::fs::write(&file, "grammar Module { }\n").unwrap();
        let pipe = fixture.0.join("pipe");
        let status = std::process::Command::new("mkfifo")
            .arg(&pipe)
            .status()
            .unwrap();
        assert!(status.success());
        let large = fixture.0.join("large");
        std::fs::File::create(&large)
            .unwrap()
            .set_len((MAX_LOCAL_BYTES + 1) as u64)
            .unwrap();
        // The path stat sees a regular file; open then reaches the replacement.
        let (send, receive) = std::sync::mpsc::channel();
        let (source, swapped) = (file.clone(), pipe.clone());
        std::thread::spawn(move || {
            send.send(read_local_with(&source, |_| open_local(&swapped)))
                .unwrap()
        });
        let result = receive.recv_timeout(std::time::Duration::from_secs(10));
        if result.is_err() {
            // Release a reader blocked in open(2) so the test fails instead of hanging.
            let _ = std::fs::OpenOptions::new().write(true).open(&pipe);
        }
        assert_eq!(
            result.expect("open blocked on a FIFO").unwrap_err(),
            NOT_REGULAR
        );
        // The handle's fstat rejects the oversized replacement before any read.
        let unread = |_: &Path| {
            open_local(&large).map(|opened| OpenedLocal {
                reader: std::io::repeat(0).take(0),
                regular: opened.regular,
                len: opened.len,
            })
        };
        assert_eq!(read_local_with(&file, unread).unwrap_err(), TOO_LARGE);
    }

    #[test]
    fn local_reader_keeps_strict_utf8() {
        let fixture = LocalFixture::new();
        let file = fixture.0.join("source");
        std::fs::write(&file, [0xc3, 0x28]).unwrap();
        assert_eq!(
            read_local_source(&file).unwrap_err(),
            "stream did not contain valid UTF-8"
        );
    }

    #[test]
    fn document_reader_checks_subject_limits_and_growth() {
        let fixture = LocalFixture::new();
        let file = fixture.0.join("document");
        for subject in ["manifest", "lock"] {
            assert_eq!(
                read_document(&file, subject).unwrap_err(),
                error(format!(
                    "missing {subject}; run unlaxer deps resolve --manifest ubnf.json"
                ))
            );
            let unopened = |_: &Path| -> std::io::Result<OpenedLocal<std::io::Empty>> {
                panic!("rejected document opened")
            };
            assert_eq!(
                read_document_with(&fixture.0, subject, unopened).unwrap_err(),
                error(format!("{subject} is not a regular file"))
            );
            std::fs::File::create(&file)
                .unwrap()
                .set_len((MAX_BYTES + 1) as u64)
                .unwrap();
            assert_eq!(
                read_document_with(&file, subject, unopened).unwrap_err(),
                error(format!("{subject} exceeds 8 MiB"))
            );
            std::fs::File::create(&file)
                .unwrap()
                .set_len(MAX_BYTES as u64)
                .unwrap();
            assert_eq!(read_document(&file, subject).unwrap().len(), MAX_BYTES);
            struct Growing(usize);
            impl Read for Growing {
                fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
                    assert!(self.0 + bytes.len() <= MAX_BYTES + 1, "read past limit + 1");
                    bytes.fill(b'a');
                    self.0 += bytes.len();
                    Ok(bytes.len())
                }
            }
            let mut growing = Growing(0);
            assert_eq!(
                read_document_with(&file, subject, |_| Ok(OpenedLocal {
                    reader: &mut growing,
                    regular: true,
                    len: MAX_BYTES as u64,
                }))
                .unwrap_err(),
                error(format!("{subject} exceeds 8 MiB"))
            );
            assert_eq!(growing.0, MAX_BYTES + 1);
            for regular in [false, true] {
                // No readable bytes: only handle metadata can detect this replacement.
                assert_eq!(
                    read_document_with(&file, subject, |_| Ok(OpenedLocal {
                        reader: std::io::empty(),
                        regular,
                        len: (MAX_BYTES + 1) as u64,
                    }))
                    .unwrap_err(),
                    error(format!(
                        "{subject} {}",
                        if regular {
                            "exceeds 8 MiB"
                        } else {
                            "is not a regular file"
                        }
                    ))
                );
            }
            std::fs::remove_file(&file).unwrap();
        }
    }

    #[cfg(unix)]
    #[test]
    fn document_reader_rechecks_replaced_fifo_and_device_handles() {
        let fixture = LocalFixture::new();
        let file = fixture.0.join("document");
        std::fs::write(&file, "{}").unwrap();
        let pipe = fixture.0.join("pipe");
        assert!(std::process::Command::new("mkfifo")
            .arg(&pipe)
            .status()
            .unwrap()
            .success());
        for subject in ["manifest", "lock"] {
            let (send, receive) = std::sync::mpsc::channel();
            let (source, swapped) = (file.clone(), pipe.clone());
            std::thread::spawn(move || {
                let _ = send.send(read_document_with(&source, subject, |_| {
                    open_local(&swapped)
                }));
            });
            let result = receive.recv_timeout(std::time::Duration::from_secs(5));
            if result.is_err() {
                // O_RDWR cannot itself wait for a reader when releasing an open mutation.
                let _ = std::fs::OpenOptions::new()
                    .read(true)
                    .write(true)
                    .open(&pipe);
            }
            assert_eq!(
                result.expect("open blocked on FIFO").unwrap_err(),
                error(format!("{subject} is not a regular file"))
            );
            assert_eq!(
                read_document_with(&file, subject, |_| open_local(Path::new("/dev/zero")))
                    .unwrap_err(),
                error(format!("{subject} is not a regular file"))
            );
        }
    }

    #[test]
    fn artifact_reader_checks_paths_raw_bytes_and_growth() {
        let fixture = LocalFixture::new();
        let file = fixture.0.join("artifact");
        for cached in [true, false] {
            let (missing, non_file, unreadable) = if cached {
                let missing =
                    error("missing artifact; run unlaxer deps resolve --manifest ubnf.json");
                (missing.clone(), missing, error("cannot read artifact"))
            } else {
                (
                    error("missing local artifact"),
                    error("artifact is not a regular file"),
                    error("cannot read local artifact"),
                )
            };
            let unopened = |_: &Path| -> std::io::Result<OpenedLocal<std::io::Empty>> {
                panic!("rejected artifact opened")
            };
            assert_eq!(read_bytes(&file, cached, unopened).unwrap_err(), missing);
            assert_eq!(
                read_bytes(&fixture.0, cached, unopened).unwrap_err(),
                non_file
            );
            std::fs::File::create(&file)
                .unwrap()
                .set_len((MAX_BYTES + 1) as u64)
                .unwrap();
            assert_eq!(
                read_bytes(&file, cached, unopened).unwrap_err(),
                error("artifact exceeds 8 MiB")
            );
            // Hash validation must see the original bytes, even invalid UTF-8 or BOM.
            let raw = [0xef, 0xbb, 0xbf, b'{', 0xff, b'}'];
            std::fs::write(&file, raw).unwrap();
            assert_eq!(read_bytes(&file, cached, open_local).unwrap(), raw);
            std::fs::File::create(&file)
                .unwrap()
                .set_len(MAX_BYTES as u64)
                .unwrap();
            assert_eq!(
                read_bytes(&file, cached, open_local).unwrap().len(),
                MAX_BYTES
            );
            struct Growing(usize);
            impl Read for Growing {
                fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
                    assert!(self.0 + bytes.len() <= MAX_BYTES + 1, "read past limit + 1");
                    bytes.fill(b'a');
                    self.0 += bytes.len();
                    Ok(bytes.len())
                }
            }
            let mut growing = Growing(0);
            assert_eq!(
                read_bytes(&file, cached, |_| Ok(OpenedLocal {
                    reader: &mut growing,
                    regular: true,
                    len: MAX_BYTES as u64,
                }))
                .unwrap_err(),
                error("artifact exceeds 8 MiB")
            );
            assert_eq!(growing.0, MAX_BYTES + 1);
            for regular in [false, true] {
                assert_eq!(
                    read_bytes(&file, cached, |_| Ok(OpenedLocal {
                        reader: std::io::empty(),
                        regular,
                        len: (MAX_BYTES + 1) as u64,
                    }))
                    .unwrap_err(),
                    if regular {
                        error("artifact exceeds 8 MiB")
                    } else {
                        non_file.clone()
                    }
                );
            }
            struct Broken;
            impl Read for Broken {
                fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
                    Err(std::io::Error::other("private path detail"))
                }
            }
            assert_eq!(
                read_bytes(&file, cached, |_| Ok(OpenedLocal {
                    reader: Broken,
                    regular: true,
                    len: 0,
                }))
                .unwrap_err(),
                unreadable
            );
            std::fs::remove_file(&file).unwrap();
        }
    }

    #[cfg(unix)]
    #[test]
    fn artifact_reader_rechecks_replaced_fifo_and_device_handles() {
        let fixture = LocalFixture::new();
        let file = fixture.0.join("artifact");
        std::fs::write(&file, "{}").unwrap();
        let pipe = fixture.0.join("pipe");
        assert!(std::process::Command::new("mkfifo")
            .arg(&pipe)
            .status()
            .unwrap()
            .success());
        for cached in [true, false] {
            let expected = error(if cached {
                "missing artifact; run unlaxer deps resolve --manifest ubnf.json"
            } else {
                "artifact is not a regular file"
            });
            let (send, receive) = std::sync::mpsc::channel();
            let (source, swapped) = (file.clone(), pipe.clone());
            let worker = std::thread::spawn(move || {
                let _ = send.send(read_bytes(&source, cached, |_| open_local(&swapped)));
            });
            let result = receive.recv_timeout(std::time::Duration::from_secs(5));
            if result.is_err() {
                // O_RDWR releases a removed-O_NONBLOCK mutation without waiting for a writer.
                let _ = std::fs::OpenOptions::new()
                    .read(true)
                    .write(true)
                    .open(&pipe);
            }
            worker.join().unwrap();
            assert_eq!(result.expect("open blocked on FIFO").unwrap_err(), expected);
            assert_eq!(
                read_bytes(&file, cached, |_| open_local(Path::new("/dev/zero"))).unwrap_err(),
                expected
            );
        }
    }

    #[test]
    fn document_size_limit_uses_utf8_bytes() {
        let cases =
            include_str!("../../../unlaxer-dsl/src/test/resources/packages/document-limits.tsv");
        let directory = std::env::temp_dir().join(format!(
            "ubnf-document-limits-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&directory).unwrap();
        for row in cases
            .lines()
            .filter(|row| !row.starts_with('#') && !row.is_empty())
        {
            let fixture: Vec<_> = row.split('\t').collect();
            let case = directory.join(fixture[0]);
            std::fs::create_dir(&case).unwrap();
            let manifest = case.join("ubnf.json");
            let root = case.join("root.ubnf");
            std::fs::write(&manifest, r#"{"schemaVersion":1,"dependencies":{"std/layout":{"version":"1.0.0","source":"builtin:std/layout@1.0.0"}}}"#).unwrap();
            resolve(&manifest).unwrap();
            let document = if fixture[1] == "manifest" {
                manifest.clone()
            } else {
                case.join("ubnf.lock.json")
            };
            let base = std::fs::read_to_string(&document).unwrap();
            let base = base.trim_end();
            let prefix = format!("{},\"padding\":\"", &base[..base.len() - 1]);
            let suffix = "\"}";
            let bytes: usize = fixture[3].parse().unwrap();
            let fill = bytes - prefix.len() - suffix.len();
            let source = format!(
                "{prefix}{}{suffix}",
                fixture[2].repeat(fill / fixture[2].len()) + &"a".repeat(fill % fixture[2].len())
            );
            assert_eq!(source.len(), bytes, "{}", fixture[0]);
            std::fs::write(document, source).unwrap();
            let mut resolver = Resolver::new(&root);
            if fixture[4] == "LIMIT" {
                assert!(
                    resolver
                        .import_path(&root, "pkg:std/layout")
                        .unwrap_err()
                        .contains("exceeds 8 MiB"),
                    "{}",
                    fixture[0]
                );
                if fixture[1] == "manifest" {
                    assert!(
                        resolve(&manifest).unwrap_err().contains("exceeds 8 MiB"),
                        "{}",
                        fixture[0]
                    );
                }
            } else {
                resolver.import_path(&root, "pkg:std/layout").unwrap();
                if fixture[1] == "manifest" {
                    resolve(&manifest).unwrap();
                }
            }
        }
        std::fs::remove_dir_all(directory).unwrap();
    }

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
