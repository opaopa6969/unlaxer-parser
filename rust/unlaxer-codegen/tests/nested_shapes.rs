use unlaxer_codegen::*;

fn fixture(kind: Kind, cardinality: Cardinality) -> GrammarIr {
    GrammarIr {
        root: 0,
        java_whitespace: false,
        rules: vec![Rule {
            name: "Root".into(),
            body: Expression::Capture {
                name: "value".into(),
                expression: Box::new(Expression::CaptureBoundary {
                    kind: BoundaryKind::Value,
                    child: Box::new(Expression::Literal("x".into())),
                }),
            },
            mapping: Some(Mapping {
                name: "Root".into(),
                fields: vec![Field {
                    name: "value".into(),
                    kind,
                    cardinality,
                }],
            }),
            operator: None,
            catalog: None,
            skip: false,
            enum_literals: vec![],
            documentation: vec![],
            type_constraints: vec![],
        }],
    }
}
fn nested(leaf: Kind, containers: Vec<Cardinality>) -> Kind {
    Kind::Nested {
        leaf: Box::new(leaf),
        containers,
    }
}
#[test]
fn malformed_public_nested_schemas_are_rejected_before_emission() {
    for (kind, cardinality) in [
        (nested(Kind::Node, vec![]), Cardinality::One),
        (nested(Kind::Text, vec![Cardinality::One]), Cardinality::One),
        (
            nested(Kind::Text, vec![Cardinality::Optional; 33]),
            Cardinality::One,
        ),
        (nested(Kind::Text, vec![]), Cardinality::Many),
        (nested(nested(Kind::Text, vec![]), vec![]), Cardinality::One),
    ] {
        assert!(generate(&fixture(kind, cardinality))
            .unwrap_err()
            .0
            .contains("E-NESTED-SHAPE"));
    }
    let mut ir = fixture(nested(Kind::Text, vec![]), Cardinality::One);
    let field = ir.rules[0].mapping.as_ref().unwrap().fields[0].clone();
    ir.rules[0].type_constraints.push(TypeConstraint {
        capture: field.clone(),
        reference: field,
    });
    assert!(generate(&ir).unwrap_err().0.contains("E-NESTED-SHAPE"));
}
#[test]
fn nested_scalar_schema_has_an_owned_value_and_borrowed_evaluator_parameter() {
    for (leaf, ty) in [(Kind::Text, "String"), (Kind::Integer, "i32")] {
        let ir = fixture(nested(leaf, vec![]), Cardinality::One);
        generate(&ir).unwrap();
        let field = &ir.rules[0].mapping.as_ref().unwrap().fields[0];
        assert_eq!(ast_field_type(field), ty);
        assert_eq!(evaluator_parameter_type(field), format!("&{ty}"));
    }
}
