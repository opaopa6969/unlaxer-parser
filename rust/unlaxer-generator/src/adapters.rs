//! Data-only adapter declarations and resolution. Neither construction nor lookup loads
//! a Java class, compiles a Rust provider, or invokes a token parser.
use std::collections::{BTreeMap, BTreeSet};
use unlaxer_ubnf::ast::{GlobalSetting, GrammarDecl, SettingValue, Span, TokenKind};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BuiltinAdapter {
    StringLiteral,
    CodeStart,
    CodeEnd,
    LongCodeBlock,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CustomAdapter {
    pub id: String,
    pub version: u32,
    pub java_class: String,
    pub rust_function: String,
    pub accepts: Option<String>,
    pub failure: Option<String>,
    pub consumes: Option<String>,
    pub context_accessors: BTreeSet<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AdapterBinding {
    Builtin(BuiltinAdapter),
    Custom(CustomAdapter),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AdapterDiagnostic {
    pub code: &'static str,
    pub subject: String,
    pub span: Span,
}

#[derive(Clone, Debug)]
pub struct AdapterRegistry {
    bindings: BTreeMap<(String, u32), AdapterBinding>,
}

impl AdapterRegistry {
    pub fn builtins() -> Self {
        let mut bindings = BTreeMap::new();
        for (id, builtin) in [
            ("tinyexpression.string", BuiltinAdapter::StringLiteral),
            ("tinyexpression.code-start", BuiltinAdapter::CodeStart),
            ("tinyexpression.code-end", BuiltinAdapter::CodeEnd),
            (
                "tinyexpression.long-code-block",
                BuiltinAdapter::LongCodeBlock,
            ),
        ] {
            bindings.insert((id.to_owned(), 1), AdapterBinding::Builtin(builtin));
        }
        Self { bindings }
    }

    /// Returns declaration and token-reference diagnostics with their source spans.
    /// Like Java's build(grammar, null), stable order is code then UTF-16 subject;
    /// positioned consumers (portability) apply their own final source ordering.
    pub fn from_grammar(grammar: &GrammarDecl) -> (Self, Vec<AdapterDiagnostic>) {
        let mut registry = Self::builtins();
        let mut diagnostics = Vec::new();
        for setting in grammar.settings.iter().filter(|s| s.key == "tokenAdapter") {
            match parse_definition(setting) {
                Ok(custom) => {
                    let key = (custom.id.clone(), custom.version);
                    match registry.bindings.entry(key) {
                        std::collections::btree_map::Entry::Vacant(slot) => {
                            slot.insert(AdapterBinding::Custom(custom));
                        }
                        std::collections::btree_map::Entry::Occupied(_) => {
                            diagnostics.push(AdapterDiagnostic {
                                code: "P-ADAPTER-DUPLICATE",
                                subject: custom.id,
                                span: setting.span,
                            });
                        }
                    }
                }
                Err(subject) => diagnostics.push(AdapterDiagnostic {
                    code: "P-ADAPTER-DEFINITION",
                    subject,
                    span: setting.span,
                }),
            }
        }
        for token in &grammar.tokens {
            if let TokenKind::Adapter { id, version } = &token.kind {
                if let Err((code, subject)) = registry.resolve(id, version) {
                    diagnostics.push(AdapterDiagnostic {
                        code,
                        subject,
                        span: token.span,
                    });
                }
            }
        }
        diagnostics.sort_by(|a, b| {
            a.code
                .cmp(b.code)
                .then_with(|| a.subject.encode_utf16().cmp(b.subject.encode_utf16()))
        });
        (registry, diagnostics)
    }

    /// Resolve a parsed token reference without loading or executing its provider.
    pub fn resolve(
        &self,
        id: &str,
        version: &str,
    ) -> Result<&AdapterBinding, (&'static str, String)> {
        if !valid_id(id) {
            return Err(("P-ADAPTER-DEFINITION", id.to_owned()));
        }
        let Some(version) = valid_version(version) else {
            return Err(("P-ADAPTER-DEFINITION", id.to_owned()));
        };
        if let Some(binding) = self.bindings.get(&(id.to_owned(), version)) {
            return Ok(binding);
        }
        if self.bindings.keys().any(|(known, _)| known == id) {
            Err(("P-ADAPTER-VERSION", id.to_owned()))
        } else {
            Err(("P-ADAPTER-UNKNOWN", id.to_owned()))
        }
    }

    pub fn contains(&self, id: &str, version: u32) -> bool {
        self.bindings.contains_key(&(id.to_owned(), version))
    }
}

fn parse_definition(setting: &GlobalSetting) -> Result<CustomAdapter, String> {
    let SettingValue::Block(entries) = &setting.value else {
        return Err("tokenAdapter".into());
    };
    let mut fields = BTreeMap::new();
    let mut seen = BTreeSet::new();
    for entry in entries {
        if !matches!(
            entry.key.as_str(),
            "id" | "version" | "java" | "rust" | "accepts" | "failure" | "consumes" | "context"
        ) || !seen.insert(&entry.key)
        {
            return Err(entries
                .iter()
                .find(|e| e.key == "id")
                .map_or_else(|| "tokenAdapter".to_owned(), |e| e.value.clone()));
        }
        fields.insert(entry.key.as_str(), entry.value.as_str());
    }
    let id = fields.get("id").copied().unwrap_or("tokenAdapter");
    if !["id", "version", "java", "rust"]
        .iter()
        .all(|key| fields.contains_key(key))
        || !valid_id(id)
    {
        return Err(id.to_owned());
    }
    let version = valid_version(fields["version"]).ok_or_else(|| id.to_owned())?;
    let java_class = fields["java"];
    let rust_function = fields["rust"];
    if !valid_java_fqn(java_class) || !valid_rust_path(rust_function) {
        return Err(id.to_owned());
    }
    let accepts = fields.get("accepts").map(|value| (*value).to_owned());
    if accepts.as_ref().is_some_and(|value| java_is_blank(value)) {
        return Err(id.to_owned());
    }
    let failure = fields.get("failure").map(|value| (*value).to_owned());
    if failure
        .as_deref()
        .is_some_and(|value| !matches!(value, "no-consume" | "may-consume"))
    {
        return Err(id.to_owned());
    }
    let consumes = fields.get("consumes").map(|value| (*value).to_owned());
    if consumes
        .as_deref()
        .is_some_and(|value| !matches!(value, "always" | "maybe" | "never"))
    {
        return Err(id.to_owned());
    }
    let context_accessors = context_accessors(fields.get("context").copied().unwrap_or(""))
        .ok_or_else(|| id.to_owned())?;
    Ok(CustomAdapter {
        id: id.to_owned(),
        version,
        java_class: java_class.to_owned(),
        rust_function: rust_function.to_owned(),
        accepts,
        failure,
        consumes,
        context_accessors,
    })
}

// String.isBlank uses Character.isWhitespace, not Unicode White_Space (Rust trim).
// In particular NBSP, figure space, narrow NBSP and NEL are not Java whitespace.
fn java_is_blank(value: &str) -> bool {
    value.chars().all(|c| {
        matches!(c, '\t'..='\r' | '\u{1c}'..='\u{20}' | '\u{1680}'
            | '\u{2000}'..='\u{2006}' | '\u{2008}'..='\u{200a}'
            | '\u{2028}' | '\u{2029}' | '\u{205f}' | '\u{3000}')
    })
}

fn context_accessors(raw: &str) -> Option<BTreeSet<String>> {
    let mut names = BTreeSet::new();
    if !java_is_blank(raw) {
        for name in raw.split(',') {
            // Java String.trim removes only UTF-16 units <= U+0020.
            let name = name.trim_matches(|c| c <= '\u{20}');
            if !matches!(
                name,
                "source" | "remaining" | "position" | "matchedPosition" | "bindings"
            ) || !names.insert(name.to_owned())
            {
                return None;
            }
        }
    }
    Some(names)
}

/// Validates source-visible format-2 contracts. Contracts supplement a token
/// binding, so they apply equally to a direct host class and to an ADAPTER.
pub fn token_contract_diagnostics(grammar: &GrammarDecl) -> Vec<AdapterDiagnostic> {
    let format2 = grammar.settings.iter().any(|setting| {
        setting.key == "ubnf"
            && matches!(&setting.value, SettingValue::String(value) if value == "v2")
    });
    let tokens: BTreeSet<_> = grammar
        .tokens
        .iter()
        .map(|token| token.name.as_str())
        .collect();
    let mut seen = BTreeSet::new();
    let mut diagnostics = Vec::new();
    for setting in grammar
        .settings
        .iter()
        .filter(|setting| setting.key == "tokenContract")
    {
        if !format2 {
            diagnostics.push(AdapterDiagnostic {
                code: "P-TOKEN-CONTRACT-VERSION",
                subject: "tokenContract".into(),
                span: setting.span,
            });
            continue;
        }
        let SettingValue::Block(entries) = &setting.value else {
            diagnostics.push(AdapterDiagnostic {
                code: "P-TOKEN-CONTRACT-DEFINITION",
                subject: "tokenContract".into(),
                span: setting.span,
            });
            continue;
        };
        let mut fields = BTreeMap::new();
        let mut valid = true;
        for entry in entries {
            if !matches!(
                entry.key.as_str(),
                "token" | "accepts" | "failure" | "consumes" | "context"
            ) {
                valid = false;
                continue;
            }
            // Java putIfAbsent retains the first value even for an invalid block.
            match fields.entry(entry.key.as_str()) {
                std::collections::btree_map::Entry::Vacant(slot) => {
                    slot.insert(entry.value.as_str());
                }
                std::collections::btree_map::Entry::Occupied(_) => valid = false,
            }
        }
        let token = fields.get("token").copied().unwrap_or("tokenContract");
        let context = fields.get("context").copied().unwrap_or("");
        valid &= fields.len() == 5
            && tokens.contains(token)
            && !java_is_blank(fields["accepts"])
            && matches!(fields["failure"], "no-consume" | "may-consume")
            && matches!(fields["consumes"], "always" | "maybe" | "never")
            && context_accessors(context).is_some();
        if !valid {
            diagnostics.push(AdapterDiagnostic {
                code: "P-TOKEN-CONTRACT-DEFINITION",
                subject: token.into(),
                span: setting.span,
            });
        } else if !seen.insert(token.to_owned()) {
            diagnostics.push(AdapterDiagnostic {
                code: "P-TOKEN-CONTRACT-DUPLICATE",
                subject: token.into(),
                span: setting.span,
            });
        }
    }
    // Java build(grammar, null) sorts by code only, preserving equal-code order.
    diagnostics.sort_by(|a, b| a.code.cmp(b.code));
    diagnostics
}

pub fn feature_diagnostics(grammar: &GrammarDecl) -> Vec<AdapterDiagnostic> {
    let format2 = grammar.settings.iter().any(|setting| {
        setting.key == "ubnf"
            && matches!(&setting.value, SettingValue::String(value) if value == "v2")
    });
    let mut seen = BTreeSet::new();
    grammar
        .settings
        .iter()
        .filter(|setting| setting.key == "feature")
        .filter_map(|setting| {
            // UBNFFeatures.validate checks the format before inspecting the value.
            if !format2 {
                return Some(AdapterDiagnostic {
                    code: "E-FEATURE-VERSION",
                    subject: "feature".into(),
                    span: setting.span,
                });
            }
            let SettingValue::String(value) = &setting.value else {
                return Some(AdapterDiagnostic {
                    code: "E-FEATURE-UNKNOWN",
                    subject: "feature".into(),
                    span: setting.span,
                });
            };
            let code = if !matches!(
                value.as_str(),
                "tokenContractsV1"
                    | "contextAccessorsV1"
                    | "parseBindingsV1"
                    | "tokenProgressContractsV1"
                    | "declarativeTokensV1"
            ) {
                Some("E-FEATURE-UNKNOWN")
            } else if !seen.insert(value.clone()) {
                Some("E-FEATURE-DUPLICATE")
            } else {
                None
            };
            code.map(|code| AdapterDiagnostic {
                code,
                subject: if code == "E-FEATURE-DUPLICATE" {
                    value.clone()
                } else {
                    "feature".into()
                },
                span: setting.span,
            })
        })
        .collect()
}

pub fn valid_id(id: &str) -> bool {
    let mut expect_part = true;
    for (index, byte) in id.bytes().enumerate() {
        if expect_part {
            if !(byte.is_ascii_lowercase() || index > 0 && byte.is_ascii_digit()) {
                return false;
            }
            expect_part = false;
        } else if byte == b'.' || byte == b'-' {
            expect_part = true;
        } else if !byte.is_ascii_lowercase() && !byte.is_ascii_digit() {
            return false;
        }
    }
    !expect_part
}

pub fn valid_version(raw: &str) -> Option<u32> {
    if raw.is_empty() || !raw.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    raw.parse::<u32>()
        .ok()
        .filter(|value| (1..=i32::MAX as u32).contains(value))
}

fn valid_segment(segment: &str) -> bool {
    let mut chars = segment.bytes();
    chars
        .next()
        .is_some_and(|first| first.is_ascii_alphabetic() || first == b'_')
        && chars.all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
}

pub fn valid_java_fqn(path: &str) -> bool {
    let parts: Vec<_> = path.split('.').collect();
    parts.len() >= 2
        && parts.into_iter().all(|part| {
            valid_segment(part)
                && part != "_"
                && !matches!(
                    part,
                    "abstract"
                        | "assert"
                        | "boolean"
                        | "break"
                        | "byte"
                        | "case"
                        | "catch"
                        | "char"
                        | "class"
                        | "const"
                        | "continue"
                        | "default"
                        | "do"
                        | "double"
                        | "else"
                        | "enum"
                        | "extends"
                        | "final"
                        | "finally"
                        | "float"
                        | "for"
                        | "goto"
                        | "if"
                        | "implements"
                        | "import"
                        | "instanceof"
                        | "int"
                        | "interface"
                        | "long"
                        | "native"
                        | "new"
                        | "package"
                        | "private"
                        | "protected"
                        | "public"
                        | "return"
                        | "short"
                        | "static"
                        | "strictfp"
                        | "super"
                        | "switch"
                        | "synchronized"
                        | "this"
                        | "throw"
                        | "throws"
                        | "transient"
                        | "try"
                        | "void"
                        | "volatile"
                        | "while"
                        | "true"
                        | "false"
                        | "null"
                        | "record"
                        | "var"
                        | "yield"
                        | "sealed"
                        | "permits"
                )
        })
}

pub fn valid_rust_path(path: &str) -> bool {
    unlaxer_codegen::valid_custom_token_path(path)
}
