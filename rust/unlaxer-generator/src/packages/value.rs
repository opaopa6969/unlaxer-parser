//! Input-only JSON tree: Gson member order, without serde_json feature unification.
use std::collections::{btree_map, BTreeMap};
use std::ops::Index;

mod depth;

#[derive(Clone, Debug, PartialEq)]
pub(super) enum Value {
    Null,
    Bool(bool),
    Number(Number),
    String(String),
    Array(Vec<Value>),
    Object(Object),
}

/// Members in Gson order. Each key is stored once; `Entry::sorted` of the i-th entry is
/// the index of the entry whose key is i-th in key order. Keeping that permutation inline
/// (instead of a second key index) keeps `Value` as small as `serde_json::Value`, so the
/// 8 MiB package limit bounds memory close to the previous representation.
#[derive(Clone, Debug, Default)]
pub(super) struct Object {
    entries: Vec<Entry>,
}

#[derive(Clone, Debug)]
pub(super) struct Entry {
    key: String,
    value: Value,
    sorted: usize,
}

impl Object {
    /// Build from members in written order. Gson STRICT (`LinkedTreeMap.put`) keeps the
    /// first position of a repeated key and the value of its last occurrence.
    pub(super) fn from_entries(members: Vec<(String, Value)>) -> Self {
        let mut kept: Vec<(String, Value)> = Vec::with_capacity(members.len());
        let mut positions: BTreeMap<String, usize> = BTreeMap::new();
        for (key, value) in members {
            match positions.entry(key) {
                btree_map::Entry::Occupied(position) => kept[*position.get()].1 = value,
                btree_map::Entry::Vacant(position) => {
                    kept.push((position.key().clone(), value));
                    position.insert(kept.len() - 1);
                }
            }
        }
        Self::from_unique(kept, positions.into_values().collect())
    }

    fn from_unique(members: Vec<(String, Value)>, sorted: Vec<usize>) -> Self {
        let entries = members
            .into_iter()
            .zip(sorted)
            .map(|((key, value), sorted)| Entry { key, value, sorted })
            .collect();
        Self { entries }
    }

    fn by_key(&self) -> impl Iterator<Item = &Entry> {
        self.entries.iter().map(|entry| &self.entries[entry.sorted])
    }

    pub(super) fn get(&self, key: &str) -> Option<&Value> {
        let position = self
            .entries
            .binary_search_by(|entry| self.entries[entry.sorted].key.as_str().cmp(key))
            .ok()?;
        Some(&self.entries[self.entries[position].sorted].value)
    }

    pub(super) fn contains_key(&self, key: &str) -> bool {
        self.get(key).is_some()
    }

    pub(super) fn len(&self) -> usize {
        self.entries.len()
    }

    pub(super) fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub(super) fn keys(&self) -> impl Iterator<Item = &String> {
        self.entries.iter().map(|entry| &entry.key)
    }

    pub(super) fn into_entries(self) -> Vec<(String, Value)> {
        self.entries
            .into_iter()
            .map(|entry| (entry.key, entry.value))
            .collect()
    }
}

type Member<'a> = (&'a String, &'a Value);

impl<'a> IntoIterator for &'a Object {
    type Item = Member<'a>;
    type IntoIter = std::iter::Map<std::slice::Iter<'a, Entry>, fn(&'a Entry) -> Member<'a>>;

    fn into_iter(self) -> Self::IntoIter {
        self.entries.iter().map(|entry| (&entry.key, &entry.value))
    }
}

impl PartialEq for Object {
    fn eq(&self, other: &Self) -> bool {
        // Gson object equality ignores member order (stale lock / pinned references).
        self.len() == other.len()
            && self
                .by_key()
                .zip(other.by_key())
                .all(|(a, b)| a.key == b.key && a.value == b.value)
    }
}

impl Index<&str> for Object {
    type Output = Value;

    fn index(&self, key: &str) -> &Value {
        self.get(key).unwrap_or(&Value::Null)
    }
}

impl Value {
    pub(super) fn as_object(&self) -> Option<&Object> {
        if let Self::Object(value) = self {
            Some(value)
        } else {
            None
        }
    }

    pub(super) fn as_str(&self) -> Option<&str> {
        if let Self::String(value) = self {
            Some(value)
        } else {
            None
        }
    }

    pub(super) fn is_string(&self) -> bool {
        matches!(self, Self::String(_))
    }

    pub(super) fn get(&self, key: &str) -> Option<&Value> {
        self.as_object()?.get(key)
    }
}

impl Index<&str> for Value {
    type Output = Value;

    fn index(&self, key: &str) -> &Value {
        self.get(key).unwrap_or(&Value::Null)
    }
}

/// Numeric tokens stay textual, like Gson's LazilyParsedNumber. Short tokens are
/// inline so large arrays of small numbers retain the 32-byte Value memory bound.
#[derive(Clone, Debug, PartialEq)]
pub(super) enum Number {
    Inline { bytes: [u8; 22], len: u8 },
    Long(Box<str>),
}

impl Number {
    pub(super) fn new(text: &str) -> Self {
        if text.len() <= 22 {
            let mut bytes = [0; 22];
            bytes[..text.len()].copy_from_slice(text.as_bytes());
            Self::Inline {
                bytes,
                len: text.len() as u8,
            }
        } else {
            Self::Long(text.into())
        }
    }

    pub(super) fn as_str(&self) -> &str {
        match self {
            Self::Inline { bytes, len } => std::str::from_utf8(&bytes[..*len as usize])
                .expect("number tokens contain only ASCII"),
            Self::Long(text) => text,
        }
    }
}

pub(super) fn parse(bytes: &[u8]) -> Result<Value, serde_json::Error> {
    depth::parse(bytes)
}

#[cfg(test)]
impl PartialEq<serde_json::Value> for Value {
    fn eq(&self, other: &serde_json::Value) -> bool {
        parse(other.to_string().as_bytes()).is_ok_and(|value| *self == value)
    }
}

#[cfg(test)]
impl PartialEq<&str> for Value {
    fn eq(&self, other: &&str) -> bool {
        self.as_str() == Some(*other)
    }
}
