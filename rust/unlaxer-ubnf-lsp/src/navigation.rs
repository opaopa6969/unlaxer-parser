//! Source bindings and wire results follow Java UbnfEditorIndex/UBNFLanguageServerExt.
use crate::{
    features,
    index::{Index, Scope, Symbol},
    text, Error,
};
use serde_json::{json, Value};

type Use = (String, usize, usize);
fn at(index: &Index, offset: usize) -> Option<(&Scope, &Use)> {
    index.scopes.iter().find_map(|scope| {
        scope
            .uses
            .iter()
            .find(|(_, start, end)| *start <= offset && offset < *end)
            .map(|u| (scope, u))
    })
}
fn target(index: &Index, offset: usize) -> Option<&Symbol> {
    let (scope, u) = at(index, offset)?;
    scope.unique(&u.0)
}
fn references<'a>(index: &'a Index, target: &'a Symbol) -> impl Iterator<Item = &'a Use> {
    // Java compares complete Symbol values, so two aliases to the same external
    // declaration share references, while same-named local scopes remain distinct.
    index.scopes.iter().flat_map(move |scope| {
        scope
            .uses
            .iter()
            .filter(move |u| scope.unique(&u.0) == Some(target))
    })
}
fn editable(index: &Index, uri: &str, symbol: &Symbol) -> bool {
    index.safe
        && !index.diagnostics.iter().any(|d| d["code"] == "E-MODULE")
        && symbol.uri == uri
        && !symbol.exported
}
fn location(symbol: &Symbol) -> Value {
    json!({"uri":symbol.uri,"range":text::range(&symbol.source,symbol.start,symbol.end)})
}
pub fn definition(index: &Index, offset: usize) -> Value {
    target(index, offset).map_or_else(|| json!([]), |s| json!([location(s)]))
}
pub fn reference_locations(
    source: &str,
    uri: &str,
    index: &Index,
    offset: usize,
    include: bool,
) -> Value {
    let Some(symbol) = target(index, offset) else {
        return json!([]);
    };
    let mut locations: Vec<Value> = references(index, symbol)
        .filter(|u| include || !(symbol.uri == uri && u.1 == symbol.start && u.2 == symbol.end))
        .map(|u| json!({"uri":uri,"range":text::range(source,u.1,u.2)}))
        .collect();
    if include && symbol.uri != uri {
        locations.push(location(symbol));
    }
    json!(locations)
}
pub fn rename(
    source: &str,
    uri: &str,
    index: &Index,
    offset: usize,
    name: Option<&str>,
) -> Result<Value, Error> {
    let Some((scope, u)) = at(index, offset) else {
        return Err(rename_error());
    };
    let Some(symbol) = scope.unique(&u.0) else {
        return Err(rename_error());
    };
    if !editable(index, uri, symbol) {
        return Err(rename_error());
    }
    // Java checks editability before dereferencing a null newName.
    let Some(name) = name else {
        return Err(crate::rpc::internal());
    };
    if !text::identifier(name)
        || features::keywords().iter().any(|k| k == name)
        || scope.symbols.iter().any(|(key, _)| key == name)
    {
        return Err(rename_error());
    }
    let edits: Vec<Value> = references(index, symbol)
        .map(|u| json!({"range":text::range(source,u.1,u.2),"newText":name}))
        .collect();
    Ok(json!({"changes":{uri:edits}}))
}
fn rename_error() -> Error {
    (-32602, "安全に名前変更できません。完成した文法のローカル宣言を選び、重複・予約語を避けてください。外部 module の token / import alias / capture は一括 rename の対象外です。".into())
}
pub fn linked(source: &str, uri: &str, index: &Index, offset: usize) -> Value {
    let Some(symbol) = target(index, offset).filter(|s| editable(index, uri, s)) else {
        return Value::Null;
    };
    let ranges: Vec<Value> = references(index, symbol)
        .map(|u| text::range(source, u.1, u.2))
        .collect();
    json!({"ranges":ranges})
}
pub fn symbols(source: &str, index: &Index) -> Value {
    let mut out: Vec<Value> = Vec::new();
    for scope in &index.scopes {
        let mut parent = None;
        for decl in &index.declarations {
            if decl.start < scope.start || decl.end > scope.end {
                continue;
            }
            let selection = text::range(source, decl.start, decl.end);
            if decl.kind == "grammar" {
                out.push(json!({"name":decl.name,"kind":3,"range":text::range(source,scope.start,scope.end),"selectionRange":selection,"children":[]}));
                parent = Some(out.len() - 1);
            } else {
                let symbol = json!({"name":decl.name,"kind":if decl.kind == "token" {14} else {5},"range":selection,"selectionRange":selection});
                if let Some(i) = parent {
                    out[i]["children"].as_array_mut().unwrap().push(symbol);
                } else {
                    out.push(symbol);
                }
            }
        }
    }
    json!(out)
}
pub fn folding(source: &str) -> Value {
    let mut out = Vec::new();
    let (mut brace, mut rule) = (None, None);
    for (i, line) in source.split('\n').enumerate() {
        let prefix = line.trim_start_matches(text::regex_space);
        let grammar = prefix.strip_prefix("grammar").is_some_and(|s| {
            s.chars().next().is_none_or(|c| !text::word(c))
                && !s.contains(['\r', '\u{85}', '\u{2028}', '\u{2029}'])
        });
        if brace.is_none() && line.contains('{') && grammar {
            brace = Some(i);
        }
        if brace.is_some() && line.trim_matches(text::java_whitespace) == "}" {
            let start = brace.take().unwrap();
            if i > start {
                out.push(json!({"startLine":start,"endLine":i}));
            }
        }
        if rule.is_none() && line.contains("::=") && !line.contains(';') {
            rule = Some(i);
        } else if rule.is_some() && line.contains(';') {
            let start = rule.take().unwrap();
            if i > start {
                out.push(json!({"startLine":start,"endLine":i,"kind":"region"}));
            }
        }
    }
    json!(out)
}
