use crate::text::{self, Lexeme};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::PathBuf;
use unlaxer_generator::{modules, packages::Resolver};
use unlaxer_ubnf::*;

#[derive(Clone)]
pub struct Symbol {
    pub name: String,
    pub kind: &'static str,
    pub detail: String,
    pub origin: String,
}
pub struct Scope {
    pub start: usize,
    pub end: usize,
    pub symbols: Vec<(String, Symbol)>,
    pub uses: Vec<(String, usize, usize)>,
}
pub struct Index {
    pub scopes: Vec<Scope>,
    pub diagnostics: Vec<Value>,
    pub valid: bool,
    pub error_offset: usize,
}
impl Scope {
    pub fn unique(&self, name: &str) -> Option<&Symbol> {
        let mut found = self.symbols.iter().filter(|(key, _)| key == name);
        let first = &found.next()?.1;
        if found.next().is_some() {
            None
        } else {
            Some(first)
        }
    }
    fn declare(
        &mut self,
        source: &str,
        lex: &[Lexeme],
        uri: &str,
        name: &str,
        kind: &'static str,
        span: Span,
    ) {
        let candidate = lex.iter().enumerate().find(|(i, t)| {
            t.start >= span.byte_start
                && t.end <= span.byte_end
                && t.text == name
                && (kind != "rule" || lex.get(i + 1).is_some_and(|v| v.text == "::="))
        });
        if let Some((_, t)) = candidate {
            self.symbols.push((
                name.into(),
                Symbol {
                    name: name.into(),
                    kind,
                    detail: source[span.byte_start..span.byte_end].into(),
                    origin: uri.into(),
                },
            ));
            self.uses.push((name.into(), t.start, t.end));
        }
    }
}
impl Index {
    pub fn new(uri: &str, source: &str, sources: BTreeMap<PathBuf, String>) -> Self {
        let lex = text::scan(source);
        let parsed = parse(source);
        let mut result = Self {
            scopes: Vec::new(),
            diagnostics: Vec::new(),
            valid: parsed.is_ok(),
            error_offset: 0,
        };
        let file = match parsed {
            Ok(file) => file,
            Err(error) => {
                result.error_offset = source[..error.span.byte_start].encode_utf16().count();
                result.diagnostics.push(json!({"range":text::range(source,error.span.byte_start,error.span.byte_end),"severity":1,"code":"ULX-PARSE-001","source":"unlaxer","message":error.message}));
                let mut scope = Scope {
                    start: 0,
                    end: source.len(),
                    symbols: Vec::new(),
                    uses: Vec::new(),
                };
                for (i, t) in lex.iter().enumerate() {
                    let kind = if i > 0 && lex[i - 1].text == "grammar" {
                        "grammar"
                    } else if i > 0 && lex[i - 1].text == "token" {
                        "token"
                    } else if lex.get(i + 1).is_some_and(|t| t.text == "::=") {
                        "rule"
                    } else {
                        continue;
                    };
                    scope.symbols.push((
                        t.text.clone(),
                        Symbol {
                            name: t.text.clone(),
                            kind,
                            detail: String::new(),
                            origin: uri.into(),
                        },
                    ));
                }
                result.scopes.push(scope);
                // Recover complete import declarations only; the real frontend validates each.
                let mut imports = Vec::new();
                for pair in lex.windows(2) {
                    if pair[0].text == "@" && pair[1].text == "import" {
                        for end in source[pair[0].start..]
                            .match_indices('\'')
                            .map(|(i, _)| pair[0].start + i + 1)
                        {
                            if let Ok(file) = parse(&format!(
                                "grammar Editor {{ {} }}",
                                &source[pair[0].start..end]
                            )) {
                                imports.extend(file.grammars[0].imports.clone());
                                break;
                            }
                        }
                    }
                }
                let mut resolver = text::file_path(uri)
                    .ok()
                    .map(|p| Resolver::with_sources(&p, sources.clone()));
                for import in imports {
                    result.import(0, uri, source, &import, resolver.as_mut(), true);
                }
                return result;
            }
        };
        for grammar in &file.grammars {
            let mut scope = Scope {
                start: grammar.span.byte_start,
                end: grammar.span.byte_end,
                symbols: Vec::new(),
                uses: Vec::new(),
            };
            for token in &grammar.tokens {
                scope.declare(source, &lex, uri, &token.name, "token", token.span);
                if let TokenKind::Declarative { expression } = &token.kind {
                    let mut refs = Vec::new();
                    lexical_refs(expression, &mut refs);
                    let mut cursor = lex
                        .iter()
                        .position(|t| t.start >= token.span.byte_start && t.text == "::=")
                        .map_or(lex.len(), |i| i + 1);
                    for name in refs {
                        while cursor < lex.len() && lex[cursor].start < token.span.byte_end {
                            let (found, end) = qualified(&lex, cursor);
                            let start = lex[cursor].start;
                            cursor = end;
                            if found == name {
                                scope.uses.push((name, start, lex[end - 1].end));
                                break;
                            }
                        }
                    }
                }
            }
            for rule in &grammar.rules {
                scope.declare(source, &lex, uri, &rule.name, "rule", rule.span);
                body_uses(&rule.body, &mut scope.uses);
                for annotation in &rule.annotations {
                    if let AnnotationKind::Whitespace { style: Some(style) } = &annotation.kind {
                        policy(&lex, &mut scope, annotation.span, style);
                    }
                }
            }
            for setting in &grammar.settings {
                if setting.key == "whitespace" {
                    if let SettingValue::String(style) = &setting.value {
                        policy(&lex, &mut scope, setting.span, style);
                    }
                }
            }
            result.scopes.push(scope);
        }
        let path = text::file_path(uri);
        let mut resolver = path
            .as_ref()
            .ok()
            .map(|p| Resolver::with_sources(p, sources));
        for (i, grammar) in file.grammars.iter().enumerate() {
            for import in &grammar.imports {
                result.import(i, uri, source, import, resolver.as_mut(), false);
            }
        }
        if file.grammars.iter().any(|g| !g.imports.is_empty()) && result.diagnostics.is_empty() {
            if let (Ok(path), Some(resolver)) = (path, resolver.as_mut()) {
                if let Err(error) = modules::resolve(source, &path, resolver) {
                    result.module_error(
                        source,
                        0,
                        source.chars().next().map_or(0, char::len_utf8),
                        error,
                    );
                }
            }
        }
        if result.diagnostics.is_empty() {
            for warning in unlaxer_generator::warnings::collect(source) {
                let (start, end) = warning
                    .span
                    .map_or((0, source.chars().next().map_or(0, char::len_utf8)), |s| {
                        (s.byte_start, s.byte_end)
                    });
                result.diagnostics.push(json!({"range":text::range(source,start,end),"severity":2,"code":warning.code,"source":"ubnf-generator","message":warning.line()}));
            }
        }
        result
    }
    fn module_error(&mut self, source: &str, start: usize, end: usize, message: String) {
        self.diagnostics.push(json!({"range":text::range(source,start,end),"severity":1,"code":"E-MODULE","source":"ubnf-module","message":message}));
    }
    fn import(
        &mut self,
        scope: usize,
        uri: &str,
        source: &str,
        import: &ImportDecl,
        resolver: Option<&mut Resolver>,
        recovered: bool,
    ) {
        let loaded = (|| -> Result<Vec<(String, Symbol)>, String> {
            let path = text::file_path(uri)?;
            let resolver = resolver.ok_or("file URI required")?;
            let directive = if recovered {
                format!(
                    "@import {} from '{}'",
                    import.alias,
                    import.path.replace('\\', "\\\\").replace('\'', "\\'")
                )
            } else {
                source[import.span.byte_start..import.span.byte_end].to_owned()
            };
            modules::resolve(
                &format!("grammar Editor {{ {directive} }}"),
                &path,
                resolver,
            )?;
            let module = resolver.import_path(&path, &import.path)?;
            let text = resolver.read(&module)?;
            let origin = match resolver.identity(&module)? {
                Some(id) => format!(
                    "{}@{} · sha256 {} · {}",
                    id["id"].as_str().unwrap(),
                    id["version"].as_str().unwrap(),
                    id["sha256"].as_str().unwrap(),
                    id["file"].as_str().unwrap()
                ),
                None => text::file_uri(&module),
            };
            let parsed = parse(&text).map_err(|e| e.to_string())?;
            Ok(parsed.grammars[0]
                .tokens
                .iter()
                .map(|t| {
                    (
                        format!("{}.{}", import.alias, t.name),
                        Symbol {
                            name: t.name.clone(),
                            kind: "token",
                            detail: text[t.span.byte_start..t.span.byte_end].into(),
                            origin: origin.clone(),
                        },
                    )
                })
                .collect())
        })();
        match loaded {
            Ok(symbols) => self.scopes[scope].symbols.extend(symbols),
            Err(error) => {
                let (start, end) = if recovered {
                    (0, source.chars().next().map_or(0, char::len_utf8))
                } else {
                    (import.span.byte_start, import.span.byte_end)
                };
                self.module_error(
                    source,
                    start,
                    end,
                    format!("import {}: {error}", import.alias),
                );
            }
        }
    }
    pub fn scope(&self, offset: usize) -> Option<&Scope> {
        self.scopes
            .iter()
            .find(|s| s.start <= offset && offset <= s.end)
    }
}
fn qualified(lex: &[Lexeme], start: usize) -> (String, usize) {
    let mut name = lex[start].text.clone();
    let mut end = start + 1;
    while end + 1 < lex.len() && lex[end].text == "." {
        name.push('.');
        name.push_str(&lex[end + 1].text);
        end += 2;
    }
    (name, end)
}
fn policy(lex: &[Lexeme], scope: &mut Scope, span: Span, name: &str) {
    if name.eq_ignore_ascii_case("none") || name.eq_ignore_ascii_case("javaStyle") {
        return;
    }
    for (i, t) in lex
        .iter()
        .enumerate()
        .filter(|(_, t)| t.start >= span.byte_start && t.end <= span.byte_end)
    {
        let (found, end) = qualified(lex, i);
        if found == name {
            scope.uses.push((name.into(), t.start, lex[end - 1].end));
            break;
        }
    }
}
fn lexical_refs(expression: &unlaxer_ubnf::lexical::LexicalExpression, out: &mut Vec<String>) {
    if expression.op == unlaxer_ubnf::lexical::Op::REF {
        out.push(expression.text.clone());
    }
    for child in &expression.children {
        lexical_refs(child, out);
    }
}
fn body_uses(body: &RuleBody, out: &mut Vec<(String, usize, usize)>) {
    for seq in &body.alternatives {
        for item in &seq.elements {
            atom_uses(&item.element, out);
        }
    }
}
fn atom_uses(atom: &AtomicElement, out: &mut Vec<(String, usize, usize)>) {
    match &atom.kind {
        ElementKind::RuleRef { namespace, name } => out.push((
            namespace
                .as_ref()
                .map_or_else(|| name.clone(), |n| format!("{n}.{name}")),
            atom.span.byte_start,
            atom.span.byte_end,
        )),
        ElementKind::Group(b) | ElementKind::Optional(b) | ElementKind::Repeat(b) => {
            body_uses(b, out)
        }
        ElementKind::OneOrMore(a) | ElementKind::BoundedRepeat { element: a, .. } => {
            atom_uses(a, out)
        }
        ElementKind::Separated { element, separator } => {
            atom_uses(element, out);
            atom_uses(separator, out);
        }
        _ => {}
    }
}
