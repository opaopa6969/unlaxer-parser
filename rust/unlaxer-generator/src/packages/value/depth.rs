//! Package JSON structure and numeric tokens, independent of serde_json features.
//! Container depth is bounded; serde still decodes strings, booleans and null.
use super::super::text::Text;
use super::{Number, Object, Value};
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
        members: Vec<(Text, Value)>,
        positions: BTreeMap<Text, usize>,
        key: Option<Text>,
    },
}

struct Frame {
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

    fn finish(self) -> Value {
        match self.container {
            Container::Array(items) => Value::Array(items),
            Container::Object {
                members, positions, ..
            } => Value::Object(Object::from_unique(
                members,
                positions.into_values().collect(),
            )),
        }
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
    fn number(&mut self) -> Result<Value> {
        let start = self.offset;
        let take = |input: &mut Self, byte| {
            if input.bytes.get(input.offset) == Some(&byte) {
                input.offset += 1;
                true
            } else {
                false
            }
        };
        take(self, b'-');
        if !take(self, b'0') {
            self.digits()?;
        }
        if take(self, b'.') {
            self.digits()?;
        }
        if take(self, b'e') || take(self, b'E') {
            if !take(self, b'+') {
                take(self, b'-');
            }
            self.digits()?;
        }
        // Gson STRICT's 1024-char reader buffer accepts at most 1023 chars in
        // a numeric token. Preserve that boundary as well as the original spelling.
        if self.offset - start >= 1024 {
            return Err(invalid());
        }
        let text = std::str::from_utf8(&self.bytes[start..self.offset]).map_err(|_| invalid())?;
        Ok(Value::Number(Number::new(text)))
    }

    fn digits(&mut self) -> Result<()> {
        let start = self.offset;
        while self.bytes.get(self.offset).is_some_and(u8::is_ascii_digit) {
            self.offset += 1;
        }
        if self.offset == start {
            Err(invalid())
        } else {
            Ok(())
        }
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
                    completed = Some(stack.pop().unwrap().finish());
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
                        *key = Some(input.scalar::<String>()?.into());
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
            b'"' => completed = Some(Value::String(input.scalar::<String>()?.into())),
            b'n' => {
                input.scalar::<()>()?;
                completed = Some(Value::Null);
            }
            b't' | b'f' => completed = Some(Value::Bool(input.scalar()?)),
            b'-' | b'0'..=b'9' => completed = Some(input.number()?),
            _ => return Err(invalid()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn iterative_structure_rejects_malformed_json() {
        let value =
            parse(br#"{"t":true,"f":false,"n":null,"s":"[{}]:,\\\"","x":[],"x":{},"x":[null]}"#)
                .unwrap();
        assert_eq!(value["t"], Value::Bool(true));
        assert_eq!(value["f"], Value::Bool(false));
        assert_eq!(value["n"], Value::Null);
        assert_eq!(value["s"].as_str(), Some("[{}]:,\\\""));
        assert_eq!(value["x"], Value::Array(vec![Value::Null]));
        let inputs = [
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
            assert!(parse(input.as_bytes()).is_err(), "{input}");
        }
    }
}
