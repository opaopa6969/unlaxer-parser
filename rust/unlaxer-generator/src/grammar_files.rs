//! Independent grammars share a directory only through an explicit module index.
use std::collections::BTreeSet;
use unlaxer_ubnf::{GrammarDecl, UbnfFile};

pub(crate) fn valid_name(grammar: &GrammarDecl) -> bool {
    let mut bytes = grammar.name.bytes();
    bytes
        .next()
        .is_some_and(|c| c.is_ascii_alphabetic() || c == b'_')
        && bytes.all(|c| c.is_ascii_alphanumeric() || c == b'_')
}

pub(crate) fn module_name(grammar: &GrammarDecl) -> String {
    format!("grammar_{}", grammar.name.to_ascii_lowercase())
}

pub(crate) fn collisions(file: &UbnfFile) -> Vec<(&GrammarDecl, String)> {
    let mut seen = BTreeSet::new();
    file.grammars
        .iter()
        .filter(|grammar| valid_name(grammar))
        .filter_map(|grammar| {
            let name = module_name(grammar);
            (!seen.insert(name.clone())).then_some((grammar, name))
        })
        .collect()
}

pub(crate) fn validate(file: &UbnfFile) -> Result<(), String> {
    if file.grammars.is_empty() {
        return Err("expected one or more grammars".into());
    }
    if let Some(grammar) = file.grammars.iter().find(|g| !valid_name(g)) {
        return Err(format!(
            "P-GRAMMAR-NAME [{},{}): {}",
            grammar.span.codepoint_start, grammar.span.codepoint_end, grammar.name
        ));
    }
    if let Some((grammar, module)) = collisions(file).first() {
        return Err(format!(
            "P-OUTPUT-COLLISION [{},{}): {module}",
            grammar.span.codepoint_start, grammar.span.codepoint_end
        ));
    }
    Ok(())
}
