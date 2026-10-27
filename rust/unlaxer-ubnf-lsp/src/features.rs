use crate::{index::Index, text};
use serde_json::{json, Value};
use std::sync::OnceLock;
use unlaxer_ubnf::*;

fn data() -> &'static Value {
    static DATA: OnceLock<Value> = OnceLock::new();
    DATA.get_or_init(|| serde_json::from_str(include_str!("authoring-data.json")).unwrap())
}
fn string<'a>(value: &'a Value, key: &str) -> &'a str {
    value[key].as_str().unwrap()
}
pub fn markdown(value: impl Into<String>) -> Value {
    json!({"kind":"markdown","value":value.into()})
}
fn help(id: &str) -> Option<String> {
    static CATALOG: OnceLock<Value> = OnceLock::new();
    let catalog = CATALOG.get_or_init(|| {
        serde_json::from_str(include_str!(
            "../../../unlaxer-dsl/src/main/resources/ubnf-help/catalog.json"
        ))
        .unwrap()
    });
    let entry = catalog["entries"]
        .as_array()?
        .iter()
        .find(|e| e["id"] == id)?;
    let mut text = format!(
        "{}\n\n{}\n\n```ubnf\n{}\n```",
        string(entry, "title"),
        string(entry, "summary"),
        string(entry, "syntax")
    );
    for detail in entry["details"].as_array()? {
        text.push_str("\n\n");
        text.push_str(detail.as_str()?);
    }
    Some(text)
}
pub(crate) fn keywords() -> &'static Vec<String> {
    static WORDS: OnceLock<Vec<String>> = OnceLock::new();
    WORDS.get_or_init(|| {
        let file = parse(include_str!("../../../unlaxer-dsl/grammar/ubnf.ubnf"))
            .expect("bundled meta grammar");
        let mut words = Vec::new();
        for grammar in file.grammars {
            for rule in grammar.rules {
                body_words(&rule.body, &mut words);
            }
        }
        for word in ["BOF", "BOL", "EOL"] {
            if !words.iter().any(|v| v == word) {
                words.push(word.into());
            }
        }
        words
    })
}
fn body_words(body: &RuleBody, out: &mut Vec<String>) {
    for seq in &body.alternatives {
        for item in &seq.elements {
            atom_words(&item.element, out);
        }
    }
}
fn atom_words(atom: &AtomicElement, out: &mut Vec<String>) {
    match &atom.kind {
        ElementKind::Terminal(word)
            if !word.is_empty()
                && (word.as_bytes()[0].is_ascii_alphabetic() || word.starts_with('_'))
                && word.chars().all(text::word) =>
        {
            if !out.contains(word) {
                out.push(word.clone());
            }
        }
        ElementKind::Group(b) | ElementKind::Optional(b) | ElementKind::Repeat(b) => {
            body_words(b, out)
        }
        ElementKind::OneOrMore(a) | ElementKind::BoundedRepeat { element: a, .. } => {
            atom_words(a, out)
        }
        ElementKind::Separated { element, separator } => {
            atom_words(element, out);
            atom_words(separator, out);
        }
        _ => {}
    }
}
pub fn completion(source: &str, index: &Index, offset: usize) -> Value {
    let line_start = source[..offset].rfind('\n').map_or(0, |i| i + 1);
    let prefix = &source[line_start..offset];
    let suffix = prefix.trim_end_matches(text::word);
    if suffix.ends_with('@') {
        return Value::Array(data()["annotations"].as_array().unwrap().iter().map(|a|{
            let label=string(a,"label");
            json!({"label":label,"kind":23,"detail":a["signature"],"documentation":markdown(help(&label[1..]).unwrap_or_else(||string(a,"doc").into())),"insertTextFormat":2,"insertText":&string(a,"snippet")[1..],"filterText":&label[1..]})
        }).collect());
    }
    if prefix.split_once('=').is_some_and(|(before, after)| {
        let parts: Vec<_> = before.split_whitespace().collect();
        parts.len() == 2
            && parts[0] == "token"
            && parts[1].chars().all(text::word)
            && !after.trim_start().contains(char::is_whitespace)
    }) {
        return Value::Array(data()["parsers"].as_array().unwrap().iter().map(|p|json!({"label":p,"kind":7,"detail":"bundled unlaxer parser","sortText":format!("0{}",p.as_str().unwrap())})).collect());
    }
    let whitespace = prefix.rsplit_once("@whitespace").is_some_and(|(_, tail)| {
        let tail = tail.trim_start();
        (tail.starts_with(':') || tail.starts_with('('))
            && tail[1..]
                .trim_start()
                .chars()
                .all(|c| text::word(c) || c == '.')
    });
    let mut start = offset;
    while let Some((i, c)) = source[..start].char_indices().next_back() {
        if text::java_letter_or_digit(c) || c == '_' || c == '.' {
            start = i;
        } else {
            break;
        }
    }
    // Java: `([A-Za-z_]\w*\.)\w*$` on the line prefix (ASCII \w, last qualifier only).
    let qualifier = prefix
        .trim_end_matches(text::word)
        .strip_suffix('.')
        .and_then(|before| {
            let run = &before[before.trim_end_matches(text::word).len()..];
            let first = run.find(|c: char| c.is_ascii_alphabetic() || c == '_')?;
            Some(format!("{}.", &run[first..]))
        });
    let lex = text::scan(&source[..offset]);
    let lexical = lex.iter().rposition(|t| t.text == "::=").is_some_and(|i| {
        i >= 2 && lex[i - 2].text == "token" && !lex[i + 1..].iter().any(|t| t.text == ";")
    });
    let mut items = Vec::new();
    if whitespace {
        items.push(
            json!({"label":"javaStyle","kind":21,"detail":"組込みの空白・行/block コメント"}),
        );
        items.push(json!({"label":"none","kind":21,"detail":"自動読み飛ばしなし"}));
    }
    if qualifier.is_none() && !whitespace {
        let words = if lexical {
            vec![
                "CHAR_RANGE",
                "NEGATION",
                "LOOKAHEAD",
                "NEGATIVE_LOOKAHEAD",
                "ANY",
                "EOF",
                "BOF",
                "BOL",
                "EOL",
                "CAPTURE",
                "SAME_AS",
            ]
        } else {
            keywords().iter().map(String::as_str).collect()
        };
        for word in words {
            let mut item = json!({"label":word,"kind":14});
            if let Some(help) = help(word) {
                item["documentation"] = markdown(help);
            }
            items.push(item);
        }
        if !lexical {
            for snippet in data()["snippets"].as_array().unwrap() {
                items.push(json!({"label":snippet["label"],"kind":15,"detail":snippet["detail"],"insertTextFormat":2,"insertText":snippet["body"]}));
            }
        }
    }
    if let Some(scope) = index.scope(offset) {
        for (name, symbol) in &scope.symbols {
            if scope.unique(name).is_none()
                || ((lexical || whitespace) && symbol.kind != "token")
                || qualifier.as_ref().is_some_and(|q| !name.starts_with(q))
            {
                continue;
            }
            if whitespace
                && (!symbol.detail.contains("::=")
                    || name.eq_ignore_ascii_case("none")
                    || name.eq_ignore_ascii_case("javaStyle"))
            {
                continue;
            }
            items.push(json!({"label":name,"kind":if symbol.kind=="token" {21}else{7},"detail":format!("{} · {}",symbol.kind,symbol.origin),"documentation":markdown(format!("```ubnf\n{}\n```",symbol.detail)),"textEdit":{"range":text::range(source,start,offset),"newText":name}}));
        }
    }
    Value::Array(items)
}
pub fn hover(source: &str, index: &Index, offset: usize) -> Value {
    let mut start = offset;
    let mut end = offset;
    while let Some((i, c)) = source[..start].char_indices().next_back() {
        if text::java_letter_or_digit(c) || c == '_' {
            start = i;
        } else {
            break;
        }
    }
    for (i, c) in source[offset..].char_indices() {
        if text::java_letter_or_digit(c) || c == '_' {
            end = offset + i + c.len_utf8();
        } else {
            break;
        }
    }
    let word = &source[start..end];
    let annotation = start > 0 && source.as_bytes()[start - 1] == b'@';
    if annotation || keywords().iter().any(|k| k == word) {
        if let Some(help) = help(word) {
            return json!({"contents":markdown(help)});
        }
    }
    if annotation {
        if let Some(a) = data()["annotations"]
            .as_array()
            .unwrap()
            .iter()
            .find(|a| string(a, "label")[1..] == *word)
        {
            return json!({"contents":markdown(format!("**{}**\n\n{}",string(a,"signature"),string(a,"doc")))});
        }
    }
    if let Some(scope) = index.scope(offset) {
        if let Some((name, _, _)) = scope
            .uses
            .iter()
            .find(|(_, start, end)| *start <= offset && offset < *end)
        {
            if let Some(symbol) = scope.unique(name) {
                // Each local declaration has one use; imported declarations live elsewhere.
                let local = scope.uses.iter().filter(|(n, _, _)| n == name).count();
                let imported = name.contains('.');
                let refs = if imported {
                    local
                } else {
                    local.saturating_sub(1)
                };
                return json!({"contents":markdown(format!("**{} {}**\n\n```ubnf\n{}\n```\n\n{}\n\n{} reference(s) in this document",symbol.kind,symbol.name,symbol.detail,symbol.origin,refs))});
            }
        }
    }
    json!({"contents":markdown(if index.valid {"Valid UBNF".into()}else{format!("Parse error at offset {}", index.error_offset)})})
}
