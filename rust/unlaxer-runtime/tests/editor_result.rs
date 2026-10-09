use unlaxer_runtime::editor::{CallSite, EditorParseResult, Node, NodeKind, Status};
use unlaxer_runtime::semantic::{
    Argument, Call, ModelData, Scope, SemanticModel, Signature, Symbol, Type, TypeKind,
};
use unlaxer_runtime::Span;

fn snapshot() -> SemanticModel {
    SemanticModel::new(
        "memory:nested".into(),
        3,
        "😀outer(inner(  ".into(),
        ModelData {
            types: ["Item", "Context"]
                .into_iter()
                .map(|id| Type {
                    id: id.into(),
                    kind: TypeKind::Builtin,
                    supertypes: vec![],
                    fields: vec![],
                    span: Span { start: 0, end: 0 },
                })
                .collect(),
            scopes: vec![Scope {
                id: "root".into(),
                parent: None,
                span: Span { start: 0, end: 15 },
            }],
            symbols: vec![Symbol {
                id: "item".into(),
                name: "item".into(),
                type_id: "Item".into(),
                scope: "root".into(),
                declaration: Span { start: 7, end: 12 },
                visible_from: 0,
            }],
            signatures: vec![
                Signature {
                    id: "outer".into(),
                    name: "outer".into(),
                    parameters: vec!["Context".into()],
                    result: "Context".into(),
                    span: Span { start: 0, end: 0 },
                },
                Signature {
                    id: "inner".into(),
                    name: "inner".into(),
                    parameters: vec!["Item".into()],
                    result: "Context".into(),
                    span: Span { start: 0, end: 0 },
                },
            ],
            calls: vec![
                Call {
                    id: "outer".into(),
                    signatures: vec!["outer".into()],
                    arguments: vec![Argument {
                        type_id: "?".into(),
                        span: Span { start: 7, end: 15 },
                    }],
                    span: Span { start: 1, end: 15 },
                },
                Call {
                    id: "inner".into(),
                    signatures: vec!["inner".into()],
                    arguments: vec![Argument {
                        type_id: "?".into(),
                        span: Span { start: 15, end: 15 },
                    }],
                    span: Span { start: 7, end: 15 },
                },
            ],
        },
    )
    .unwrap()
}
fn partial() -> EditorParseResult<String> {
    let model = snapshot();
    EditorParseResult::new(
        model.uri().into(),
        model.version(),
        model.source().into(),
        Status::Partial,
        None,
        vec![Node {
            kind: NodeKind::Missing,
            span: Span { start: 15, end: 15 },
            candidate_rules: vec!["Argument".into()],
            region_id: Some("child".into()),
        }],
        Some(model),
        vec![
            CallSite {
                call_id: "outer".into(),
                argument_index: 0,
                region_id: None,
            },
            CallSite {
                call_id: "inner".into(),
                argument_index: 0,
                region_id: Some("child".into()),
            },
        ],
    )
    .unwrap()
}
#[test]
fn innermost_empty_argument_uses_retained_types_without_a_strict_ast() {
    let result = partial();
    assert!(result.strict_ast().is_none());
    assert_eq!(result.call_at(15, None).unwrap().unwrap().call_id, "inner");
    assert_eq!(result.expected_types_at(15, None).unwrap(), vec!["Item"]);
    let candidates = result.complete_at(15, None, 3, "").unwrap();
    assert_eq!(candidates.len(), 1);
    assert_eq!(candidates[0].symbol.id, "item");
    assert!(result.call_at(15, Some("different")).unwrap().is_none());
    assert_eq!(
        result
            .utf16_span(Span { start: 15, end: 15 })
            .unwrap()
            .start,
        16
    );
    assert_eq!(
        result.complete_at(15, None, 4, "").unwrap_err().code,
        "EDITOR_STALE_SNAPSHOT"
    );
}
#[test]
fn strict_values_require_complete_status_and_no_defects() {
    let result = EditorParseResult::new(
        "memory:complete".into(),
        0,
        "".into(),
        Status::Complete,
        Some(42),
        vec![],
        None,
        vec![],
    )
    .unwrap();
    assert_eq!(result.strict_ast(), Some(&42));
    let failure = EditorParseResult::new(
        "memory:failed".into(),
        0,
        "".into(),
        Status::Failed,
        None::<i32>,
        vec![],
        None,
        vec![],
    )
    .unwrap();
    assert!(failure.strict_ast().is_none());
    assert!(failure.expected_types_at(0, None).unwrap().is_empty());
    assert_eq!(
        EditorParseResult::new(
            "memory:bad".into(),
            0,
            "".into(),
            Status::Partial,
            Some(42),
            vec![],
            None,
            vec![]
        )
        .unwrap_err()
        .code,
        "EDITOR_INVALID_STATUS"
    );
}
