//! JSON schema compilation belongs to the toolchain, while the runtime IR stays dependency-free.
use serde::de::{self, DeserializeSeed, MapAccess, SeqAccess, Visitor};
use serde_json::{Map, Number, Value};
use std::cell::Cell;
use std::collections::{BTreeMap, BTreeSet};
use unlaxer_runtime::semantic_rules::*;
use unlaxer_ubnf::{AnnotationKind, AtomicElement, ElementKind, GrammarDecl, RuleBody};

pub fn load(json: &str, grammar: &GrammarDecl) -> Result<Program, SchemaError> {
    if json.len() > 1048576 {
        return Err(schema("LIMIT", ""));
    }
    let count = Cell::new(0);
    let mut reader = serde_json::Deserializer::from_str(json);
    let value = Seed {
        depth: 0,
        count: &count,
    }
    .deserialize(&mut reader)
    .map_err(json_error)?;
    reader.end().map_err(json_error)?;
    let root = object(
        &value,
        "",
        &[
            "schemaVersion",
            "grammar",
            "profile",
            "unknownLiterals",
            "rules",
        ],
    )?;
    if root.get("schemaVersion").and_then(Value::as_i64) != Some(1) {
        return Err(schema("SCHEMA_VERSION", "schemaVersion"));
    }
    let mut rules = vec![];
    for (index, item) in array(root, "rules", "rules")?.iter().enumerate() {
        let path = format!("rules[{index}]");
        let value = object(
            item,
            &path,
            &[
                "id",
                "node",
                "emit",
                "dependsOn",
                "name",
                "kind",
                "parents",
                "owner",
                "type",
                "visibility",
                "parameters",
                "result",
                "arguments",
            ],
        )?;
        let id = text(value, "id", &format!("{path}.id"))?;
        let node = text(value, "node", &format!("{path}.node"))?;
        let emit_text = text(value, "emit", &format!("{path}.emit"))?;
        let emit = match emit_text.to_ascii_uppercase().as_str() {
            "SCOPE" => Emit::Scope,
            "TYPE" => Emit::Type,
            "FIELD" => Emit::Field,
            "SYMBOL" => Emit::Symbol,
            "SIGNATURE" => Emit::Signature,
            "EXPRESSION" => Emit::Expression,
            "REFERENCE" => Emit::Reference,
            "CALL" => Emit::Call,
            _ => return Err(schema("INVALID_EMIT", &id)),
        };
        let mut selectors = BTreeMap::new();
        for key in [
            "name",
            "kind",
            "parents",
            "type",
            "parameters",
            "result",
            "arguments",
        ] {
            if let Some(item) = value.get(key) {
                let path = format!("{id}.{key}");
                let selector = object(item, &path, &["capture", "field", "literal"])?;
                if selector.len() != 1 {
                    return Err(schema("INVALID_SELECTOR", &path));
                }
                let key_name = selector.keys().next().expect("single selector");
                let source = match key_name.as_str() {
                    "capture" => Source::Capture,
                    "field" => Source::Field,
                    _ => Source::Literal,
                };
                let name = text(selector, key_name, &path)?;
                scalar(&name, true)?;
                selectors.insert(key.into(), Selector { source, name });
            }
        }
        let rule = Rule {
            depends_on: strings(value, "dependsOn", &format!("{id}.dependsOn"), true)?,
            owner: optional(value, "owner", &format!("{id}.owner"))?,
            visibility: optional(value, "visibility", &format!("{id}.visibility"))?,
            id,
            node,
            emit,
            selectors,
        };
        if rule.id.is_empty() || rule.node.is_empty() {
            return Err(schema("EMPTY_NAME", ""));
        }
        rule_scalars(&rule)?;
        rules.push(rule);
    }
    Program::new(
        1,
        text(root, "grammar", "grammar")?,
        text(root, "profile", "profile")?,
        strings(root, "unknownLiterals", "unknownLiterals", false)?,
        rules,
        inventory(grammar)?,
    )
}
pub fn inventory(grammar: &GrammarDecl) -> Result<Inventory, SchemaError> {
    let mut nodes = BTreeMap::new();
    for rule in &grammar.rules {
        let mut captures = BTreeSet::new();
        body(&rule.body, &mut captures, &mut 0, 0)?;
        let fields = rule
            .annotations
            .iter()
            .filter_map(|a| {
                if let AnnotationKind::Mapping { params, .. } = &a.kind {
                    Some(params)
                } else {
                    None
                }
            })
            .flatten()
            .cloned()
            .collect();
        if nodes
            .insert(rule.name.clone(), Shape { captures, fields })
            .is_some()
        {
            return Err(schema("DUPLICATE_NODE", &rule.name));
        }
    }
    if grammar.name.is_empty() {
        return Err(schema("EMPTY_NAME", ""));
    }
    Ok(Inventory {
        grammar: grammar.name.clone(),
        nodes,
    })
}
fn budget(count: &mut usize, depth: usize) -> Result<(), SchemaError> {
    *count += 1;
    if depth > 64 || *count > 16384 {
        Err(schema("LIMIT", "inventory"))
    } else {
        Ok(())
    }
}
fn body(
    value: &RuleBody,
    captures: &mut BTreeSet<String>,
    count: &mut usize,
    depth: usize,
) -> Result<(), SchemaError> {
    budget(count, depth)?;
    // Java's ChoiceBody wraps SequenceBody; preserve that extra inventory depth for choices.
    for sequence in &value.alternatives {
        let inner = if value.alternatives.len() > 1 {
            budget(count, depth + 1)?;
            depth + 1
        } else {
            depth
        };
        for element in &sequence.elements {
            if let Some(capture) = &element.capture {
                captures.insert(capture.clone());
            }
            atomic(&element.element, captures, count, inner + 1)?;
        }
    }
    Ok(())
}
fn atomic(
    value: &AtomicElement,
    captures: &mut BTreeSet<String>,
    count: &mut usize,
    depth: usize,
) -> Result<(), SchemaError> {
    budget(count, depth)?;
    match &value.kind {
        ElementKind::Group(value) | ElementKind::Optional(value) | ElementKind::Repeat(value) => {
            body(value, captures, count, depth + 1)?
        }
        ElementKind::OneOrMore(value) | ElementKind::BoundedRepeat { element: value, .. } => {
            atomic(value, captures, count, depth + 1)?
        }
        ElementKind::Separated { element, separator } => {
            atomic(element, captures, count, depth + 1)?;
            atomic(separator, captures, count, depth + 1)?;
        }
        _ => {}
    }
    Ok(())
}
fn object<'a>(
    value: &'a Value,
    path: &str,
    allowed: &[&str],
) -> Result<&'a Map<String, Value>, SchemaError> {
    let object = value
        .as_object()
        .ok_or_else(|| schema("SCHEMA_TYPE", path))?;
    if object.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err(schema("UNKNOWN_PROPERTY", path));
    }
    Ok(object)
}
fn text(value: &Map<String, Value>, key: &str, path: &str) -> Result<String, SchemaError> {
    value
        .get(key)
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| schema("SCHEMA_TYPE", path))
}
fn optional(value: &Map<String, Value>, key: &str, path: &str) -> Result<String, SchemaError> {
    if value.contains_key(key) {
        text(value, key, path)
    } else {
        Ok(String::new())
    }
}
fn array<'a>(
    value: &'a Map<String, Value>,
    key: &str,
    path: &str,
) -> Result<&'a Vec<Value>, SchemaError> {
    value
        .get(key)
        .and_then(Value::as_array)
        .ok_or_else(|| schema("SCHEMA_TYPE", path))
}
fn strings(
    value: &Map<String, Value>,
    key: &str,
    path: &str,
    optional: bool,
) -> Result<Vec<String>, SchemaError> {
    if optional && !value.contains_key(key) {
        return Ok(vec![]);
    }
    array(value, key, path)?
        .iter()
        .map(|v| {
            v.as_str()
                .map(str::to_owned)
                .ok_or_else(|| schema("SCHEMA_TYPE", path))
        })
        .collect()
}
fn json_error(error: serde_json::Error) -> SchemaError {
    schema(
        if error.to_string().contains("SEMANTIC_JSON_LIMIT") {
            "LIMIT"
        } else {
            "INVALID_JSON"
        },
        "",
    )
}
#[derive(Clone, Copy)]
struct Seed<'a> {
    depth: usize,
    count: &'a Cell<usize>,
}
impl<'de> DeserializeSeed<'de> for Seed<'_> {
    type Value = Value;
    fn deserialize<D: de::Deserializer<'de>>(self, reader: D) -> Result<Value, D::Error> {
        self.count.set(self.count.get() + 1);
        if self.depth > 64 || self.count.get() > 16384 {
            return Err(de::Error::custom("SEMANTIC_JSON_LIMIT"));
        }
        reader.deserialize_any(self)
    }
}
impl<'de> Visitor<'de> for Seed<'_> {
    type Value = Value;
    fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("strict JSON")
    }
    fn visit_bool<E: de::Error>(self, value: bool) -> Result<Value, E> {
        Ok(Value::Bool(value))
    }
    fn visit_i64<E: de::Error>(self, value: i64) -> Result<Value, E> {
        Ok(Value::Number(value.into()))
    }
    fn visit_u64<E: de::Error>(self, value: u64) -> Result<Value, E> {
        Ok(Value::Number(value.into()))
    }
    fn visit_f64<E: de::Error>(self, value: f64) -> Result<Value, E> {
        Number::from_f64(value)
            .map(Value::Number)
            .ok_or_else(|| de::Error::custom("invalid number"))
    }
    fn visit_str<E: de::Error>(self, value: &str) -> Result<Value, E> {
        Ok(Value::String(value.into()))
    }
    fn visit_string<E: de::Error>(self, value: String) -> Result<Value, E> {
        Ok(Value::String(value))
    }
    fn visit_unit<E: de::Error>(self) -> Result<Value, E> {
        Ok(Value::Null)
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut sequence: A) -> Result<Value, A::Error> {
        let mut result = vec![];
        while let Some(value) = sequence.next_element_seed(Seed {
            depth: self.depth + 1,
            count: self.count,
        })? {
            result.push(value);
        }
        Ok(Value::Array(result))
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Value, A::Error> {
        let mut result = Map::new();
        while let Some(key) = map.next_key::<String>()? {
            if result.contains_key(&key) {
                return Err(de::Error::custom("duplicate property"));
            }
            result.insert(
                key,
                map.next_value_seed(Seed {
                    depth: self.depth + 1,
                    count: self.count,
                })?,
            );
        }
        Ok(Value::Object(result))
    }
}
