//! Lossless strings at package boundaries. Ordinary JSON stays UTF-8; only strings
//! with unpaired escapes own UTF-16 units. Neither representation names another file.
#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub(super) enum Text {
    Scalar(Box<str>),
    Units(Box<[u16]>),
}

impl From<String> for Text {
    fn from(value: String) -> Self {
        Self::Scalar(value.into_boxed_str())
    }
}
impl From<&str> for Text {
    fn from(value: &str) -> Self {
        Self::Scalar(value.into())
    }
}
impl Text {
    pub(super) fn as_str(&self) -> Option<&str> {
        match self {
            Self::Scalar(text) => Some(text),
            Self::Units(_) => None,
        }
    }

    // For the existing ASCII-only ID validators. A non-scalar key is always invalid;
    // this value is never used for file identity, source text, or transport.
    pub(super) fn ascii_candidate(&self) -> &str {
        self.as_str().unwrap_or("\0")
    }

    pub(super) fn cmp_str(&self, other: &str) -> std::cmp::Ordering {
        match self {
            Self::Scalar(text) => text.as_ref().cmp(other),
            Self::Units(_) => std::cmp::Ordering::Greater,
        }
    }

    pub(super) fn from_marked(text: &str) -> Self {
        let mut units = Vec::with_capacity(text.len());
        let mut chars = text.chars();
        while let Some(c) = chars.next() {
            if c == '\0' {
                match chars.next().expect("quoted NUL marker") {
                    '0' => units.push(0),
                    's' => {
                        let unit = chars.by_ref().take(4).fold(0, |unit, hex| {
                            unit * 16 + hex.to_digit(16).expect("surrogate hex") as u16
                        });
                        units.push(unit);
                    }
                    _ => unreachable!("only NUL and surrogate markers"),
                }
            } else {
                units.extend_from_slice(c.encode_utf16(&mut [0; 2]));
            }
        }
        match String::from_utf16(&units) {
            Ok(scalar) => scalar.into(),
            Err(_) => Self::Units(units.into_boxed_slice()),
        }
    }

    pub(super) fn validate_file(&self) -> Result<(), String> {
        let Self::Units(units) = self else {
            return super::file(self.as_str().unwrap());
        };
        // Same two passes as Java: ISO controls and separators before dot components.
        if units.is_empty()
            || units
                .iter()
                .any(|u| *u <= 0x1f || (0x7f..=0x9f).contains(u) || matches!(*u, 0x5c | 0x3a))
            || units.first() == Some(&0x2f)
        {
            return Err(super::error("invalid package file path"));
        }
        if units
            .split(|u| *u == 0x2f)
            .any(|part| matches!(part, [] | [0x2e] | [0x2e, 0x2e]))
        {
            return Err(super::error("package file path escapes boundary"));
        }
        Ok(())
    }

    pub(super) fn ends_with_ubnf(&self) -> bool {
        match self {
            Self::Scalar(text) => text.ends_with(".ubnf"),
            Self::Units(units) => units.ends_with(&[0x2e, 0x75, 0x62, 0x6e, 0x66]),
        }
    }

    /// An ASCII-only URI for syntax/origin/credential validation, never for I/O.
    /// The caller must reject before transport: percent-encoding cannot make an
    /// unpaired Java code unit into a valid request path.
    pub(super) fn https_probe(&self) -> Result<String, String> {
        let Self::Units(units) = self else {
            unreachable!("surrogate URL only")
        };
        let invalid = || super::error("invalid HTTPS artifact URL");
        for (i, unit) in units.iter().copied().enumerate() {
            if unit <= 0x20
                || (0x7f..=0x9f).contains(&unit)
                || matches!(
                    unit,
                    0x22 | 0x3c | 0x3e | 0x5c | 0x5e | 0x60 | 0x7b | 0x7c | 0x7d
                )
            {
                return Err(invalid());
            }
            if unit == 0x25
                && !units.get(i + 1..i + 3).is_some_and(|hex| {
                    hex.iter()
                        .all(|u| *u < 128 && (*u as u8).is_ascii_hexdigit())
                })
            {
                return Err(invalid());
            }
        }
        let omit = || super::error("HTTPS artifact URL must omit credentials, query and fragment");
        let prefix: Vec<_> = "https://".encode_utf16().collect();
        if !units.starts_with(&prefix) {
            return Err(omit());
        }
        let authority = units[prefix.len()..]
            .split(|u| matches!(*u, 0x2f | 0x3f | 0x23))
            .next()
            .unwrap();
        // URI.create permits registry authorities, whose getHost() is null. HTTP's
        // Uri parser would reject these earlier with a different diagnostic.
        if authority.is_empty() || authority.iter().any(|u| *u > 127) {
            if authority.contains(&0x5b) || authority.contains(&0x5d) {
                return Err(invalid());
            }
            return Err(omit());
        }
        if units.iter().filter(|u| **u == 0x23).count() > 1 {
            return Err(invalid());
        }
        let mut probe = String::new();
        for unit in char::decode_utf16(units.iter().copied()) {
            match unit {
                Ok(c) if c.is_ascii() => probe.push(c),
                Ok(c) => {
                    if c.is_whitespace() {
                        return Err(invalid());
                    }
                    for byte in c.encode_utf8(&mut [0; 4]).bytes() {
                        probe.push_str(&format!("%{byte:02X}"));
                    }
                }
                Err(e) => {
                    let unit = e.unpaired_surrogate();
                    for byte in [
                        0xe0 | (unit >> 12) as u8,
                        0x80 | ((unit >> 6) & 0x3f) as u8,
                        0x80 | (unit & 0x3f) as u8,
                    ] {
                        probe.push_str(&format!("%{byte:02X}"));
                    }
                }
            }
        }
        Ok(probe)
    }

    pub(super) fn path_text(&self) -> Result<&str, String> {
        self.as_str().ok_or_else(|| {
            let Self::Units(units) = self else {
                unreachable!()
            };
            // Java's UnixPath rejects before I/O. Its diagnostic is then written with
            // the JDK UTF-8 encoder's '?' replacement; the filename itself is untouched.
            let message: String = char::decode_utf16(units.iter().copied())
                .map(|c| c.unwrap_or('?'))
                .collect();
            format!("Malformed input or input contains unmappable characters: {message}")
        })
    }
}

#[cfg(test)]
impl PartialEq<str> for Text {
    fn eq(&self, other: &str) -> bool {
        self.as_str() == Some(other)
    }
}
#[cfg(test)]
impl PartialEq<&str> for Text {
    fn eq(&self, other: &&str) -> bool {
        self.as_str() == Some(*other)
    }
}
