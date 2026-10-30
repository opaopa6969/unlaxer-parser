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

    /// The credentials origin of a non-scalar HTTPS source, decided by Java's
    /// `URI.create` rules over the UTF-16 units. Nothing here is ever sent: Java's
    /// HTTP request encoder cannot encode an unpaired unit, so the caller rejects
    /// after the credentials/CA/token checks. Scalar URLs keep `fetch(&str)`.
    pub(super) fn https_origin(&self) -> Result<String, String> {
        let Self::Units(units) = self else {
            unreachable!("surrogate URL only")
        };
        match super::java_uri::classify(units) {
            super::java_uri::Https::Origin(origin) => Ok(origin),
            super::java_uri::Https::Invalid => Err(super::error("invalid HTTPS artifact URL")),
            super::java_uri::Https::Omit => Err(super::error(
                "HTTPS artifact URL must omit credentials, query and fragment",
            )),
        }
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
