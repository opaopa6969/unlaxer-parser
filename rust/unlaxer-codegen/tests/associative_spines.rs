use unlaxer_codegen::*;
fn field(name: &str, kind: Kind, cardinality: Cardinality) -> Field {
    Field {
        name: name.into(),
        kind,
        cardinality,
    }
}
fn fixture() -> GrammarIr {
    GrammarIr {
        root: 0,
        java_whitespace: false,
        rules: vec![Rule {
            name: "Expr".into(),
            skip: false,
            body: Expression::Sequence(
                ["left", "op", "right"]
                    .into_iter()
                    .map(|name| Expression::Capture {
                        name: name.into(),
                        expression: Box::new(Expression::Literal("x".into())),
                    })
                    .collect(),
            ),
            mapping: Some(Mapping {
                name: "Binary".into(),
                fields: vec![
                    field("left", Kind::NullableNode, Cardinality::One),
                    field("op", Kind::Text, Cardinality::Many),
                    field("right", Kind::Node, Cardinality::Many),
                ],
            }),
            operator: Some(Operator {
                associativity: Associativity::Left,
                precedence: 10,
            }),
            catalog: None,
            documentation: vec![],
            enum_literals: vec![],
            type_constraints: vec![],
        }],
    }
}
#[test]
fn nullable_node_is_restricted_to_scalar_associative_left() {
    validate_ir(&fixture()).unwrap();
    for cardinality in [Cardinality::Optional, Cardinality::Many] {
        let mut ir = fixture();
        ir.rules[0].mapping.as_mut().unwrap().fields[0].cardinality = cardinality;
        assert!(validate_ir(&ir).unwrap_err().0.contains("E-ASSOC"));
    }
    let mut ir = fixture();
    ir.rules[0].operator = None;
    assert!(validate_ir(&ir).unwrap_err().0.contains("E-ASSOC"));
    let mut ir = fixture();
    ir.rules[0].mapping.as_mut().unwrap().fields[2].kind = Kind::NullableNode;
    assert!(validate_ir(&ir).unwrap_err().0.contains("E-ASSOC"));
}
#[test]
fn nullable_node_is_not_an_arbitrary_private_constraint_type() {
    let mut ir = fixture();
    let fake = field("op", Kind::NullableNode, Cardinality::One);
    ir.rules[0].type_constraints.push(TypeConstraint {
        capture: fake.clone(),
        reference: fake,
    });
    assert!(validate_ir(&ir).unwrap_err().0.contains("E-ASSOC"));
}
