//! Declarative coverage metadata. Loading never installs or invokes a provider.
use crate::source::Language;
use std::collections::{BTreeMap, BTreeSet};
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Support {
    Supported,
    Partial,
    External,
    Unsupported,
}
impl Support {
    fn parse(value: &str) -> Result<Self, String> {
        match value {
            "SUPPORTED" => Ok(Self::Supported),
            "PARTIAL" => Ok(Self::Partial),
            "EXTERNAL" => Ok(Self::External),
            "UNSUPPORTED" => Ok(Self::Unsupported),
            _ => Err(invalid()),
        }
    }
}
#[derive(Clone, Debug)]
pub struct LanguageProfile {
    rows: Vec<Vec<String>>,
    singles: BTreeMap<String, Vec<String>>,
    pub entries: BTreeMap<String, Support>,
    pub capabilities: BTreeMap<String, Support>,
}
#[derive(Clone, Debug)]
pub struct Selection {
    pub profile: LanguageProfile,
    pub language: Language,
}
impl Selection {
    pub fn allows_local(&self, capability: &str) -> bool {
        self.allows(capability, false)
    }
    pub fn allows(&self, capability: &str, provider_registered: bool) -> bool {
        let support = self.profile.capabilities.get(capability);
        local(support) || provider_registered && support == Some(&Support::External)
    }
}
fn local(support: Option<&Support>) -> bool {
    matches!(support, Some(Support::Supported | Support::Partial))
}
fn invalid() -> String {
    "invalid language profile".into()
}
fn identifier(value: &str) -> bool {
    value
        .as_bytes()
        .first()
        .is_some_and(u8::is_ascii_alphabetic)
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}
fn lowercase(value: &str) -> bool {
    value.as_bytes().first().is_some_and(u8::is_ascii_lowercase)
        && value
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}
impl LanguageProfile {
    pub fn parse(text: &str) -> Result<Self, String> {
        if text.len() > 65536 || !text.ends_with('\n') || text.contains(['\r', '\0']) {
            return Err(invalid());
        }
        let mut result = Self {
            rows: Vec::new(),
            singles: BTreeMap::new(),
            entries: BTreeMap::new(),
            capabilities: BTreeMap::new(),
        };
        let mut keys = BTreeSet::new();
        for line in text.strip_suffix('\n').unwrap().split('\n') {
            let row: Vec<String> = line.split('\t').map(str::to_owned).collect();
            if row.iter().any(String::is_empty) || result.rows.len() >= 512 {
                return Err(invalid());
            }
            let tag = row[0].as_str();
            let width = match tag {
                "profile" | "language" | "fixture" => 2,
                "package" | "target" | "tool" | "grammar" | "entry" | "capability" | "syntax"
                | "difference" => 3,
                "source" => 4,
                _ => return Err(invalid()),
            };
            if row.len() != width {
                return Err(invalid());
            }
            let repeated = matches!(tag, "entry" | "capability" | "syntax" | "difference");
            let key = if repeated {
                format!("{tag}\t{}", row[1])
            } else {
                tag.into()
            };
            if !keys.insert(key) || (repeated && !identifier(&row[1])) {
                return Err(invalid());
            }
            if matches!(tag, "entry" | "capability" | "syntax") {
                let support = Support::parse(&row[2])?;
                if tag == "entry" {
                    result.entries.insert(row[1].clone(), support);
                }
                if tag == "capability" {
                    result.capabilities.insert(row[1].clone(), support);
                }
            }
            if !repeated {
                result.singles.insert(tag.into(), row.clone());
            }
            result.rows.push(row);
        }
        let required: BTreeSet<_> = [
            "profile", "language", "package", "target", "tool", "grammar", "source", "fixture",
        ]
        .into_iter()
        .collect();
        let caps: BTreeSet<_> = [
            "PARSE",
            "AST",
            "CST",
            "VALIDATE",
            "COMPLETION",
            "HOVER",
            "DEFINITION",
            "RENAME",
            "FORMAT",
            "CODE_ACTION",
            "EXECUTE",
        ]
        .into_iter()
        .collect();
        if result
            .singles
            .keys()
            .map(String::as_str)
            .collect::<BTreeSet<_>>()
            != required
            || result.entries.is_empty()
            || result
                .capabilities
                .keys()
                .map(String::as_str)
                .collect::<BTreeSet<_>>()
                != caps
        {
            return Err(invalid());
        }
        let package: Vec<_> = result.singles["package"][1].split('/').collect();
        let version: Vec<_> = result.singles["package"][2].split('.').collect();
        let grammar = &result.singles["grammar"][1];
        if result.singles["profile"][1] != "1"
            || !lowercase(result.language())
            || package.len() != 2
            || !package.iter().all(|s| lowercase(s))
            || version.len() != 3
            || !version.iter().all(|s| {
                !s.is_empty()
                    && s.bytes().all(|b| b.is_ascii_digit())
                    && (s.len() == 1 || !s.starts_with('0'))
            })
            || !identifier(grammar)
            || grammar.contains('-')
        {
            return Err(invalid());
        }
        for file in [result.grammar_file(), result.fixture_file()] {
            let parts: Vec<_> = file.split('.').collect();
            if parts.len() != 2
                || parts.iter().any(|s| s.is_empty())
                || !parts[0]
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
                || !parts[1].bytes().all(|b| b.is_ascii_alphanumeric())
            {
                return Err(invalid());
            }
        }
        if !result.grammar_file().ends_with(".ubnf")
            || !result.singles["source"][1].starts_with("https://")
            || result.capabilities["EXECUTE"] != Support::Unsupported
        {
            return Err(invalid());
        }
        Ok(result)
    }
    pub fn language(&self) -> &str {
        &self.singles["language"][1]
    }
    pub fn grammar_file(&self) -> &str {
        &self.singles["grammar"][2]
    }
    pub fn fixture_file(&self) -> &str {
        &self.singles["fixture"][1]
    }
    pub fn rows(&self) -> &[Vec<String>] {
        &self.rows
    }
    pub fn identity(&self, entry: &str) -> Result<Language, String> {
        if !self.entries.contains_key(entry) || self.entries[entry] == Support::Unsupported {
            return Err(invalid());
        }
        Ok(Language {
            id: self.language().into(),
            package_id: self.singles["package"][1].clone(),
            version: self.singles["package"][2].clone(),
            grammar: self.singles["grammar"][1].clone(),
            entry: entry.into(),
        })
    }
    /// A profile never installs a provider; only intrinsic parse entries can be selected.
    pub fn select(&self, grammar: &str, entry: &str) -> Result<Selection, String> {
        let language = self.identity(entry)?;
        if language.grammar != grammar
            || !local(self.entries.get(entry))
            || !local(self.capabilities.get("PARSE"))
        {
            return Err("profile grammar/entry cannot be parsed locally".into());
        }
        Ok(Selection {
            profile: self.clone(),
            language,
        })
    }
    pub fn canonical_tsv(&self) -> String {
        let mut lines: Vec<_> = self.rows.iter().map(|row| row.join("\t")).collect();
        lines.sort();
        lines.join("\n") + "\n"
    }
}
