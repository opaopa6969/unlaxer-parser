use unlaxer_codegen::*;

fn cap(name: &str, expression: Expression) -> Expression {
    Expression::Capture {
        name: name.into(),
        expression: Box::new(expression),
    }
}
fn field(name: &str, kind: Kind, cardinality: Cardinality) -> Field {
    Field {
        name: name.into(),
        kind,
        cardinality,
    }
}
fn rule(name: &str, body: Expression, mapping: Option<(&str, Vec<Field>)>) -> Rule {
    Rule {
        name: name.into(),
        body,
        mapping: mapping.map(|(name, fields)| Mapping {
            name: name.into(),
            fields,
        }),
        operator: None,
    }
}

pub fn fixture(name: &str) -> GrammarIr {
    use Cardinality::*;
    use Expression::*;
    use Kind::*;
    let rules = match name {
        "evolution" => vec![
            rule(
                "Expression",
                Choice(vec![Reference(5), Reference(4), Reference(1), Reference(2)]),
                None,
            ),
            rule(
                "Binary",
                Sequence(vec![
                    cap("left", Reference(2)),
                    Choice(vec![
                        cap("op", Literal("+".into())),
                        cap("op", Literal("*".into())),
                    ]),
                    cap("right", Reference(2)),
                ]),
                Some((
                    "Binary",
                    vec![
                        field("left", Node, One),
                        field("op", Text, One),
                        field("right", Node, One),
                    ],
                )),
            ),
            rule(
                "Literal",
                Sequence(vec![cap("value", Reference(3))]),
                Some(("Number", vec![field("value", Text, One)])),
            ),
            rule("Digits", Sequence(vec![NumberToken]), None),
            rule(
                "Negation",
                Sequence(vec![
                    Literal("neg".into()),
                    Literal("(".into()),
                    cap("value", Reference(0)),
                    Literal(")".into()),
                ]),
                Some(("Negation", vec![field("value", Node, One)])),
            ),
            rule(
                "Conditional",
                Sequence(vec![
                    Literal("if".into()),
                    Literal("(".into()),
                    cap("condition", Reference(2)),
                    Literal(",".into()),
                    cap("thenExpr", Reference(0)),
                    Literal(",".into()),
                    cap("elseExpr", Reference(0)),
                    Literal(")".into()),
                ]),
                Some((
                    "Conditional",
                    vec![
                        field("condition", Node, One),
                        field("thenExpr", Node, One),
                        field("elseExpr", Node, One),
                    ],
                )),
            ),
        ],
        "fields" => vec![
            rule(
                "Root",
                Sequence(vec![
                    cap("type", IdentifierToken),
                    OptionalExpr(Box::new(cap("text", QuotedToken('\'')))),
                    Repeat {
                        child: Box::new(cap("texts", QuotedToken('"'))),
                        min: 0,
                        max: None,
                    },
                    cap("child", Reference(1)),
                    OptionalExpr(Box::new(cap("maybe", Reference(1)))),
                    Repeat {
                        child: Box::new(cap("children", Reference(1))),
                        min: 0,
                        max: None,
                    },
                ]),
                Some((
                    "Value",
                    vec![
                        field("type", Text, One),
                        field("text", Text, Optional),
                        field("texts", Text, Many),
                        field("child", Node, One),
                        field("maybe", Node, Optional),
                        field("children", Node, Many),
                    ],
                )),
            ),
            rule(
                "Leaf",
                Sequence(vec![cap("value", NumberToken)]),
                Some(("Leaf", vec![field("value", Text, One)])),
            ),
        ],
        "shared" => {
            let mapping = Some(("Value", vec![field("value", Text, One)]));
            let mut left = rule(
                "Left",
                Sequence(vec![cap("value", Literal("a".into()))]),
                mapping.clone(),
            );
            left.operator = Some(Operator {
                associativity: Associativity::None,
                precedence: 20,
            });
            let mut right = rule(
                "Right",
                Sequence(vec![cap("value", Literal("b".into()))]),
                mapping,
            );
            right.operator = Some(Operator {
                associativity: Associativity::None,
                precedence: 10,
            });
            vec![
                rule("Root", Choice(vec![Reference(1), Reference(2)]), None),
                left,
                right,
            ]
        }
        _ => panic!("unknown fixture {name}"),
    };
    GrammarIr {
        rules,
        root: 0,
        java_whitespace: true,
    }
}
