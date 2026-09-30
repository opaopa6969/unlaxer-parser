//! Data-only adapter declarations and resolution. Neither construction nor lookup loads
//! a Java class, compiles a Rust provider, or invokes a token parser.
use std::collections::{BTreeMap, BTreeSet};
use unlaxer_ubnf::ast::{GlobalSetting, GrammarDecl, SettingValue, Span};

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

    /// Returns every invalid or duplicate declaration, with the offending setting span.
    /// Valid declarations are retained so all token references can also be checked.
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
        if !matches!(entry.key.as_str(), "id" | "version" | "java" | "rust")
            || !seen.insert(&entry.key)
        {
            return Err(entries
                .iter()
                .find(|e| e.key == "id")
                .map_or_else(|| "tokenAdapter".to_owned(), |e| e.value.clone()));
        }
        fields.insert(entry.key.as_str(), entry.value.as_str());
    }
    let id = fields.get("id").copied().unwrap_or("tokenAdapter");
    if fields.len() != 4 || !valid_id(id) {
        return Err(id.to_owned());
    }
    let version = valid_version(fields["version"]).ok_or_else(|| id.to_owned())?;
    let java_class = fields["java"];
    let rust_function = fields["rust"];
    if !valid_java_fqn(java_class) || !valid_rust_path(rust_function) {
        return Err(id.to_owned());
    }
    Ok(CustomAdapter {
        id: id.to_owned(),
        version,
        java_class: java_class.to_owned(),
        rust_function: rust_function.to_owned(),
    })
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
