//! Read-only capability inventory followed by structural validation when possible.
//! String API is I/O-free; file API resolves local lexical modules.
use crate::adapters::{feature_diagnostics, token_contract_diagnostics, AdapterRegistry};
use std::fmt::Write;
use unlaxer_ubnf::*;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Diagnostic {
    pub code: &'static str,
    pub subject: String,
    pub span: Option<Span>,
}

impl Diagnostic {
    pub fn severity(&self) -> &'static str {
        "error"
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Report {
    pub portable: bool,
    /// passed / blocked (by capabilities) / failed / unavailable (syntax error)
    pub structure: &'static str,
    pub diagnostics: Vec<Diagnostic>,
}

impl Report {
    pub fn to_json(&self) -> String {
        let diagnostics = self
            .diagnostics
            .iter()
            .map(|d| {
                let span = d.span.map_or_else(
                    || "null".into(),
                    |s| {
                        format!(
                            "{{\"start\":{},\"end\":{}}}",
                            s.codepoint_start, s.codepoint_end
                        )
                    },
                );
                format!(
                    "{{\"code\":{},\"severity\":\"error\",\"span\":{},\"subject\":{}}}",
                    quote(d.code),
                    span,
                    quote(&d.subject)
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        format!("{{\"schemaVersion\":1,\"target\":\"rust\",\"portable\":{},\"structure\":{},\"diagnostics\":[{}]}}",
            self.portable, quote(self.structure), diagnostics)
    }
}

fn quote(value: &str) -> String {
    let mut out = String::from("\"");
    for ch in value.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            ch if ch < ' ' => {
                write!(out, "\\u{:04x}", ch as u32).unwrap();
            }
            ch => out.push(ch),
        }
    }
    out.push('"');
    out
}

/// Inventory every unsupported construct in the complete AST. Structural errors
/// remain fail-first per grammar, and are explicitly blocked by capability errors.
pub fn check(source: &str) -> Report {
    let snapshot = match parse_with_source(source) {
        Ok(snapshot) => snapshot,
        Err(_) => {
            return Report {
                portable: false,
                structure: "unavailable",
                diagnostics: vec![Diagnostic {
                    code: "P-SYNTAX",
                    subject: "UBNF syntax".into(),
                    span: None,
                }],
            }
        }
    };
    check_ast(snapshot.ast())
}

pub fn check_file(path: &std::path::Path) -> Report {
    if let Ok(source) = std::fs::read_to_string(path) {
        match parse(&source) {
            Ok(file)
                if file.grammars.iter().any(|g| {
                    !g.imports.is_empty()
                        && g.settings.iter().any(|s| {
                            s.key == "ubnf"
                                && matches!(&s.value, SettingValue::String(v) if v == "v2")
                        })
                }) => {}
            _ => return check(&source),
        }
    }
    match crate::modules::load(path) {
        Ok(file) => {
            let mut report = check_ast(&file);
            for diagnostic in &mut report.diagnostics {
                diagnostic.span = None;
            }
            report
                .diagnostics
                .sort_by(|a, b| a.code.cmp(b.code).then_with(|| a.subject.cmp(&b.subject)));
            report.diagnostics.dedup();
            report
        }
        Err(_) => Report {
            portable: false,
            structure: "blocked",
            diagnostics: vec![Diagnostic {
                code: "P-MODULE",
                subject: "UBNF module resolution".into(),
                span: None,
            }],
        },
    }
}

fn check_ast(file: &UbnfFile) -> Report {
    let mut inventory = Inventory {
        diagnostics: Vec::new(),
    };
    if file.grammars.len() != 1 {
        inventory.add("P-GRAMMAR-COUNT", "expected one grammar", file.span);
    }
    for grammar in &file.grammars {
        inventory.grammar(grammar);
    }
    let structure = if inventory.diagnostics.is_empty() {
        for grammar in &file.grammars {
            let result = crate::lowering::lower(grammar)
                .and_then(|ir| unlaxer_codegen::validate_ir(&ir).map_err(|e| e.to_string()));
            if result.is_err() {
                inventory.add("P-STRUCTURE", "Rust structural constraints", grammar.span);
            }
        }
        if inventory.diagnostics.is_empty() {
            "passed"
        } else {
            "failed"
        }
    } else {
        "blocked"
    };
    inventory.diagnostics.sort_by(|a, b| {
        let key = |d: &Diagnostic| {
            d.span.map_or((usize::MAX, usize::MAX), |s| {
                (s.codepoint_start, s.codepoint_end)
            })
        };
        key(a)
            .cmp(&key(b))
            .then_with(|| a.code.cmp(b.code))
            .then_with(|| a.subject.cmp(&b.subject))
    });
    inventory.diagnostics.dedup();
    Report {
        portable: inventory.diagnostics.is_empty(),
        structure,
        diagnostics: inventory.diagnostics,
    }
}

struct Inventory {
    diagnostics: Vec<Diagnostic>,
}

impl Inventory {
    fn add(&mut self, code: &'static str, subject: impl Into<String>, span: Span) {
        self.diagnostics.push(Diagnostic {
            code,
            subject: subject.into(),
            span: Some(span),
        });
    }
    fn grammar(&mut self, grammar: &GrammarDecl) {
        let (adapter_registry, adapter_issues) = AdapterRegistry::from_grammar(grammar);
        for issue in adapter_issues {
            self.add(issue.code, issue.subject, issue.span);
        }
        for issue in token_contract_diagnostics(grammar) {
            self.add(issue.code, issue.subject, issue.span);
        }
        for issue in feature_diagnostics(grammar) {
            self.add(issue.code, issue.subject, issue.span);
        }
        for issue in crate::token_stream::problems(grammar) {
            self.add(issue.code, issue.subject, issue.span);
        }
        for import in &grammar.imports {
            self.add("P-IMPORT", &import.path, import.span);
        }
        for setting in &grammar.settings {
            match (&*setting.key, &setting.value) {
                ("package" | "memoSafeToken" | "tokenStream", SettingValue::String(_)) => {}
                ("ubnf", SettingValue::String(value)) if value == "v1" || value == "v2" => {}
                ("feature", SettingValue::String(value))
                    if matches!(
                        value.as_str(),
                        "tokenContractsV1"
                            | "contextAccessorsV1"
                            | "tokenProgressContractsV1"
                            | "declarativeTokensV1"
                    ) => {}
                ("tokenAdapter" | "tokenContract", _) => {}
                ("whitespace", SettingValue::String(value)) => {
                    if !whitespace(value) {
                        self.add("P-WHITESPACE", value, setting.value_span);
                    }
                }
                _ => self.add("P-SETTING", &setting.key, setting.span),
            }
        }
        for token in &grammar.tokens {
            match &token.kind {
                TokenKind::Declarative { .. } => {}
                TokenKind::Simple { parser_class } => {
                    if crate::lowering::token_expression(&token.kind).is_err() {
                        self.add("P-EXTERNAL-TOKEN", parser_class, token.span);
                    }
                }
                TokenKind::Adapter { id, version } => {
                    if let Err((code, subject)) = adapter_registry.resolve(id, version) {
                        self.add(code, subject, token.span);
                    }
                }
                TokenKind::Regex { .. } => self.add("P-TOKEN-KIND", "REGEX", token.span),
                TokenKind::CaseInsensitive { .. } => self.add("P-TOKEN-KIND", "CI", token.span),
                TokenKind::Until { .. }
                | TokenKind::Negation { .. }
                | TokenKind::Lookahead { .. }
                | TokenKind::NegativeLookahead { .. }
                | TokenKind::Any
                | TokenKind::Eof
                | TokenKind::Empty
                | TokenKind::CharRange { .. } => {}
            }
        }
        for rule in &grammar.rules {
            let skip = rule
                .annotations
                .iter()
                .any(|annotation| matches!(annotation.kind, AnnotationKind::Skip));
            for annotation in &rule.annotations {
                if skip && matches!(annotation.kind, AnnotationKind::Mapping { .. }) {
                    continue;
                }
                self.annotation(annotation);
            }
            self.body(&rule.body);
        }
    }
    fn annotation(&mut self, annotation: &Annotation) {
        let span = annotation.span;
        match &annotation.kind {
            AnnotationKind::Root
            | AnnotationKind::LeftAssoc
            | AnnotationKind::RightAssoc
            | AnnotationKind::LongestChoice
            | AnnotationKind::PredictiveChoice
            | AnnotationKind::Precedence { .. }
            | AnnotationKind::Declares { .. }
            | AnnotationKind::Backref { .. }
            | AnnotationKind::Catalog { .. } => {}
            AnnotationKind::Mapping { class_name, params } => {
                if !identifier(class_name) {
                    self.add("P-MAPPING-TYPE", class_name, span);
                }
                for param in params {
                    if !identifier(param) || matches!(&**param, "span" | "semantics") {
                        self.add("P-FIELD-NAME", param, span);
                    }
                }
            }
            AnnotationKind::Whitespace { style } => {
                if let Some(style) = style {
                    if !whitespace(style) {
                        self.add("P-WHITESPACE", style, span);
                    }
                }
            }
            AnnotationKind::Interleave { profile } => {
                if !matches!(profile.trim(), "javaStyle" | "commentsAndSpaces") {
                    self.add("P-INTERLEAVE", profile, span);
                }
            }
            AnnotationKind::ScopeTree { mode } => {
                if !matches!(mode.trim(), "lexical" | "dynamic") {
                    self.add("P-SCOPE-MODE", mode, span);
                }
            }
            AnnotationKind::Eval { .. } => self.add("P-ANNOTATION", "eval", span),
            AnnotationKind::Doc { .. } => {}
            AnnotationKind::Recovery { .. } => self.add("P-ANNOTATION", "recovery", span),
            AnnotationKind::Skip => {}
            AnnotationKind::Simple { name } => self.add("P-ANNOTATION", name, span),
            AnnotationKind::CommonField { .. } => self.add("P-ANNOTATION", "commonField", span),
            AnnotationKind::Enum => self.add("P-ANNOTATION", "enum", span),
        }
    }
    fn body(&mut self, body: &RuleBody) {
        for sequence in &body.alternatives {
            for annotated in &sequence.elements {
                if let (Some(name), Some(span)) =
                    (&annotated.typeof_constraint, annotated.typeof_span)
                {
                    self.add("P-TYPEOF", name, span);
                }
                self.element(&annotated.element);
            }
        }
    }
    fn element(&mut self, element: &AtomicElement) {
        match &element.kind {
            ElementKind::Group(body) | ElementKind::Optional(body) | ElementKind::Repeat(body) => {
                self.body(body)
            }
            ElementKind::OneOrMore(element) | ElementKind::BoundedRepeat { element, .. } => {
                self.element(element)
            }
            ElementKind::Separated { element, separator } => {
                self.element(element);
                self.element(separator);
            }
            ElementKind::RuleRef {
                namespace: Some(namespace),
                name,
            } => self.add(
                "P-QUALIFIED-REFERENCE",
                format!("{namespace}.{name}"),
                element.span,
            ),
            ElementKind::Error(_) => {}
            ElementKind::Terminal(value) if value.is_empty() => {
                self.add("P-EMPTY-LITERAL", "empty literal", element.span)
            }
            ElementKind::Terminal(_)
            | ElementKind::RuleRef {
                namespace: None, ..
            } => {}
        }
    }
}

fn identifier(value: &str) -> bool {
    let mut chars = value.chars();
    chars.next().is_some_and(|ch| ch.is_ascii_alphabetic())
        && chars.all(|ch| ch.is_ascii_alphanumeric() || ch == '_')
        && !matches!(value, "Self" | "self" | "super" | "crate")
}

fn whitespace(value: &str) -> bool {
    value.trim().eq_ignore_ascii_case("javaStyle") || value.trim().eq_ignore_ascii_case("none")
}
