use serde_json::{Map, Value};
use std::{collections::BTreeMap, fs::File, io::Read, path::Path};
const SCHEMA: &str = "ubnf.words/v1";
const LIMIT: u64 = 1_048_576;
type Result<T> = std::result::Result<T, &'static str>;
#[derive(Clone)]
pub struct Snapshot {
    revision: String,
    words: Vec<String>,
}
impl Snapshot {
    pub fn new(revision: String, words: Vec<String>) -> Result<Self> {
        if revision.is_empty() || words.iter().any(String::is_empty) {
            return Err("E-DATA");
        }
        Ok(Self { revision, words })
    }
    pub fn revision(&self) -> &str {
        &self.revision
    }
    pub fn words(&self) -> &[String] {
        &self.words
    }
}
pub trait Resolver {
    fn resolve(&self, properties: &Map<String, Value>, directory: &Path) -> Result<Snapshot>;
}
pub struct InlineResolver;
impl Resolver for InlineResolver {
    fn resolve(&self, properties: &Map<String, Value>, _: &Path) -> Result<Snapshot> {
        snapshot(properties)
    }
}
pub struct FileResolver;
impl Resolver for FileResolver {
    fn resolve(&self, properties: &Map<String, Value>, directory: &Path) -> Result<Snapshot> {
        fields(properties, &["path"], "E-CONFIG")?;
        let data = read(
            &directory.join(string(properties, "path", "E-CONFIG")?),
            "E-DATA",
        )?;
        snapshot(object(&data, "E-DATA")?)
    }
}
pub fn load(path: &Path) -> Result<Snapshot> {
    let registry: BTreeMap<&str, Box<dyn Resolver>> = BTreeMap::from([
        ("inline/v1", Box::new(InlineResolver) as Box<dyn Resolver>),
        ("file/v1", Box::new(FileResolver) as Box<dyn Resolver>),
    ]);
    load_with(path, &registry)
}
pub fn load_with(path: &Path, registry: &BTreeMap<&str, Box<dyn Resolver>>) -> Result<Snapshot> {
    let config = read(path, "E-CONFIG")?;
    let config = object(&config, "E-CONFIG")?;
    fields(config, &["version", "bindings", "resolvers"], "E-CONFIG")?;
    if config["version"].as_u64() != Some(1) {
        return Err("E-CONFIG");
    }
    let bindings = object(&config["bindings"], "E-CONFIG")?;
    let binding = object(bindings.get(crate::BINDING).ok_or("E-BINDING")?, "E-CONFIG")?;
    fields(binding, &["resolver", "schema"], "E-CONFIG")?;
    if string(binding, "schema", "E-CONFIG")? != SCHEMA {
        return Err("E-CONFIG");
    }
    let name = string(binding, "resolver", "E-CONFIG")?;
    let definitions = object(&config["resolvers"], "E-CONFIG")?;
    let definition = object(definitions.get(name).ok_or("E-RESOLVER")?, "E-CONFIG")?;
    fields(definition, &["provider", "properties"], "E-CONFIG")?;
    let provider = string(definition, "provider", "E-CONFIG")?;
    let resolver = registry.get(provider).ok_or("E-PROVIDER")?;
    resolver.resolve(
        object(&definition["properties"], "E-CONFIG")?,
        path.parent().unwrap_or(Path::new(".")),
    )
}
fn snapshot(data: &Map<String, Value>) -> Result<Snapshot> {
    fields(data, &["schema", "revision", "words"], "E-DATA")?;
    if string(data, "schema", "E-DATA")? != SCHEMA {
        return Err("E-DATA");
    }
    let revision = string(data, "revision", "E-DATA")?.to_owned();
    let words = data["words"]
        .as_array()
        .ok_or("E-DATA")?
        .iter()
        .map(|value| {
            value
                .as_str()
                .filter(|word| !word.is_empty())
                .map(str::to_owned)
                .ok_or("E-DATA")
        })
        .collect::<Result<Vec<_>>>()?;
    Snapshot::new(revision, words)
}
fn fields(object: &Map<String, Value>, names: &[&str], code: &'static str) -> Result<()> {
    if object.len() != names.len() || names.iter().any(|key| !object.contains_key(*key)) {
        Err(code)
    } else {
        Ok(())
    }
}
fn string<'a>(object: &'a Map<String, Value>, key: &str, code: &'static str) -> Result<&'a str> {
    object
        .get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or(code)
}
fn object<'a>(value: &'a Value, code: &'static str) -> Result<&'a Map<String, Value>> {
    value.as_object().ok_or(code)
}
fn read(path: &Path, code: &'static str) -> Result<Value> {
    let mut bytes = vec![];
    File::open(path)
        .map_err(|_| "E-IO")?
        .take(LIMIT + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "E-IO")?;
    if bytes.len() as u64 > LIMIT {
        return Err(code);
    }
    serde_json::from_slice(&bytes).map_err(|_| code)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn file_updates_only_affect_the_next_resolve() {
        let dir = std::env::temp_dir().join(format!("resolver-snapshot-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("words.json");
        let properties = json!({"path":"words.json"});
        let resolver = FileResolver;
        std::fs::write(
            &file,
            json!({"schema":SCHEMA,"revision":"v1","words":["𠮷野"]}).to_string(),
        )
        .unwrap();
        let old = resolver
            .resolve(properties.as_object().unwrap(), &dir)
            .unwrap();
        std::fs::write(
            &file,
            json!({"schema":SCHEMA,"revision":"v2","words":["東京"]}).to_string(),
        )
        .unwrap();
        let fresh = resolver
            .resolve(properties.as_object().unwrap(), &dir)
            .unwrap();
        assert_eq!(old.words, ["𠮷野"]);
        assert_eq!(old.revision, "v1");
        assert_eq!(fresh.words, ["東京"]);
        assert_eq!(fresh.revision, "v2");
        assert_eq!(crate::parse("𠮷野-12", &old)["accepted"], true);
        assert_eq!(crate::parse("𠮷野-12", &fresh)["accepted"], false);
        std::fs::remove_file(file).unwrap();
        std::fs::remove_dir(dir).unwrap();
    }
}
