use unlaxer_codegen::*;

fn field(name: &str) -> Field {
    Field {
        name: name.into(),
        kind: Kind::Text,
        cardinality: Cardinality::One,
    }
}
fn fixture() -> GrammarIr {
    GrammarIr {
        root: 0,
        java_whitespace: false,
        rules: vec![Rule {
            name: "Root".into(),
            body: Expression::Sequence(
                ["left", "right"]
                    .into_iter()
                    .map(|name| Expression::Capture {
                        name: name.into(),
                        expression: Box::new(Expression::Literal("x".into())),
                    })
                    .collect(),
            ),
            mapping: Some(Mapping {
                name: "Pair".into(),
                fields: vec![field("right")],
            }),
            type_constraints: vec![TypeConstraint {
                capture: field("right"),
                reference: field("left"),
            }],
            operator: None,
            catalog: None,
            skip: false,
            enum_literals: vec![],
            documentation: vec![],
        }],
    }
}

#[test]
fn private_constraint_captures_are_bound_before_checks() {
    let ir = fixture();
    validate_ir(&ir).unwrap();
    let mapper = generate(&ir)
        .unwrap()
        .into_iter()
        .find(|f| f.relative_path == "mapper.rs")
        .unwrap()
        .content;
    assert!(mapper.contains("capture.name == \"left\""));
    assert!(mapper.contains("@typeof constraint violated: right must be same type as left"));
    assert_eq!(1, mapper.matches("capture.name == \"left\"").count());
}

#[test]
fn constraints_require_a_non_skipped_owner() {
    let mut ir = fixture();
    ir.rules[0].skip = true;
    assert!(validate_ir(&ir).unwrap_err().0.contains("E-TYPEOF-MAPPING"));
}

#[test]
fn unknown_or_schema_inconsistent_constraint_fields_are_rejected() {
    let mut ir = fixture();
    ir.rules[0].type_constraints[0].reference.name = "missing".into();
    assert!(validate_ir(&ir)
        .unwrap_err()
        .0
        .contains("E-TYPEOF-UNKNOWN-CAPTURE"));
    let mut ir = fixture();
    ir.rules[0].type_constraints[0].capture.kind = Kind::Integer;
    assert!(validate_ir(&ir)
        .unwrap_err()
        .0
        .contains("constraint differs from bound field"));
    let mut ir = fixture();
    ir.rules[0].type_constraints[0].reference.kind = Kind::Enum("Missing".into());
    assert!(validate_ir(&ir)
        .unwrap_err()
        .0
        .contains("unknown enum value type"));
    let mut ir = fixture();
    let mut duplicate = ir.rules[0].type_constraints[0].clone();
    duplicate.reference.kind = Kind::Integer;
    ir.rules[0].type_constraints.push(duplicate);
    assert!(validate_ir(&ir)
        .unwrap_err()
        .0
        .contains("inconsistent constraint field"));
}

#[test]
fn transparent_ir_guards_keep_the_projected_node() {
    let mut ir = fixture();
    ir.rules[0].mapping = None;
    ir.rules.push(Rule {
        name: "Child".into(),
        body: Expression::Literal("x".into()),
        mapping: Some(Mapping {
            name: "Child".into(),
            fields: vec![],
        }),
        type_constraints: vec![],
        operator: None,
        catalog: None,
        skip: false,
        enum_literals: vec![],
        documentation: vec![],
    });
    ir.rules[0].body = Expression::Choice(
        ["left", "right"]
            .into_iter()
            .map(|name| Expression::Capture {
                name: name.into(),
                expression: Box::new(Expression::Reference(1)),
            })
            .collect(),
    );
    ir.rules[0].type_constraints[0].capture.kind = Kind::Node;
    ir.rules[0].type_constraints[0].reference.kind = Kind::Node;
    let mapper = generate(&ir)
        .unwrap()
        .into_iter()
        .find(|f| f.relative_path == "mapper.rs")
        .unwrap()
        .content;
    assert!(mapper.contains("0 => map_rule_0(tree, id),"));
    assert!(mapper.contains("let projected = map_nodes(tree, &node.children)?;"));
    assert!(mapper.contains("Ok(projected)"));
    let rule = mapper
        .split("fn map_rule_0(")
        .nth(1)
        .unwrap()
        .split("fn map_rule_1(")
        .next()
        .unwrap();
    assert_eq!(1, rule.matches("map_nodes(tree,").count());
    ir.rules[0].body =
        Expression::Sequence(vec![Expression::Reference(1), Expression::Reference(1)]);
    assert!(validate_ir(&ir)
        .unwrap_err()
        .0
        .contains("transparent guard"));
}
