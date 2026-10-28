//! Input-only JSON tree: Gson member order, without serde_json feature unification.
use serde::de::{Deserialize, Deserializer, MapAccess, SeqAccess, Visitor};
use serde::ser::{Serialize, SerializeMap, Serializer};
use serde_json::Number;
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

impl Serialize for Value {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            Self::Null => serializer.serialize_unit(),
            Self::Bool(value) => value.serialize(serializer),
            Self::Number(value) => value.serialize(serializer),
            Self::String(value) => value.serialize(serializer),
            Self::Array(value) => value.serialize(serializer),
            // Output stays sorted, as in Java's explicitly constructed lock objects.
            Self::Object(value) => {
                let mut map = serializer.serialize_map(Some(value.len()))?;
                for entry in value.by_key() {
                    map.serialize_entry(&entry.key, &entry.value)?;
                }
                map.end()
            }
        }
    }
}

// Read only structure/order in the second pass. In workspace builds serde_json's
// arbitrary_precision feature can be unified by DAP; its numeric map protocol
// must never be interpreted as a package object. The first pass owns all values,
// so an object records only each kept member's key rank, and an array whose items
// are all scalars records nothing.
enum MemberOrder {
    Scalar,
    Array(Vec<MemberOrder>),
    Object(Vec<(usize, MemberOrder)>),
}

impl<'de> Deserialize<'de> for MemberOrder {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct OrderVisitor;
        impl<'de> Visitor<'de> for OrderVisitor {
            type Value = MemberOrder;

            fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                f.write_str("a JSON value")
            }

            fn visit_unit<E>(self) -> Result<MemberOrder, E> {
                Ok(MemberOrder::Scalar)
            }
            fn visit_bool<E>(self, _: bool) -> Result<MemberOrder, E> {
                Ok(MemberOrder::Scalar)
            }
            fn visit_i64<E>(self, _: i64) -> Result<MemberOrder, E> {
                Ok(MemberOrder::Scalar)
            }
            fn visit_u64<E>(self, _: u64) -> Result<MemberOrder, E> {
                Ok(MemberOrder::Scalar)
            }
            fn visit_f64<E>(self, _: f64) -> Result<MemberOrder, E> {
                Ok(MemberOrder::Scalar)
            }
            fn visit_str<E>(self, _: &str) -> Result<MemberOrder, E> {
                Ok(MemberOrder::Scalar)
            }

            fn visit_seq<A: SeqAccess<'de>>(
                self,
                mut sequence: A,
            ) -> Result<MemberOrder, A::Error> {
                // Leading scalars are only counted until a structured item appears.
                let (mut items, mut count) = (Vec::new(), 0);
                while let Some(item) = sequence.next_element::<MemberOrder>()? {
                    if !matches!(item, MemberOrder::Scalar) {
                        items.resize_with(count, || MemberOrder::Scalar);
                        items.push(item);
                    } else if !items.is_empty() {
                        items.push(item);
                    }
                    count += 1;
                }
                Ok(if items.is_empty() {
                    MemberOrder::Scalar
                } else {
                    MemberOrder::Array(items)
                })
            }

            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<MemberOrder, A::Error> {
                // Same duplicate rule as Object::from_entries; the key index is
                // temporary, so wide or duplicate-heavy metadata stays O(n log n).
                let mut members: Vec<(usize, MemberOrder)> = Vec::new();
                let mut positions: BTreeMap<String, usize> = BTreeMap::new();
                while let Some(key) = map.next_key::<String>()? {
                    let order = map.next_value::<MemberOrder>()?;
                    match positions.entry(key) {
                        btree_map::Entry::Occupied(position) => members[*position.get()].1 = order,
                        btree_map::Entry::Vacant(position) => {
                            position.insert(members.len());
                            members.push((0, order));
                        }
                    }
                }
                for (rank, position) in positions.into_values().enumerate() {
                    members[position].0 = rank;
                }
                Ok(MemberOrder::Object(members))
            }
        }
        deserializer.deserialize_any(OrderVisitor)
    }
}

pub(super) fn parse(bytes: &[u8]) -> Result<Value, serde_json::Error> {
    let value = match serde_json::from_slice(bytes) {
        Ok(value) => value,
        Err(error) if error.to_string().starts_with("recursion limit exceeded") => {
            return depth::parse(bytes);
        }
        Err(error) => return Err(error),
    };
    let order = serde_json::from_slice(bytes)?;
    Ok(with_order(value, order))
}

fn with_order(value: serde_json::Value, order: MemberOrder) -> Value {
    match value {
        serde_json::Value::Null => Value::Null,
        serde_json::Value::Bool(value) => Value::Bool(value),
        serde_json::Value::Number(value) => Value::Number(value),
        serde_json::Value::String(value) => Value::String(value),
        serde_json::Value::Array(items) => Value::Array(match order {
            MemberOrder::Scalar => items
                .into_iter()
                .map(|v| with_order(v, MemberOrder::Scalar))
                .collect(),
            MemberOrder::Array(order) => items
                .into_iter()
                .zip(order)
                .map(|(v, o)| with_order(v, o))
                .collect(),
            MemberOrder::Object(_) => unreachable!("same JSON array in both passes"),
        }),
        serde_json::Value::Object(fields) => {
            let MemberOrder::Object(order) = order else {
                unreachable!("same JSON object in both passes");
            };
            // Both passes decode the same unique keys; rank them independently of
            // whether serde_json's map iterates in key order.
            let mut fields: Vec<(String, serde_json::Value)> = fields.into_iter().collect();
            fields.sort_by(|a, b| a.0.cmp(&b.0));
            assert_eq!(fields.len(), order.len(), "same JSON object in both passes");
            let mut fields: Vec<_> = fields.into_iter().map(Some).collect();
            let mut sorted = vec![0; order.len()];
            let members = order
                .into_iter()
                .enumerate()
                .map(|(position, (rank, child))| {
                    sorted[rank] = position;
                    let (key, value) = fields[rank].take().expect("same JSON key in both passes");
                    (key, with_order(value, child))
                })
                .collect();
            Value::Object(Object::from_unique(members, sorted))
        }
    }
}

#[cfg(test)]
impl PartialEq<serde_json::Value> for Value {
    fn eq(&self, other: &serde_json::Value) -> bool {
        serde_json::to_value(self).unwrap() == *other
    }
}

#[cfg(test)]
impl PartialEq<&str> for Value {
    fn eq(&self, other: &&str) -> bool {
        self.as_str() == Some(*other)
    }
}
