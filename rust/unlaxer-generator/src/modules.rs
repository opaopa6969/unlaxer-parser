//! File-based lexical modules. Import aliases are namespaces, not host package names.
use crate::input::FileError;
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use unlaxer_ubnf::lexical::{LexicalExpression, Op};
use unlaxer_ubnf::*;

// Internal marker in the check-only AST: an imported/synthetic declaration has no
// root position. Never infer provenance by comparing offsets from separate files.
pub(crate) const NON_ROOT_SPAN: Span = Span {
    byte_start: usize::MAX,
    byte_end: usize::MAX,
    codepoint_start: usize::MAX,
    codepoint_end: usize::MAX,
};

pub fn load(path: &Path) -> Result<UbnfFile, String> {
    load_with_errors(path).map_err(|error| error.to_string())
}

pub(crate) fn load_with_errors(path: &Path) -> Result<UbnfFile, FileError> {
    let path = normalize(path)?;
    let source = crate::input::read_grammar(&path)?;
    resolve_root(
        &source,
        &path,
        &mut crate::packages::Resolver::new(&path),
        false,
        true,
    )
}

pub(crate) fn load_for_check(path: &Path) -> Result<UbnfFile, String> {
    let path = normalize(path)?;
    let source = std::fs::read_to_string(&path).map_err(|e| e.to_string())?;
    resolve_root(
        &source,
        &path,
        &mut crate::packages::Resolver::new(&path),
        true,
        true,
    )
    .map_err(|error| error.to_string())
}

pub fn resolve(
    source: &str,
    path: &Path,
    packages: &mut crate::packages::Resolver,
) -> Result<UbnfFile, String> {
    resolve_root(source, path, packages, false, true).map_err(|error| error.to_string())
}

/// Java's vocabulary inspection resolves an AST without a source snapshot, so
/// even multi-grammar I/O failures have no grammar-location wrapper.
pub(crate) fn resolve_for_inspect(
    source: &str,
    path: &Path,
    packages: &mut crate::packages::Resolver,
) -> Result<UbnfFile, String> {
    resolve_root(source, path, packages, false, false).map_err(|error| error.to_string())
}

fn resolve_root(
    source: &str,
    path: &Path,
    packages: &mut crate::packages::Resolver,
    root_spans_only: bool,
    include_location: bool,
) -> Result<UbnfFile, FileError> {
    let path = normalize(path)?;
    let mut file = parse(source).map_err(|e| e.to_string())?;
    let mut loader = Loader {
        packages,
        cache: BTreeMap::new(),
        stack: BTreeSet::from([path.clone()]),
    };
    let multiple = file.grammars.len() > 1;
    for grammar in &mut file.grammars {
        let root_tokens = grammar.tokens.len();
        loader.link(grammar, &path).map_err(|error| {
            if multiple && include_location {
                error.context(&format!(
                    "grammar {} [{},{})",
                    grammar.name, grammar.span.codepoint_start, grammar.span.codepoint_end
                ))
            } else {
                error
            }
        })?;
        if root_spans_only {
            // Linking mutates root nodes in place and appends only synthetic token
            // declarations. Imported lexical expressions carry no source spans.
            // Their generated declarations must not borrow the caller reference span.
            for token in &mut grammar.tokens[root_tokens..] {
                token.span = NON_ROOT_SPAN;
            }
        }
    }
    Ok(file)
}

