//! Explicit native integration with both production parsers; no evaluator or compiler execution.
use std::collections::{BTreeMap, HashMap, HashSet};
use tinyexpression_rs::{
    formula_info,
    generated::{scanners, ubnfc},
};
use unlaxer_runtime::{
    language_queries::{self, LanguageQueries, Project, Provider},
    source::*,
    Span,
};

pub fn language(id: &str) -> Language {
    let (package, version, grammar, entry) = match id {
        "formulainfo" => (
            "tinyexpression",
            "f86ce8a5ab0ab7d23fdba2cd477187df8aa7607c",
            "FormulaInfo",
            "Document",
        ),
        "tinyexpression" => (
            "tinyexpression",
            "f86ce8a5ab0ab7d23fdba2cd477187df8aa7607c",
            "TinyExpressionP4",
            "Formula",
        ),
        "java" => ("lang/java", "0.1.0", "Java21", "CompilationUnit"),
        _ => unreachable!(),
    };
    Language {
        id: id.into(),
        package_id: package.into(),
        version: version.into(),
        grammar: grammar.into(),
        entry: entry.into(),
    }
}
pub struct Binding {
    pub host: Snapshot,
    pub regions: Vec<Region>,
    pub java_files: BTreeMap<String, String>,
    pub open_ends: HashSet<String>,
}
impl Binding {
    pub fn tree(&self) -> Result<LanguageRegions> {
        LanguageRegions::with_open_ends(
            self.host.clone(),
            self.regions.clone(),
            self.open_ends.clone(),
        )
    }
    pub fn queries(&self, project: Project, java: Box<dyn Provider>) -> Result<LanguageQueries> {
        LanguageQueries::new(
            self.tree()?,
            project,
            HashMap::from([(
                language("java"),
                Box::new(JavaProvider {
                    provider: java,
                    regions: self.regions.clone(),
                    files: self.java_files.clone(),
                }) as Box<dyn Provider>,
            )]),
        )
    }
}
struct JavaProvider {
    provider: Box<dyn Provider>,
    regions: Vec<Region>,
    files: BTreeMap<String, String>,
}
impl Provider for JavaProvider {
    fn capabilities(&self) -> HashSet<Operation> {
        self.provider.capabilities()
    }
    fn diagnostics(
        &self,
        request: &language_queries::Request<'_>,
    ) -> Result<language_queries::DiagnosticResponse> {
        let parameters = self.parameters(request)?;
        self.provider.diagnostics(&language_queries::Request {
            parameters: &parameters,
            ..*request
        })
    }
    fn query(&self, request: &language_queries::Request<'_>) -> Result<language_queries::Response> {
        let parameters = self.parameters(request)?;
        self.provider.query(&language_queries::Request {
            parameters: &parameters,
            ..*request
        })
    }
}
impl JavaProvider {
    fn parameters(
        &self,
        request: &language_queries::Request<'_>,
    ) -> Result<BTreeMap<String, String>> {
        if !self.regions.iter().any(|r| {
            r.id == request.region.id
                && r.source_map.output() == request.region.source_map.output()
                && r.language == request.region.language
        }) {
            return Err("foreign region");
        }
        let file = self
            .files
            .get(&request.region.id)
            .ok_or("foreign Java filename")?;
        let mut parameters = request.parameters.clone();
        if parameters
            .get("fileName")
            .is_some_and(|value| value != file)
        {
            return Err("foreign Java filename");
        }
        parameters.insert("fileName".into(), file.clone());
        Ok(parameters)
    }
}
pub fn parse(host: &Snapshot) -> Result<Binding> {
    parse_mode(host, false)
}
pub fn parse_editor(host: &Snapshot) -> Result<Binding> {
    parse_mode(host, true)
}
fn parse_mode(host: &Snapshot, editor: bool) -> Result<Binding> {
    if host.len() > 1_048_576 {
        return Err("production document limit");
    }
    let document =
        formula_info::parse_document(&host.text).map_err(|_| "invalid FormulaInfo document")?;
    let all = Span {
        start: 0,
        end: host.len(),
    };
    let mut regions = vec![region(
        host,
        "root",
        None,
        "formulainfo",
        all,
        all,
        State::Complete,
    )?];
    let mut files = BTreeMap::new();
    let mut open_ends = HashSet::new();
    let mut index = 0;
    for block in document.blocks {
        if block.entries.iter().any(|entry| entry.value().is_none()) {
            return Err("invalid FormulaInfo value");
        }
        let formulas: Vec<_> = block
            .entries
            .iter()
            .filter(|entry| entry.key == "formula")
            .collect();
        if formulas.len() > 1 {
            return Err("duplicate formula");
        }
        let Some(formula) = formulas.first() else {
            continue;
        };
        if formula.value().is_none() {
            return Err("invalid FormulaInfo document");
        }
        index += 1;
        if index > 256 {
            return Err("production section limit");
        }
        let raw = &formula.raw_value;
        let skip = if raw.starts_with("\r\n") {
            2
        } else if raw.starts_with(['\n', '\r']) {
            1
        } else {
            0
        };
        let raw = &raw[skip..];
        let start = formula.value_span.start + skip;
        let body = Span {
            start,
            end: start + raw.chars().count(),
        };
        if host.slice(body)? != raw {
            return Err("FormulaInfo source mismatch");
        }
        let input = mask_comments(raw);
        let id = format!("formula/{index}");
        let options = ubnfc::ParseOptions {
            lexical: true,
            occurrences: true,
            max_depth: 512,
            ..Default::default()
        };
        let p4 = ubnfc::parse_entry_with_scanner(
            "TinyExpressionP4",
            None,
            &input,
            options,
            &mut scanners::registry(),
        )?;
        regions.push(region(
            host,
            &id,
            Some("root"),
            "tinyexpression",
            body,
            body,
            if p4.ok {
                State::Complete
            } else {
                State::Failed
            },
        )?);
        if !p4.ok {
            if editor {
                let parent_index = regions.len() - 1;
                recover_prefix(
                    host,
                    &id,
                    start,
                    &input,
                    &mut regions,
                    &mut files,
                    &mut open_ends,
                )?;
                if regions.len() > parent_index + 1 {
                    regions[parent_index].parse_state = State::Partial;
                }
            }
            continue;
        }
        let tokens: Vec<_> = p4
            .tokens
            .iter()
            .filter(|token| token.rule_id == "TinyExpressionP4::CodeBlock")
            .collect();
        if tokens.len() % 2 != 0 {
            return Err("P4 lexical contract changed");
        }
        let chars: Vec<char> = input.chars().collect();
        for (number, pair) in tokens.chunks(2).enumerate() {
            let open = pair[0];
            let close = pair[1];
            let header: String = chars[open.span[0]..open.span[1]].iter().collect();
            let Some(class) = header.trim().strip_prefix("```java:") else {
                continue;
            };
            let child = format!("{id}/java/{}", number + 1);
            // Rust scanner stops before the opening line terminator; the Java scanner includes it.
            let mut body_start = open.span[1];
            if chars.get(body_start) == Some(&'\r') {
                body_start += 1;
            }
            if chars.get(body_start) == Some(&'\n') {
                body_start += 1;
            }
            let mut full_end = close.span[1];
            if chars.get(full_end) == Some(&'\r') {
                full_end += 1;
            }
            if chars.get(full_end) == Some(&'\n') {
                full_end += 1;
            }
            let full = Span {
                start: start + open.span[0],
                end: start + full_end,
            };
            let java_body = Span {
                start: start + body_start,
                end: start + close.span[0],
            };
            regions.push(region(
                host,
                &child,
                Some(&id),
                "java",
                full,
                java_body,
                State::Complete,
            )?);
            files.insert(child, format!("{}.java", class.rsplit('.').next().unwrap()));
        }
    }
    if index == 0 {
        return Err("no formula section");
    }
    let result = Binding {
        host: host.clone(),
        regions,
        java_files: files,
        open_ends,
    };
    result.tree()?;
    Ok(result)
}
fn recover_prefix(
    host: &Snapshot,
    parent: &str,
    start: usize,
    input: &str,
    regions: &mut Vec<Region>,
    files: &mut BTreeMap<String, String>,
    open_ends: &mut HashSet<String>,
) -> Result<()> {
    let mut from = 0;
    let mut number = 0;
    while from < input.len() {
        if number >= 256 {
            return Err("production code block limit");
        }
        let remaining = &input[from..];
        let options = ubnfc::ParseOptions {
            require_eof: false,
            build_ast: false,
            lexical: true,
            occurrences: true,
            max_depth: 512,
            ..Default::default()
        };
        let mut parsed = ubnfc::parse_entry_with_scanner(
            "TinyExpressionP4",
            Some("CodeBlock"),
            remaining,
            options,
            &mut scanners::registry(),
        )?;
        let partial = !parsed.ok;
        if partial {
            parsed = ubnfc::parse_entry_with_scanner(
                "TinyExpressionP4",
                Some("CodeBlock"),
                &format!("{remaining}\n```\n"),
                options,
                &mut scanners::registry(),
            )?;
        }
        if !parsed.ok {
            break;
        }
        let tokens: Vec<_> = parsed
            .tokens
            .iter()
            .filter(|token| token.rule_id == "TinyExpressionP4::CodeBlock")
            .collect();
        if tokens.len() != 2 {
            return Err("P4 CodeBlock lexical contract changed");
        }
        let open = tokens[0];
        let close = tokens[1];
        let chars: Vec<_> = remaining.chars().collect();
        let size = chars.len();
        if partial && (close.span[0] < size || open.span[1] > size) {
            break;
        }
        let mut body_start = open.span[1];
        if chars.get(body_start) == Some(&'\r') {
            body_start += 1;
        }
        if chars.get(body_start) == Some(&'\n') {
            body_start += 1;
        }
        if partial && body_start == size && !remaining.ends_with('\n') {
            break;
        }
        let mut consumed = if partial { size } else { close.span[1] };
        if !partial {
            if chars.get(consumed) == Some(&'\r') {
                consumed += 1;
            }
            if chars.get(consumed) == Some(&'\n') {
                consumed += 1;
            }
        }
        if consumed == 0 || consumed > size {
            break;
        }
        let header: String = chars[open.span[0]..open.span[1]].iter().collect();
        number += 1;
        if let Some(class) = header.trim().strip_prefix("```java:") {
            let offset = start + input[..from].chars().count();
            let child = format!("{parent}/java/{number}");
            regions.push(region(
                host,
                &child,
                Some(parent),
                "java",
                Span {
                    start: offset + open.span[0],
                    end: offset + consumed,
                },
                Span {
                    start: offset + body_start,
                    end: offset + if partial { size } else { close.span[0] },
                },
                if partial {
                    State::Partial
                } else {
                    State::Complete
                },
            )?);
            files.insert(
                child.clone(),
                format!("{}.java", class.rsplit('.').next().unwrap()),
            );
            if partial {
                open_ends.insert(child);
            }
        }
        if partial {
            break;
        }
        from += remaining
            .char_indices()
            .nth(consumed)
            .map_or(remaining.len(), |(byte, _)| byte);
    }
    Ok(())
}
// Match Character.isWhitespace used by the production Java editor view (NBSP is excluded).
fn java_whitespace(c: char) -> bool {
    matches!(c, '\u{9}'..='\u{d}' | '\u{1c}'..='\u{20}' | '\u{1680}' |
        '\u{2000}'..='\u{2006}' | '\u{2008}'..='\u{200a}' | '\u{2028}' |
        '\u{2029}' | '\u{205f}' | '\u{3000}')
}
fn mask_comments(raw: &str) -> String {
    raw.split_inclusive('\n')
        .map(|line| {
            if line.trim_start_matches(java_whitespace).starts_with('#') {
                line.chars()
                    .map(|c| if c == '\r' || c == '\n' { c } else { ' ' })
                    .collect()
            } else {
                line.to_owned()
            }
        })
        .collect()
}
fn region(
    host: &Snapshot,
    id: &str,
    parent: Option<&str>,
    lang: &str,
    full: Span,
    body: Span,
    state: State,
) -> Result<Region> {
    let output = Snapshot::new(
        format!("{}#production/{id}", host.uri),
        host.version,
        host.slice(body)?,
    )?;
    let source_map = SourceMap::new(
        output.clone(),
        vec![Segment {
            output: Span {
                start: 0,
                end: output.len(),
            },
            kind: Kind::Copy,
            origin: Some(Location::new(host.clone(), body)?),
        }],
    )?;
    Ok(Region {
        id: id.into(),
        parent: parent.map(str::to_owned),
        language: language(lang),
        full,
        body,
        source_map,
        parse_state: state,
    })
}
