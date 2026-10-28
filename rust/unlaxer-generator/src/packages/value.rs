//! Input-only JSON tree: Gson member order, without serde_json feature unification.
use serde::de::{Deserialize, Deserializer, MapAccess, SeqAccess, Visitor};
use serde::ser::{Serialize, Serializer};
use serde_json::Number;
use std::collections::BTreeMap;
use std::ops::Index;

#[derive(Clone, Debug, PartialEq)]
pub(super) enum Value {
    Null,
    Bool(bool),
    Number(Number),
    String(String),
    Array(Vec<Value>),
    Object(Object),
}

#[derive(Clone, Debug, Default)]
pub(super) struct Object {
    entries: Vec<(String, Value)>,
    positions: BTreeMap<String, usize>,
}

impl Object {
    pub(super) fn insert(&mut self, key: String, value: Value) {
        // Gson STRICT keeps the first position but replaces the value of a duplicate.
        // Indexed lookup avoids quadratic work on wide objects (including metadata).
        if let Some(&position) = self.positions.get(&key) {
            self.entries[position].1 = value;
        } else {
            self.positions.insert(key.clone(), self.entries.len());
            self.entries.push((key, value));
        }
    }

    pub(super) fn get(&self, key: &str) -> Option<&Value> {
        self.positions.get(key).map(|&i| &self.entries[i].1)
    }

    pub(super) fn contains_key(&self, key: &str) -> bool {
        self.positions.contains_key(key)
    }

    pub(super) fn len(&self) -> usize {
        self.entries.len()
    }

    pub(super) fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub(super) fn keys(&self) -> impl Iterator<Item = &String> {
        self.entries.iter().map(|(key, _)| key)
    }

    pub(super) fn into_entries(self) -> Vec<(String, Value)> {
        self.entries
    }
}

impl<'a> IntoIterator for &'a Object {
    type Item = &'a (String, Value);
    type IntoIter = std::slice::Iter<'a, (String, Value)>;

    fn into_iter(self) -> Self::IntoIter {
        self.entries.iter()
    }
}

impl PartialEq for Object {
    fn eq(&self, other: &Self) -> bool {
        // Gson object equality ignores member order (stale lock / pinned references).
        self.len() == other.len()
            && self
                .entries
                .iter()
                .all(|(key, value)| other.get(key) == Some(value))
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
            Self::Object(value) => value
                .entries
                .iter()
                .map(|(k, v)| (k, v))
                .collect::<BTreeMap<_, _>>()
                .serialize(serializer),
        }
    }
}

// Read only structure/order in the second pass. In workspace builds serde_json's
// arbitrary_precision feature can be unified by DAP; its numeric map protocol
// must never be interpreted as a package object. The first pass owns all values.
enum MemberOrder {
    Scalar,
    Array(Vec<MemberOrder>),
    Object(Vec<(String, MemberOrder)>),
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
                let mut items = Vec::new();
                while let Some(item) = sequence.next_element()? {
                    items.push(item);
                }
                Ok(MemberOrder::Array(items))
            }

            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<MemberOrder, A::Error> {
                let mut entries: Vec<(String, MemberOrder)> = Vec::new();
                let mut positions: BTreeMap<String, usize> = BTreeMap::new();
                while let Some((key, value)) = map.next_entry::<String, MemberOrder>()? {
                    if let Some(&position) = positions.get(&key) {
                        entries[position].1 = value;
                    } else {
                        positions.insert(key.clone(), entries.len());
                        entries.push((key, value));
                    }
                }
                Ok(MemberOrder::Object(entries))
            }
        }
        deserializer.deserialize_any(OrderVisitor)
    }
}

pub(super) fn parse(bytes: &[u8]) -> Result<Value, serde_json::Error> {
    let value = serde_json::from_slice(bytes)?;
    let order = serde_json::from_slice(bytes)?;
    Ok(with_order(value, order))
}

fn with_order(value: serde_json::Value, order: MemberOrder) -> Value {
    match value {
        serde_json::Value::Null => Value::Null,
        serde_json::Value::Bool(value) => Value::Bool(value),
        serde_json::Value::Number(value) => Value::Number(value),
        serde_json::Value::String(value) => Value::String(value),
        serde_json::Value::Array(items) => {
            let MemberOrder::Array(order) = order else {
                unreachable!("same JSON array in both passes");
            };
            Value::Array(
                items
                    .into_iter()
                    .zip(order)
                    .map(|(v, o)| with_order(v, o))
                    .collect(),
            )
        }
        serde_json::Value::Object(mut fields) => {
            let MemberOrder::Object(order) = order else {
                unreachable!("same JSON object in both passes");
            };
            let mut object = Object::default();
            for (key, child_order) in order {
                let child = fields.remove(&key).expect("same JSON key in both passes");
                object.insert(key, with_order(child, child_order));
            }
            Value::Object(object)
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
