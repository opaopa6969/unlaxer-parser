//! Deep package documents only. Keep the ordinary two-pass reader's memory profile.
//! Container structure uses an explicit, bounded stack; serde still decodes scalars.
use super::{with_order, MemberOrder, Object, Value};
use serde::de::{DeserializeOwned, Error};
use std::collections::{btree_map::Entry, BTreeMap};

// Gson 2.13.2 JsonReader's default: the root object counts as one container.
const MAX_DEPTH: usize = 255;

type Result<T> = std::result::Result<T, serde_json::Error>;

fn invalid() -> serde_json::Error {
    serde_json::Error::custom("invalid package JSON structure or nesting limit")
}

#[derive(Clone, Copy)]
enum State {
    First,
    Next,
    Value,
    Separator,
}

enum Container {
    Array(Vec<Value>),
    Object {
        members: Vec<(String, Value)>,
        positions: BTreeMap<String, usize>,
        key: Option<String>,
    },
}

struct Frame {
    start: usize,
    state: State,
    container: Container,
}

impl Frame {
    fn add(&mut self, value: Value) {
        match &mut self.container {
            Container::Array(items) => items.push(value),
            Container::Object {
                members,
                positions,
                key,
            } => match positions.entry(key.take().expect("object key precedes value")) {
                Entry::Occupied(position) => members[*position.get()].1 = value,
                Entry::Vacant(position) => {
                    members.push((position.key().clone(), value));
                    position.insert(members.len() - 1);
                }
            },
        }
        self.state = State::Separator;
    }

    fn finish(self, bytes: &[u8], end: usize) -> Result<Value> {
        Ok(match self.container {
            Container::Array(items) => Value::Array(items),
            Container::Object {
                members, positions, ..
            } => {
                // Preserve #947's existing workspace arbitrary_precision protocol;
                // do not silently turn its reserved first key into an ordinary object.
                if members
                    .first()
                    .is_some_and(|(key, _)| key == "$serde_json::private::Number")
                    && serde_json::from_str::<serde_json::Value>(
                        r#"{"$serde_json::private::Number":"0"}"#,
                    )?
                    .is_number()
                {
                    with_order(
                        serde_json::from_slice(&bytes[self.start..end])?,
                        MemberOrder::Scalar,
                    )
                } else {
                    Value::Object(Object::from_unique(
                        members,
                        positions.into_values().collect(),
                    ))
                }
            }
        })
    }
}

struct Input<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl Input<'_> {
    fn peek(&mut self) -> Option<u8> {
        while self
            .bytes
            .get(self.offset)
            .is_some_and(|b| matches!(b, b' ' | b'\t' | b'\r' | b'\n'))
        {
            self.offset += 1;
        }
        self.bytes.get(self.offset).copied()
    }

    fn scalar<T: DeserializeOwned>(&mut self) -> Result<T> {
        let mut stream =
            serde_json::Deserializer::from_slice(&self.bytes[self.offset..]).into_iter();
        let value = stream.next().ok_or_else(invalid)??;
        self.offset += stream.byte_offset();
        Ok(value)
    }
}

pub(super) fn parse(bytes: &[u8]) -> Result<Value> {
    let mut input = Input { bytes, offset: 0 };
    let mut stack: Vec<Frame> = Vec::new();
    let mut completed = None;
    loop {
        if let Some(value) = completed.take() {
            if let Some(parent) = stack.last_mut() {
                parent.add(value);
            } else {
                return if input.peek().is_none() {
                    Ok(value)
                } else {
                    Err(invalid())
                };
            }
        }
        let token = input.peek().ok_or_else(invalid)?;
        if let Some(frame) = stack.last_mut() {
            let close = match frame.container {
                Container::Array(_) => b']',
                Container::Object { .. } => b'}',
            };
            match frame.state {
                State::First | State::Separator if token == close => {
                    input.offset += 1;
                    completed = Some(stack.pop().unwrap().finish(bytes, input.offset)?);
                    continue;
                }
                State::Separator => {
                    if token != b',' {
                        return Err(invalid());
                    }
                    input.offset += 1;
                    frame.state = State::Next;
                    continue;
                }
                State::First | State::Next => {
                    if let Container::Object { key, .. } = &mut frame.container {
                        if token != b'"' {
                            return Err(invalid());
                        }
                        *key = Some(input.scalar()?);
                        if input.peek() != Some(b':') {
                            return Err(invalid());
                        }
                        input.offset += 1;
                        frame.state = State::Value;
                        continue;
                    }
                }
                State::Value => {}
            }
        }
        match token {
            b'[' | b'{' => {
                if stack.len() == MAX_DEPTH {
                    return Err(invalid());
                }
                stack.push(Frame {
                    start: input.offset,
                    state: State::First,
                    container: if token == b'[' {
                        Container::Array(Vec::new())
                    } else {
                        Container::Object {
                            members: Vec::new(),
                            positions: BTreeMap::new(),
                            key: None,
                        }
                    },
                });
                input.offset += 1;
            }
            b'"' | b'n' | b't' | b'f' | b'-' | b'0'..=b'9' => {
                completed = Some(with_order(input.scalar()?, MemberOrder::Scalar));
            }
            _ => return Err(invalid()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn iterative_structure_keeps_serde_validation_values_and_member_order() {
        let inputs = [
            r#"{"z":0,"a":[1,2,{"b":3,"a":4},5],"\u007a":{"d":1,"c":2}}"#,
            r#"{"x":true,"y":false,"z":null,"s":"[{}]:,\\\"é😀"}"#,
            r#"{"n":-0.0,"x":1.25,"large":18446744073709551616,"exp":1e300}"#,
            r#"{"n":1e999}"#,
            r#"{"$serde_json::private::Number":"1.5"}"#,
            r#"{"$serde_json::private::Number":null}"#,
            r#"{"$serde_json::private::Number":"1.5","a":1}"#,
            r#"{"a":1,"$serde_json::private::Number":"1.5"}"#,
            r#"{"x":[],"x":{},"x":[null],"y":{}}"#,
            r#"{"x":0,"x":01}"#,
            r#"{"x":1,}"#,
            r#"{"x":[1,]}"#,
            r#"{"x":[,1]}"#,
            r#"{"x":[1 2]}"#,
            r#"{"x" 1}"#,
            r#"{x:1}"#,
            r#"{"x":tru}"#,
            r#"{"x":nullx}"#,
            r#"{"x":1e}"#,
            r#"{"x":+1}"#,
            r#"{"x":"\q"}"#,
            r#"{"x":"\ud800"}"#,
            r#"{"x":[1}}"#,
            r#"{"x":[]]"#,
            r#"{"x":1} {}"#,
            r#"{"x":/*comment*/1}"#,
            r#"{"x":1"#,
            r#"{"x":1,"#,
            "{\"x\":\u{000c}1}",
        ];
        for input in inputs {
            let reference = super::super::parse(input.as_bytes());
            let iterative = parse(input.as_bytes());
            match reference {
                Ok(reference) => {
                    let iterative = iterative.unwrap_or_else(|e| panic!("{input}: {e}"));
                    assert_eq!(
                        format!("{iterative:?}"),
                        format!("{reference:?}"),
                        "{input}"
                    );
                }
                Err(_) => assert!(iterative.is_err(), "{input}"),
            }
        }
    }
}
