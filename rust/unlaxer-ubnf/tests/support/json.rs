//! Minimal JSON reader for the spec corpus fixtures.
//!
//! The Rust workspace has no third-party dependencies on purpose, and the spec corpus
//! (`unlaxer-dsl/src/test/resources/spec-corpus/ubnf/*.json`) must stay byte-identical to the
//! copies the Java test reads, so the fixtures are not reshaped into a Rust-friendlier format.
//! Only what the corpus actually uses is implemented.

use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Number(f64),
    Text(String),
    Array(Vec<Json>),
    Object(BTreeMap<String, Json>),
}

impl Json {
    /// The member under `key`, or `None` when absent (or when this is not an object).
    pub fn get(&self, key: &str) -> Option<&Json> {
        match self {
            Json::Object(members) => members.get(key),
            _ => None,
        }
    }

    /// The string value, or `None` for every other shape.
    pub fn text(&self) -> Option<&str> {
        match self {
            Json::Text(value) => Some(value),
            _ => None,
        }
    }

    /// The elements of an array, or an empty slice for every other shape.
    pub fn array(&self) -> &[Json] {
        match self {
            Json::Array(values) => values,
            _ => &[],
        }
    }

    /// `get(key)` as a string; panics with `context` when the member is missing.
    pub fn required_text(&self, key: &str, context: &str) -> &str {
        self.get(key)
            .and_then(Json::text)
            .unwrap_or_else(|| panic!("{context}: {key} が無いか文字列ではない"))
    }
}

pub fn parse(text: &str) -> Result<Json, String> {
    let bytes: Vec<char> = text.chars().collect();
    let mut reader = Reader {
        chars: &bytes,
        at: 0,
    };
    reader.skip_whitespace();
    let value = reader.value()?;
    reader.skip_whitespace();
    if reader.at != reader.chars.len() {
        return Err(format!("末尾に余分な入力がある (char {})", reader.at));
    }
    Ok(value)
}

struct Reader<'a> {
    chars: &'a [char],
    at: usize,
}

impl Reader<'_> {
    fn peek(&self) -> Option<char> {
        self.chars.get(self.at).copied()
    }

    fn bump(&mut self) -> Option<char> {
        let c = self.peek();
        if c.is_some() {
            self.at += 1;
        }
        c
    }

    fn skip_whitespace(&mut self) {
        while matches!(self.peek(), Some(' ' | '\t' | '\n' | '\r')) {
            self.at += 1;
        }
    }

    fn expect(&mut self, expected: char) -> Result<(), String> {
        match self.bump() {
            Some(c) if c == expected => Ok(()),
            other => Err(format!(
                "char {} で {expected:?} を期待したが {other:?}",
                self.at
            )),
        }
    }

    fn literal(&mut self, word: &str) -> Result<(), String> {
        for expected in word.chars() {
            self.expect(expected)?;
        }
        Ok(())
    }

    fn value(&mut self) -> Result<Json, String> {
        match self.peek() {
            Some('{') => self.object(),
            Some('[') => self.array(),
            Some('"') => Ok(Json::Text(self.string()?)),
            Some('t') => self.literal("true").map(|()| Json::Bool(true)),
            Some('f') => self.literal("false").map(|()| Json::Bool(false)),
            Some('n') => self.literal("null").map(|()| Json::Null),
            Some(_) => self.number(),
            None => Err("入力が尽きた".to_owned()),
        }
    }

    fn object(&mut self) -> Result<Json, String> {
        self.expect('{')?;
        let mut members = BTreeMap::new();
        self.skip_whitespace();
        if self.peek() == Some('}') {
            self.at += 1;
            return Ok(Json::Object(members));
        }
        loop {
            self.skip_whitespace();
            let key = self.string()?;
            self.skip_whitespace();
            self.expect(':')?;
            self.skip_whitespace();
            let value = self.value()?;
            members.insert(key, value);
            self.skip_whitespace();
            match self.bump() {
                Some(',') => {}
                Some('}') => return Ok(Json::Object(members)),
                other => return Err(format!("object で , か }} を期待したが {other:?}")),
            }
        }
    }

    fn array(&mut self) -> Result<Json, String> {
        self.expect('[')?;
        let mut values = Vec::new();
        self.skip_whitespace();
        if self.peek() == Some(']') {
            self.at += 1;
            return Ok(Json::Array(values));
        }
        loop {
            self.skip_whitespace();
            values.push(self.value()?);
            self.skip_whitespace();
            match self.bump() {
                Some(',') => {}
                Some(']') => return Ok(Json::Array(values)),
                other => return Err(format!("array で , か ] を期待したが {other:?}")),
            }
        }
    }

    fn string(&mut self) -> Result<String, String> {
        self.expect('"')?;
        let mut out = String::new();
        loop {
            match self.bump() {
                None => return Err("文字列が閉じていない".to_owned()),
                Some('"') => return Ok(out),
                Some('\\') => match self.bump() {
                    Some('"') => out.push('"'),
                    Some('\\') => out.push('\\'),
                    Some('/') => out.push('/'),
                    Some('b') => out.push('\u{8}'),
                    Some('f') => out.push('\u{c}'),
                    Some('n') => out.push('\n'),
                    Some('r') => out.push('\r'),
                    Some('t') => out.push('\t'),
                    Some('u') => out.push(self.unicode_escape()?),
                    other => return Err(format!("未知のエスケープ: {other:?}")),
                },
                Some(c) => out.push(c),
            }
        }
    }

    fn hex4(&mut self) -> Result<u32, String> {
        let mut value = 0u32;
        for _ in 0..4 {
            let c = self.bump().ok_or_else(|| "\\u が途中で切れた".to_owned())?;
            let digit = c
                .to_digit(16)
                .ok_or_else(|| format!("16 進ではない: {c:?}"))?;
            value = value * 16 + digit;
        }
        Ok(value)
    }

    /// `\uXXXX`, joining a surrogate pair into the code point it denotes.
    fn unicode_escape(&mut self) -> Result<char, String> {
        let high = self.hex4()?;
        if (0xD800..0xDC00).contains(&high) {
            self.expect('\\')?;
            self.expect('u')?;
            let low = self.hex4()?;
            if !(0xDC00..0xE000).contains(&low) {
                return Err(format!("後続のサロゲートが不正: {low:#x}"));
            }
            let code = 0x1_0000 + ((high - 0xD800) << 10) + (low - 0xDC00);
            return char::from_u32(code).ok_or_else(|| format!("符号位置が不正: {code:#x}"));
        }
        char::from_u32(high).ok_or_else(|| format!("符号位置が不正: {high:#x}"))
    }

    fn number(&mut self) -> Result<Json, String> {
        let start = self.at;
        while matches!(self.peek(), Some('-' | '+' | '.' | 'e' | 'E' | '0'..='9')) {
            self.at += 1;
        }
        let text: String = self.chars[start..self.at].iter().collect();
        text.parse::<f64>()
            .map(Json::Number)
            .map_err(|e| format!("数値として読めない {text:?}: {e}"))
    }
}
