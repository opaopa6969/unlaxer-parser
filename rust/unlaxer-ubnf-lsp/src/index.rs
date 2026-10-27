use crate::text::{self, Lexeme};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Arc;
use unlaxer_generator::{modules, packages::Resolver};
use unlaxer_ubnf::*;

#[derive(Clone, PartialEq, Eq)]
pub struct Symbol {
    pub name: String,
    pub kind: &'static str,
    pub detail: String,
    pub origin: String,
    pub uri: String,
    pub source: Arc<str>,
    pub start: usize,
    pub end: usize,
    pub exported: bool,
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
    pub declarations: Vec<Symbol>,
    pub safe: bool,
    pub virtual_sources: BTreeMap<String, String>,
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
}
impl Index {
    pub fn new(uri: &str, source: &str, sources: BTreeMap<PathBuf, String>) -> Self {
        let lex = text::scan(source);
        // Every binding owns its source lifetime, but shares the immutable text.
        let shared_source: Arc<str> = source.into();
        let parsed = authoring_parse(source);
        let mut result = Self {
            scopes: Vec::new(),
            diagnostics: Vec::new(),
            valid: parsed.is_ok(),
            error_offset: 0,
            declarations: Vec::new(),
            safe: parsed.is_ok(),
            virtual_sources: BTreeMap::new(),
        };
        let mut file = match parsed {
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
                // Java's recovery regex consumes a declaration before searching
                // again; keywords used as names cannot start overlapping matches.
                let code = text::mask(source);
                let mut cursor = 0;
                while cursor + 1 < lex.len() {
                    let first = &lex[cursor];
                    let next = &lex[cursor + 1];
                    let gap = &code[first.end..next.start];
                    let (t, kind) = if !gap.chars().all(text::regex_space) {
                        cursor += 1;
                        continue;
                    } else if matches!(first.text.as_str(), "grammar" | "token")
                        && text::identifier(&next.text)
                        && !gap.is_empty()
                    {
                        (
                            next,
                            if first.text == "grammar" {
                                "grammar"
                            } else {
                                "token"
                            },
                        )
                    } else if text::identifier(&first.text) && next.text == "::=" {
                        (first, "rule")
                    } else {
                        cursor += 1;
                        continue;
                    };
                    cursor += 2;
                    let symbol = Symbol {
                        name: t.text.clone(),
                        kind,
                        detail: String::new(),
                        origin: uri.into(),
                        uri: uri.into(),
                        source: shared_source.clone(),
                        start: t.start,
                        end: t.end,
                        exported: false,
                    };
                    scope.symbols.push((t.text.clone(), symbol.clone()));
                    result.declarations.push(symbol);
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
        // Before linking replaces the file: Java ranges use this document's declarations.
        let declarations = declarations(&file, source, &lex);
        for grammar in &file.grammars {
            let mut scope = Scope {
                start: grammar.span.byte_start,
                end: grammar.span.byte_end,
                symbols: Vec::new(),
                uses: Vec::new(),
            };
            for (name, kind, span, exported) in
                std::iter::once((&grammar.name, "grammar", grammar.span, false))
                    .chain(
                        grammar
                            .tokens
                            .iter()
                            .map(|t| (&t.name, "token", t.span, grammar.rules.is_empty())),
                    )
                    .chain(
                        grammar
                            .rules
                            .iter()
                            .map(|r| (&r.name, "rule", r.span, false)),
                    )
            {
                if let Some(symbol) = symbol(&shared_source, &lex, uri, name, kind, span, exported)
                {
                    result.declarations.push(symbol.clone());
                    if kind != "grammar" {
                        scope.uses.push((name.clone(), symbol.start, symbol.end));
                        scope.symbols.push((name.clone(), symbol));
                    }
                } else {
                    result.safe = false;
                }
            }
            for token in &grammar.tokens {
                if let TokenKind::Declarative { expression } = &token.kind {
                    let mut expected = Vec::new();
                    lexical_refs(expression, &mut expected);
                    let actual = lexical_uses(source, &lex, token.span);
                    if expected.iter().eq(actual.iter().map(|u| &u.0)) {
                        scope.uses.extend(actual);
                    } else {
                        result.safe = false;
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
            for rule in &grammar.rules {
                let mut uses = Vec::new();
                body_uses(&rule.body, &mut uses);
                for (name, start, end) in uses {
                    // Expansion/rewrite may retain a span that does not spell a reference.
                    // Like Java, only the AST name plus its real source origin authorizes edits.
                    let word = word_in(&lex, start, end);
                    if let Some((found, from, to)) = word.filter(|w| w.0 == name) {
                        scope.uses.push((found, from, to));
                    } else {
                        result.safe = false;
                    }
                }
                for annotation in &rule.annotations {
                    if let AnnotationKind::Whitespace { style: Some(style) } = &annotation.kind {
                        policy(&lex, &mut scope, annotation.span, style);
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
                match modules::resolve(source, &path, resolver) {
                    Ok(linked) => file = linked,
                    Err(error) => result.module_error(
                        source,
                        0,
                        source.chars().next().map_or(0, char::len_utf8),
                        error,
                    ),
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
        // Java suppresses validation after a module failure.
        if !result.diagnostics.iter().any(|d| d["code"] == "E-MODULE") {
            for grammar in &file.grammars {
                for issue in unlaxer_generator::validation::collect(grammar) {
                    result
                        .diagnostics
                        .push(validation_diagnostic(source, &declarations, &issue));
                }
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
            let identity = resolver.identity(&module)?;
            let module_uri = identity.as_ref().map_or_else(
                || text::file_uri(&module),
                |id| {
                    text::package_uri(id["sha256"].as_str().unwrap(), id["file"].as_str().unwrap())
                },
            );
            let origin = match identity {
                Some(id) => {
                    self.virtual_sources
                        .insert(module_uri.clone(), text.clone());
                    format!(
                        "{}@{} · sha256 {} · {}",
                        id["id"].as_str().unwrap(),
                        id["version"].as_str().unwrap(),
                        id["sha256"].as_str().unwrap(),
                        id["file"].as_str().unwrap()
                    )
                }
                None => module_uri.clone(),
            };
            let parsed = parse(&text).map_err(|e| e.to_string())?;
            let module_lex = text::scan(&text);
            let text: Arc<str> = text.into();
            Ok(parsed.grammars[0]
                .tokens
                .iter()
                .filter_map(|t| {
                    let mut s = symbol(
                        &text,
                        &module_lex,
                        &module_uri,
                        &t.name,
                        "token",
                        t.span,
                        true,
                    )?;
                    s.origin = origin.clone();
                    Some((format!("{}.{}", import.alias, t.name), s))
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
/// Java's namespace prefix is a plain LazyChain (no inter-token trivia); only
/// the final identifier may have leading whitespace. The native frontend accepts
/// more spacing. Recover those documents instead of granting AST edit authority.
fn authoring_parse(source: &str) -> Result<UbnfFile, Diagnostic> {
    fn body(source: &str, value: &RuleBody) -> Option<Span> {
        value
            .alternatives
            .iter()
            .flat_map(|s| &s.elements)
            .find_map(|e| atom(source, &e.element))
    }
    fn atom(source: &str, value: &AtomicElement) -> Option<Span> {
        match &value.kind {
            ElementKind::RuleRef {
                namespace: Some(namespace),
                ..
            } => {
                let raw = &source[value.span.byte_start..value.span.byte_end];
                let prefix = format!("{namespace}.");
                // A synthetic reference with an unrelated source span is handled
                // by the binding guard, not reclassified as a syntax error here.
                let normalized: String = raw.chars().filter(|c| !text::regex_space(*c)).collect();
                (normalized.starts_with(&prefix) && !raw.starts_with(&prefix)).then_some(value.span)
            }
            ElementKind::Group(b)
            | ElementKind::Optional(b)
            | ElementKind::Repeat(b)
            | ElementKind::OwnedCapture { body: b, .. }
            | ElementKind::CaptureScope(b)
            | ElementKind::Commit { body: b, .. }
            | ElementKind::Label { body: b, .. }
            | ElementKind::Catch { choice: b, .. } => body(source, b),
            ElementKind::OneOrMore(a) | ElementKind::BoundedRepeat { element: a, .. } => {
                atom(source, a)
            }
            ElementKind::Separated { element, separator } => {
                atom(source, element).or_else(|| atom(source, separator))
            }
            _ => None,
        }
    }
    let file = parse(source)?;
    if let Some(span) = file
        .grammars
        .iter()
        .flat_map(|g| &g.rules)
        .find_map(|r| body(source, &r.body))
    {
        let position = text::position(source, span.byte_start);
        return Err(Diagnostic {
            kind: DiagnosticKind::Syntax,
            message: "namespace prefix must be contiguous".into(),
            span,
            line: position["line"].as_u64().unwrap() as usize + 1,
            column: source[..span.byte_start]
                .rsplit('\n')
                .next()
                .unwrap()
                .chars()
                .count()
                + 1,
        });
    }
    Ok(file)
}

fn symbol(
    source: &Arc<str>,
    lex: &[Lexeme],
    uri: &str,
    name: &str,
    kind: &'static str,
    span: Span,
    exported: bool,
) -> Option<Symbol> {
    let (_, t) = lex.iter().enumerate().find(|(i, t)| {
        t.start >= span.byte_start
            && t.end <= span.byte_end
            && t.text == name
            && if kind == "rule" {
                lex.get(i + 1).is_some_and(|v| v.text == "::=")
            } else {
                *i > 0 && lex[i - 1].start >= span.byte_start && lex[i - 1].text == kind
            }
    })?;
    Some(Symbol {
        name: name.into(),
        kind,
        detail: source[span.byte_start..span.byte_end].into(),
        origin: uri.into(),
        uri: uri.into(),
        source: source.clone(),
        start: t.start,
        end: t.end,
        exported,
    })
}
fn word_in(lex: &[Lexeme], start: usize, end: usize) -> Option<(String, usize, usize)> {
    let (i, t) = lex
        .iter()
        .enumerate()
        .find(|(_, t)| t.start >= start && t.end <= end && text::identifier(&t.text))?;
    let (name, after) = qualified(lex, i);
    (lex[after - 1].end <= end).then_some((name, t.start, lex[after - 1].end))
}
fn lexical_uses(source: &str, lex: &[Lexeme], span: Span) -> Vec<(String, usize, usize)> {
    let Some(mut i) = lex
        .iter()
        .position(|t| t.start >= span.byte_start && t.text == "::=")
        .map(|i| i + 1)
    else {
        return vec![];
    };
    let begin = lex[i - 1].end;
    let code = text::mask(source);
    let mut out = Vec::new();
    while i < lex.len() && lex[i].start < span.byte_end {
        let t = &lex[i];
        if !text::identifier(&t.text) {
            i += 1;
            continue;
        }
        let (name, end) = qualified(lex, i);
        i = end;
        if ["ANY", "EOF", "BOF", "BOL", "EOL"].contains(&name.as_str()) {
            continue;
        }
        let before = code[begin..t.start].trim_end_matches(text::regex_space);
        if before.strip_suffix('(').is_some_and(|s| {
            let s = s.trim_end_matches(text::regex_space);
            s.ends_with("CAPTURE") || s.ends_with("SAME_AS")
        }) {
            continue;
        }
        if lex.get(end).is_some_and(|v| v.text == "(")
            && [
                "CAPTURE",
                "SAME_AS",
                "LOOKAHEAD",
                "NEGATIVE_LOOKAHEAD",
                "CHAR_RANGE",
                "NEGATION",
            ]
            .contains(&name.as_str())
        {
            continue;
        }
        out.push((name, t.start, lex[end - 1].end));
    }
    out
}
fn qualified(lex: &[Lexeme], start: usize) -> (String, usize) {
    let mut name = lex[start].text.clone();
    let mut end = start + 1;
    while end + 1 < lex.len() && lex[end].text == "." && text::identifier(&lex[end + 1].text) {
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

/// A Java `UbnfEditorIndex` declaration: grammar, then tokens, then rules per grammar,
/// each the first lexeme its node-bounded pattern matches (masked comments/strings).
struct Declaration {
    name: String,
    start: usize,
    end: usize,
    /// `tokenDetail` of the token node text; `None` for grammars and rules.
    detail: Option<String>,
}

fn declarations(file: &UbnfFile, source: &str, lex: &[Lexeme]) -> Vec<Declaration> {
    let mut out = vec![];
    let mut find = |name: &str, span: Span, kind: &str| {
        let site = lex.iter().enumerate().find(|(i, t)| {
            t.start >= span.byte_start
                && t.end <= span.byte_end
                && t.text == name
                && match kind {
                    "rule" => lex.get(i + 1).is_some_and(|n| n.text == "::="),
                    keyword => *i > 0 && lex[i - 1].text == keyword,
                }
        });
        if let Some((_, t)) = site {
            out.push(Declaration {
                name: name.into(),
                start: t.start,
                end: t.end,
                detail: (kind == "token")
                    .then(|| token_detail(&source[span.byte_start..span.byte_end])),
            });
        }
    };
    for grammar in &file.grammars {
        find(&grammar.name, grammar.span, "grammar");
        for token in &grammar.tokens {
            find(&token.name, token.span, "token");
        }
        for rule in &grammar.rules {
            find(&rule.name, rule.span, "rule");
        }
    }
    out
}

/// Java `(?s)\btoken\s+\w+\s*(?:::=|=)\s*(.*)` (ASCII classes), first match, then `strip()`.
fn token_detail(declaration: &str) -> String {
    let bytes = declaration.as_bytes();
    let word = |b: u8| b.is_ascii_alphanumeric() || b == b'_';
    let space = |b: u8| matches!(b, b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r');
    let skip = |mut i: usize, test: &dyn Fn(u8) -> bool| {
        while i < bytes.len() && test(bytes[i]) {
            i += 1;
        }
        i
    };
    for (at, _) in declaration.match_indices("token") {
        if at > 0 && word(bytes[at - 1]) {
            continue;
        }
        let name = skip(at + 5, &space);
        if name == at + 5 {
            continue;
        }
        let after = skip(name, &word);
        if after == name {
            continue;
        }
        let op = skip(after, &space);
        let rest = if declaration[op..].starts_with("::=") {
            op + 3
        } else if declaration[op..].starts_with('=') {
            op + 1
        } else {
            continue;
        };
        return declaration[skip(rest, &space)..]
            .trim_matches(java_whitespace)
            .to_owned();
    }
    String::new()
}

/// `Character.isWhitespace`, which `String.strip()` uses.
fn java_whitespace(c: char) -> bool {
    matches!(c, '\t'..='\r' | '\u{1c}'..='\u{1f}')
        || c.is_whitespace() && !matches!(c, '\u{85}' | '\u{a0}' | '\u{2007}' | '\u{202f}')
}

fn validation_diagnostic(
    source: &str,
    declarations: &[Declaration],
    issue: &unlaxer_generator::validation::Issue,
) -> Value {
    // Java's `decls` keeps the document-wide first declaration of each name (putIfAbsent).
    let declaration = |name: &str| declarations.iter().find(|d| d.name == name);
    let mut range = json!({"start":{"line":0,"character":0},"end":{"line":0,"character":1}});
    let unresolved = (issue.code == "W-TOKEN-UNRESOLVED")
        .then(|| {
            issue
                .message
                .strip_prefix("token ")?
                .split_once(" references unresolved parser class: ")
        })
        .flatten();
    let detail_range = unresolved.and_then(|(name, _)| {
        let site = declaration(name)?;
        let detail = site.detail.as_deref()?;
        let line_start = source[..site.start].rfind('\n').map_or(0, |i| i + 1);
        let line_end = source[site.start..]
            .find('\n')
            .map_or(source.len(), |i| site.start + i);
        let col = source[line_start..line_end].find(detail)?;
        Some(text::range(
            source,
            line_start + col,
            line_start + col + detail.len(),
        ))
    });
    if let Some(found) = detail_range {
        range = found;
    } else if let Some(site) = issue.rule.as_deref().and_then(declaration) {
        range = text::range(source, site.start, site.end);
    }
    json!({"range":range,"severity":if issue.code.starts_with("W-"){2}else{1},"code":issue.code,"source":"ubnf-validator","message":format!("{} — {}",issue.message,issue.hint)})
}
