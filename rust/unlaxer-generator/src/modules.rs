//! File-based lexical modules. Import aliases are namespaces, not host package names.
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Component, Path, PathBuf};
use unlaxer_ubnf::lexical::{LexicalExpression, Op};
use unlaxer_ubnf::*;

pub fn load(path: &Path) -> Result<UbnfFile, String> {
    let path = normalize(path)?;
    let source = std::fs::read_to_string(&path).map_err(|e| e.to_string())?;
    let mut file = parse(&source).map_err(|e| e.to_string())?;
    let mut loader = Loader {
        cache: BTreeMap::new(),
        stack: BTreeSet::from([path.clone()]),
    };
    for grammar in &mut file.grammars {
        loader.link(grammar, &path)?;
    }
    Ok(file)
}

fn normalize(path: &Path) -> Result<PathBuf, String> {
    let absolute = if path.is_absolute() {
        path.to_owned()
    } else {
        std::env::current_dir()
            .map_err(|e| e.to_string())?
            .join(path)
    };
    let mut result = PathBuf::new();
    for component in absolute.components() {
        match component {
            Component::ParentDir => {
                result.pop();
            }
            Component::CurDir => {}
            value => result.push(value),
        }
    }
    Ok(result)
}
fn error(message: impl std::fmt::Display) -> String {
    format!("E-MODULE: {message}")
}
type Exports = BTreeMap<String, LexicalExpression>;
struct Loader {
    cache: BTreeMap<PathBuf, Exports>,
    stack: BTreeSet<PathBuf>,
}
impl Loader {
    fn module(&mut self, path: &Path) -> Result<Exports, String> {
        let path = normalize(path)?;
        if self.stack.contains(&path) {
            return Err(error(format!("cyclic import: {}", path.display())));
        }
        if let Some(exports) = self.cache.get(&path) {
            return Ok(exports.clone());
        }
        if self.stack.len() >= 64 {
            return Err(error("import depth exceeds 64"));
        }
        self.stack.insert(path.clone());
        let result = (|| {
            let source = std::fs::read_to_string(&path).map_err(|e| e.to_string())?;
            let mut file = parse(&source).map_err(|e| e.to_string())?;
            if file.grammars.len() != 1 {
                return Err(error("import requires exactly one grammar"));
            }
            let grammar = &mut file.grammars[0];
            if !grammar.rules.is_empty()
                || grammar
                    .tokens
                    .iter()
                    .any(|t| !matches!(t.kind, TokenKind::Declarative { .. }))
            {
                return Err(error("only declarative token modules can be imported"));
            }
            if !crate::adapters::feature_diagnostics(grammar).is_empty() {
                return Err(error("invalid module features"));
            }
            self.link(grammar, &path)?;
            let exports: Exports = crate::lexical::compile_syntax(grammar)?
                .into_iter()
                .collect();
            self.cache.insert(path.clone(), exports.clone());
            Ok(exports)
        })();
        self.stack.remove(&path);
        result
    }
    fn link(&mut self, grammar: &mut GrammarDecl, path: &Path) -> Result<(), String> {
        if grammar.imports.is_empty() {
            return Ok(());
        }
        let mut aliases = BTreeSet::new();
        let mut imported = Exports::new();
        for declaration in &grammar.imports {
            if !aliases.insert(&declaration.alias) {
                return Err(error(format!(
                    "duplicate import alias: {}",
                    declaration.alias
                )));
            }
            if declaration.path.contains("://") {
                return Err(error("network imports are unsupported"));
            }
            for (name, expression) in
                self.module(&path.parent().unwrap().join(&declaration.path))?
            {
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
        for rule in &mut grammar.rules {
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
        ElementKind::Group(value) | ElementKind::Optional(value) | ElementKind::Repeat(value) => {
            body(value, imported, tokens, used, synthetic)?
        }
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
