use crate::generated::{ast::Ast, mapper, parser};
use unlaxer_runtime::{
    editor::{CallSite, EditorParseResult, Node as Defect, NodeKind, Status},
    editor_cst::{self, EditorCst, Node, Options},
    semantic::*,
    Span,
};

pub struct Parsed {
    pub result: EditorParseResult<Ast>,
    pub cst: EditorCst,
}
fn captures<'a>(node: &'a Node, name: &'a str) -> impl Iterator<Item = &'a editor_cst::Capture> {
    node.captures
        .iter()
        .filter(move |capture| capture.name == name)
}
fn values(node: &Node, name: &str) -> Vec<String> {
    captures(node, name)
        .filter(|capture| !capture.synthetic)
        .map(|capture| capture.text.trim().to_owned())
        .collect()
}
fn value(node: &Node, name: &str) -> String {
    values(node, name)[0].clone()
}
fn damaged(span: Span, cst: &EditorCst) -> bool {
    cst.defects().iter().any(|defect| {
        defect.kind == editor_cst::DefectKind::Error
            && span.start < defect.span.end
            && defect.span.start < span.end
    })
}
fn healthy(node: &Node, cst: &EditorCst) -> bool {
    !node.synthetic
        && !damaged(node.span, cst)
        && !node.captures.iter().any(|capture| capture.synthetic)
}
/// Only original captures become declarations; repaired nodes remain editor metadata.
pub fn parse(
    uri: &str,
    version: i64,
    source: &str,
    region: Option<&str>,
    options: Options,
) -> Result<Parsed, String> {
    parse_with_completions(
        uri,
        version,
        source,
        region,
        options,
        &["?", ")", ";", "}", "a", ":", "{"],
    )
}
pub fn parse_with_completions(
    uri: &str,
    version: i64,
    source: &str,
    region: Option<&str>,
    options: Options,
    completions: &[&str],
) -> Result<Parsed, String> {
    let cst = parser::parse_editor_cst(source, completions, options).map_err(str::to_owned)?;
    let mut data = ModelData::default();
    data.scopes.push(Scope {
        id: "root".into(),
        parent: None,
        span: Span {
            start: 0,
            end: source.chars().count(),
        },
    });
    for node in cst.nodes().iter().filter(|node| healthy(node, &cst)) {
        let span = node.span;
        match node.rule.as_str() {
            "TypeDecl" => {
                let kind = match value(node, "kind").as_str() {
                    "builtin" => TypeKind::Builtin,
                    "interface" => TypeKind::Interface,
                    "record" => TypeKind::Record,
                    _ => return Err("invalid kind".into()),
                };
                let fields = cst
                    .nodes()
                    .iter()
                    .filter(|child| {
                        child.rule == "Field"
                            && span.start <= child.span.start
                            && child.span.end <= span.end
                            && healthy(child, &cst)
                    })
                    .map(|child| Field {
                        name: value(child, "name"),
                        type_id: value(child, "type"),
                        span: child.span,
                    })
                    .collect();
                data.types.push(Type {
                    id: value(node, "name"),
                    kind,
                    supertypes: values(node, "parents"),
                    fields,
                    span,
                });
            }
            "ValueDecl" => data.symbols.push(Symbol {
                id: format!("value:{}", span.start),
                name: value(node, "name"),
                type_id: value(node, "type"),
                scope: "root".into(),
                declaration: span,
                visible_from: span.end,
            }),
            "FunctionDecl" => data.signatures.push(Signature {
                id: format!("fn:{}", span.start),
                name: value(node, "name"),
                parameters: values(node, "parameters"),
                result: value(node, "result"),
                span,
            }),
            _ => {}
        }
    }
    let mut sites = Vec::new();
    for node in cst
        .nodes()
        .iter()
        .filter(|node| node.rule == "Call" && !damaged(node.span, &cst))
    {
        let names: Vec<_> = captures(node, "name").collect();
        if names.len() != 1 || names[0].synthetic {
            continue;
        }
        let arguments: Vec<_> = captures(node, "arguments")
            .map(|capture| {
                let type_id = if capture.synthetic {
                    UNKNOWN
                } else {
                    data.symbols
                        .iter()
                        .find(|symbol| {
                            symbol.name == capture.text.trim()
                                && symbol.visible_from <= capture.span.start
                        })
                        .map_or(UNKNOWN, |symbol| symbol.type_id.as_str())
                };
                Argument {
                    type_id: type_id.into(),
                    span: capture.span,
                }
            })
            .collect();
        let id = format!("call:{}", node.span.start);
        for index in 0..arguments.len() {
            sites.push(CallSite {
                call_id: id.clone(),
                argument_index: index,
                region_id: region.map(str::to_owned),
            });
        }
        data.calls.push(Call {
            id,
            signatures: data
                .signatures
                .iter()
                .filter(|signature| signature.name == names[0].text.trim())
                .map(|signature| signature.id.clone())
                .collect(),
            arguments,
            span: node.span,
        });
    }
    let model = SemanticModel::new(uri.into(), version, source.into(), data)
        .map_err(|error| error.to_string())?;
    let status = match cst.status() {
        editor_cst::Status::Complete => Status::Complete,
        editor_cst::Status::Partial => Status::Partial,
        editor_cst::Status::Failed => Status::Failed,
    };
    let ast = if status == Status::Complete {
        Some(mapper::map(
            &parser::parse_tree(source).map_err(|error| error.to_string())?,
        )?)
    } else {
        None
    };
    let defects = cst
        .defects()
        .iter()
        .map(|defect| Defect {
            kind: match defect.kind {
                editor_cst::DefectKind::Missing => NodeKind::Missing,
                editor_cst::DefectKind::Error => NodeKind::Error,
            },
            span: defect.span,
            candidate_rules: defect.candidate_rules.clone(),
            region_id: region.map(str::to_owned),
        })
        .collect();
    let result = EditorParseResult::new(
        uri.into(),
        version,
        source.into(),
        status,
        ast,
        defects,
        Some(model),
        sites,
    )
    .map_err(|error| error.to_string())?;
    Ok(Parsed { result, cst })
}
