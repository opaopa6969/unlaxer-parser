//! Token spelling, field order and UTF-16 reader locations in an already
//! validated JSON frame.
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use std::ops::Range;

pub(super) struct Wire<'a> {
    pub text: &'a str,
    spans: BTreeMap<String, Range<usize>>,
    /// Object member names in input order (duplicates included).
    keys: BTreeMap<String, Vec<String>>,
}

impl<'a> Wire<'a> {
    pub fn new(text: &'a str) -> Self {
        let mut wire = Self {
            text,
            spans: BTreeMap::new(),
            keys: BTreeMap::new(),
        };
        wire.scan(&mut 0, "$".into());
        wire
    }

    fn whitespace(&self, pos: &mut usize) {
        while self
            .text
            .as_bytes()
            .get(*pos)
            .is_some_and(u8::is_ascii_whitespace)
        {
            *pos += 1;
        }
    }

    fn string(&self, pos: &mut usize) {
        *pos += 1;
        loop {
            match self.text.as_bytes()[*pos] {
                b'"' => {
                    *pos += 1;
                    return;
                }
                b'\\' => *pos += 2,
                _ => *pos += 1,
            }
        }
    }

    fn scan(&mut self, pos: &mut usize, path: String) {
        self.whitespace(pos);
        let start = *pos;
        match self.text.as_bytes()[*pos] {
            b'{' => {
                *pos += 1;
                self.whitespace(pos);
                while self.text.as_bytes()[*pos] != b'}' {
                    let key_start = *pos;
                    self.string(pos);
                    let key: String = serde_json::from_str(&self.text[key_start..*pos]).unwrap();
                    self.keys.entry(path.clone()).or_default().push(key.clone());
                    self.whitespace(pos);
                    *pos += 1;
                    self.scan(pos, format!("{path}.{key}"));
                    self.whitespace(pos);
                    if self.text.as_bytes()[*pos] != b',' {
                        break;
                    }
                    *pos += 1;
                    self.whitespace(pos);
                }
                *pos += 1;
            }
            b'[' => {
                *pos += 1;
                self.whitespace(pos);
                let mut index = 0;
                while self.text.as_bytes()[*pos] != b']' {
                    self.scan(pos, format!("{path}[{index}]"));
                    index += 1;
                    self.whitespace(pos);
                    if self.text.as_bytes()[*pos] != b',' {
                        break;
                    }
                    *pos += 1;
                    self.whitespace(pos);
                }
                *pos += 1;
            }
            b'"' => self.string(pos),
            _ => {
                while self
                    .text
                    .as_bytes()
                    .get(*pos)
                    .is_some_and(|b| !b.is_ascii_whitespace() && !b",]}".contains(b))
                {
                    *pos += 1;
                }
            }
        }
        self.spans.insert(path, start..*pos);
    }

    pub fn value(&self, path: &str) -> Option<&str> {
        self.spans.get(path).map(|span| &self.text[span.clone()])
    }

    /// The members of the object at `path` in input order. serde_json's map
    /// is sorted, so its own order is used only for a value without a frame.
    pub fn keys(&self, path: &str, fields: &Map<String, Value>) -> Vec<String> {
        match self.keys.get(path) {
            Some(keys) => {
                let mut seen = std::collections::BTreeSet::new();
                keys.iter()
                    .filter(|key| fields.contains_key(*key) && seen.insert(*key))
                    .cloned()
                    .collect()
            }
            None => fields.keys().cloned().collect(),
        }
    }

    pub fn command_first(&self) -> bool {
        matches!((self.spans.get("$.command"), self.spans.get("$.arguments")), (Some(command), Some(arguments)) if command.start < arguments.start)
    }

    pub fn location(&self, path: &str, consumed: bool) -> String {
        let pos = if let Some(span) = self.spans.get(path) {
            if consumed {
                span.end
            } else {
                let raw = &self.text[span.clone()];
                if raw.starts_with(['{', '[', '"']) {
                    span.start + 1
                } else if raw == "true"
                    || raw == "false"
                    || raw == "null"
                    || raw.parse::<i64>().is_ok()
                {
                    span.end
                } else {
                    span.start
                }
            }
        } else {
            // LSP4J reads the single method parameter from an empty arguments array.
            let parent = path.strip_suffix("[0]").unwrap_or(path);
            self.spans
                .get(parent)
                .map_or(self.text.len(), |span| span.end)
        };
        let prefix = &self.text[..pos];
        let line = prefix.bytes().filter(|b| *b == b'\n').count() + 1;
        let column = prefix
            .rsplit('\n')
            .next()
            .unwrap_or("")
            .encode_utf16()
            .count()
            + 1;
        format!("at line {line} column {column} path {path}")
    }
}