/// Absolute lexical file identity, shared by CLI readers and module/package loading.
/// Does not resolve symlinks or require removed components to exist.
pub fn normalize(path: &Path) -> Result<PathBuf, String> {
    let absolute = if path.is_absolute() {
        path.to_owned()
    } else {
        std::env::current_dir()
            .map_err(|e| e.to_string())?
            .join(path)
    };
    Ok(crate::packages::normalize(&absolute))
}
fn error(message: impl std::fmt::Display) -> String {
    format!("E-MODULE: {message}")
}
type Exports = BTreeMap<String, LexicalExpression>;
struct Loader<'a> {
    packages: &'a mut crate::packages::Resolver,
    cache: BTreeMap<PathBuf, Exports>,
    stack: BTreeSet<PathBuf>,
}
impl Loader<'_> {
    fn module(&mut self, path: &Path) -> Result<Exports, FileError> {
        let path = normalize(path)?;
        if self.stack.contains(&path) {
            return Err(error(format!("cyclic import: {}", path.display())).into());
        }
        if let Some(exports) = self.cache.get(&path) {
            return Ok(exports.clone());
        }
        if self.stack.len() >= 64 {
            return Err(error("import depth exceeds 64").into());
        }
        self.stack.insert(path.clone());
        let result = (|| {
            let source = self.packages.read_with_errors(&path)?;
            let mut file = parse(&source).map_err(|e| e.to_string())?;
            if file.grammars.len() != 1 {
                return Err(error("import requires exactly one grammar").into());
            }
            let grammar = &mut file.grammars[0];
            if !grammar.rules.is_empty()
                || grammar
                    .tokens
                    .iter()
                    .any(|t| !matches!(t.kind, TokenKind::Declarative { .. }))
            {
                return Err(error("only declarative token modules can be imported").into());
            }
            if !crate::adapters::feature_diagnostics(grammar).is_empty() {
                return Err(error("invalid module features").into());
            }
            self.link(grammar, &path)?;
            let exports: Exports = crate::lexical::compile_syntax(grammar)
                .map_err(|message| format!("E-LEXICAL: {message}"))?
                .into_iter()
                .collect();
            self.cache.insert(path.clone(), exports.clone());
            Ok(exports)
        })();
        self.stack.remove(&path);
        result
    }
    fn link(&mut self, grammar: &mut GrammarDecl, path: &Path) -> Result<(), FileError> {
        if grammar.imports.is_empty() {
            return Ok(());
        }
        let mut aliases = BTreeSet::new();
        let mut imported = Exports::new();
        for declaration in &grammar.imports {
            if !aliases.insert(&declaration.alias) {
                return Err(error(format!("duplicate import alias: {}", declaration.alias)).into());
            }
            if declaration.path.contains("://") {
                return Err(error("network imports are unsupported").into());
            }
            let target = self.packages.import_path(path, &declaration.path)?;
            for (name, expression) in self.module(&target)? {
                imported.insert(format!("{}.{name}", declaration.alias), expression);
            }
        }
        for token in &mut grammar.tokens {
            if let TokenKind::Declarative { expression } = &mut token.kind {
                external_refs(expression, &imported)?;
            }
        }
        let mut used: BTreeSet<String> = grammar
            .tokens
            .iter()
            .map(|t| t.name.clone())
            .chain(grammar.rules.iter().map(|r| r.name.clone()))
            .collect();
        let mut synthetic = BTreeMap::new();
        for setting in &mut grammar.settings {
            if setting.key == "whitespace" {
                if let SettingValue::String(style) = &mut setting.value {
                    policy(
                        style,
                        setting.span,
                        &imported,
                        &mut grammar.tokens,
                        &mut used,
                        &mut synthetic,
                    )?;
                }
            }
        }
        for rule in &mut grammar.rules {
            for annotation in &mut rule.annotations {
                if let AnnotationKind::LexicalContext { tokens, .. } = &mut annotation.kind {
                    for name in tokens {
                        policy(
                            name,
                            annotation.span,
                            &imported,
                            &mut grammar.tokens,
                            &mut used,
                            &mut synthetic,
                        )?;
                    }
                }
                if let AnnotationKind::Whitespace { style: Some(style) } = &mut annotation.kind {
                    policy(
                        style,
                        annotation.span,
                        &imported,
                        &mut grammar.tokens,
                        &mut used,
                        &mut synthetic,
                    )?;
                }
            }
            body(
                &mut rule.body,
                &imported,
                &mut grammar.tokens,
                &mut used,
                &mut synthetic,
            )?;
        }
        grammar.imports.clear();
        Ok(())
    }
}
fn policy(
    style: &mut String,
    span: Span,
    imported: &Exports,
    tokens: &mut Vec<TokenDecl>,
    used: &mut BTreeSet<String>,
    synthetic: &mut BTreeMap<String, String>,
) -> Result<(), String> {
    if let Some((namespace, name)) = style.split_once('.') {
        let mut value = AtomicElement {
            span,
            kind: ElementKind::RuleRef {
                namespace: Some(namespace.into()),
                name: name.into(),
            },
        };
        atom(&mut value, imported, tokens, used, synthetic)?;
        if let ElementKind::RuleRef { name, .. } = value.kind {
            *style = name;
        }
    }
    Ok(())
}
fn external_refs(expression: &mut LexicalExpression, imported: &Exports) -> Result<(), String> {
    if expression.op == Op::REF && expression.text.contains('.') {
        *expression = imported
            .get(&expression.text)
            .ok_or_else(|| error(format!("undefined imported token: {}", expression.text)))?
            .clone();
    } else {
        for child in &mut expression.children {
            external_refs(child, imported)?;
        }
    }
    Ok(())
}
fn body(
    value: &mut RuleBody,
    imported: &Exports,
    tokens: &mut Vec<TokenDecl>,
    used: &mut BTreeSet<String>,
    synthetic: &mut BTreeMap<String, String>,
) -> Result<(), String> {
    for sequence in &mut value.alternatives {
        for item in &mut sequence.elements {
            atom(&mut item.element, imported, tokens, used, synthetic)?;
        }
    }
    Ok(())
}
fn atom(
    value: &mut AtomicElement,
    imported: &Exports,
    tokens: &mut Vec<TokenDecl>,
    used: &mut BTreeSet<String>,
    synthetic: &mut BTreeMap<String, String>,
) -> Result<(), String> {
    match &mut value.kind {
        ElementKind::RuleRef {
            namespace: Some(namespace),
            name,
        } => {
            let qualified = format!("{namespace}.{name}");
            let expression = imported
                .get(&qualified)
                .ok_or_else(|| error(format!("undefined imported token: {qualified}")))?;
            let name = if let Some(name) = synthetic.get(&qualified) {
                name.clone()
            } else {
                let mut index = synthetic.len();
                let name = loop {
                    let candidate = format!("ImportedLexical{index}");
                    if used.insert(candidate.clone()) {
                        break candidate;
                    }
                    index += 1;
                };
                synthetic.insert(qualified, name.clone());
                tokens.push(TokenDecl {
                    name: name.clone(),
                    span: value.span,
                    kind: TokenKind::Declarative {
                        expression: expression.clone(),
                    },
                });
                name
            };
            value.kind = ElementKind::RuleRef {
                namespace: None,
                name,
            };
        }
        ElementKind::OwnedCapture { body: value, .. }
        | ElementKind::CaptureScope(value)
        | ElementKind::Commit { body: value, .. }
        | ElementKind::Label { body: value, .. }
        | ElementKind::Catch { choice: value, .. }
        | ElementKind::Group(value)
        | ElementKind::Optional(value)
        | ElementKind::Repeat(value) => body(value, imported, tokens, used, synthetic)?,
        ElementKind::OneOrMore(child) | ElementKind::BoundedRepeat { element: child, .. } => {
            atom(child, imported, tokens, used, synthetic)?
        }
        ElementKind::Separated { element, separator } => {
            atom(element, imported, tokens, used, synthetic)?;
            atom(separator, imported, tokens, used, synthetic)?;
        }
        _ => {}
    }
    Ok(())
}
