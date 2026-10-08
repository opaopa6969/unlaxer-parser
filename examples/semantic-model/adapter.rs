use crate::generated::{ast::Ast, mapper, parser};
use unlaxer_runtime::{semantic::*, Span};

/// Adapter for model.ubnf, compiled with its generated Rust modules.
pub fn model(source: &str, version: i64) -> Result<SemanticModel, String> {
    let tree = parser::parse_tree(source).map_err(|e| format!("syntax: {e:?}"))?;
    let ast = mapper::map(&tree)?;
    let Ast::Document { items, .. } = ast else {
        return Err("expected Document".into());
    };
    let mut data = ModelData::default();
    data.scopes.push(Scope {
        id: "root".into(),
        parent: None,
        span: Span {
            start: 0,
            end: source.chars().count(),
        },
    });
    for node in &items {
        match node {
            Ast::TypeDecl {
                span,
                kind,
                name,
                parents,
                fields,
            } => {
                let kind = match kind.as_str() {
                    "builtin" => TypeKind::Builtin,
                    "interface" => TypeKind::Interface,
                    "record" => TypeKind::Record,
                    _ => return Err("invalid kind".into()),
                };
                let mut members = Vec::new();
                for field in fields {
                    let Ast::Field { span, name, r#type } = field else {
                        return Err("expected Field".into());
                    };
                    members.push(Field {
                        name: name.clone(),
                        type_id: r#type.clone(),
                        span: *span,
                    });
                }
                data.types.push(Type {
                    id: name.clone(),
                    kind,
                    supertypes: parents.clone(),
                    fields: members,
                    span: *span,
                });
            }
            Ast::ValueDecl { span, name, r#type } => data.symbols.push(Symbol {
                id: format!("value:{}", span.start),
                name: name.clone(),
                type_id: r#type.clone(),
                scope: "root".into(),
                declaration: *span,
                visible_from: span.end,
            }),
            Ast::FunctionDecl {
                span,
                name,
                parameters,
                result,
            } => data.signatures.push(Signature {
                id: format!("fn:{}", span.start),
                name: name.clone(),
                parameters: parameters.clone(),
                result: result.clone(),
                span: *span,
            }),
            _ => {}
        }
    }
    for node in &items {
        if let Ast::Call {
            span,
            name,
            arguments,
        } = node
        {
            let mut args = Vec::new();
            for a in arguments {
                let Ast::Argument { span, value } = a else {
                    return Err("expected Argument".into());
                };
                let type_id = data
                    .symbols
                    .iter()
                    .find(|s| s.name == *value && s.visible_from <= span.start)
                    .map_or(UNKNOWN, |s| s.type_id.as_str())
                    .to_owned();
                args.push(Argument {
                    type_id,
                    span: *span,
                });
            }
            data.calls.push(Call {
                id: format!("call:{}", span.start),
                signatures: data
                    .signatures
                    .iter()
                    .filter(|s| s.name == *name)
                    .map(|s| s.id.clone())
                    .collect(),
                arguments: args,
                span: *span,
            });
        }
    }
    SemanticModel::new("memory:typed-model".into(), version, source.into(), data)
        .map_err(|e| e.to_string())
}
