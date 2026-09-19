use unlaxer_runtime::{Expr, ParseContext, Rule};

#[test]
fn code_start_matches_the_tinyexpression_lexical_shape_and_line_endings() {
    for source in [
        "```java:a.B",
        "```_scheme:_Class_1\n",
        "```java:a.b.Co\r",
        "```java:A0.b2.C3\r\n",
    ] {
        let mut context = ParseContext::new(source);
        let matched = context.parse(&Expr::code_start()).unwrap();
        assert_eq!(matched.span.start, 0, "{source:?}");
        assert_eq!(matched.span.end, source.chars().count(), "{source:?}");
        assert_eq!(context.position(), source.chars().count(), "{source:?}");
        assert_eq!(context.matched_position(), context.position(), "{source:?}");
    }

    for source in [
        " ```java:a.B",
        "````java:a.B",
        "``` java:a.B",
        "```java :a.B",
        "```java: a.B",
        "```java:a. B",
        "```java:a.B ",
        "```java:a.B/* comment */",
        "```9java:a.B",
        "```java:9a.B",
        "```java:a.",
        "```java:a..B",
        "```java:a.型",
        "```型:a.B",
    ] {
        let mut context = ParseContext::new(source);
        assert!(context.parse(&Expr::CodeStart).is_err(), "{source:?}");
        assert_eq!((context.position(), context.matched_position()), (0, 0));
    }
}

#[test]
fn code_end_requires_a_line_boundary_and_consumes_crlf_cr_lf_or_eof() {
    for source in ["```", "```\n", "```\r", "```\r\n"] {
        let mut context = ParseContext::new(source);
        context.parse(&Expr::code_end()).unwrap();
        assert_eq!(context.position(), source.chars().count(), "{source:?}");
        assert_eq!(context.matched_position(), context.position(), "{source:?}");
    }
    for line_break in ['\r', '\n'] {
        let source = format!("{line_break}```");
        let mut context = ParseContext::new(&source);
        context.parse(&Expr::Any).unwrap();
        context.parse(&Expr::CodeEnd).unwrap();
        assert_eq!((context.position(), context.matched_position()), (4, 4));
    }

    for source in [" ```", " ````", "``` ", "```x", "``\n", "````\n"] {
        let mut context = ParseContext::new(source);
        assert!(context.parse(&Expr::CodeEnd).is_err(), "{source:?}");
        assert_eq!((context.position(), context.matched_position()), (0, 0));
    }
}

#[test]
fn code_fences_remain_atomic_inside_java_style_grammar_sequences() {
    let rules = [Rule {
        name: "Root",
        expression: Expr::sequence([Expr::CodeStart]),
    }];
    for source in [
        "``` java:a.B",
        "```java :a.B",
        "```java: a.B",
        "```java:a .B",
        "```java:a. B",
        "```java:a.B // trailing",
        "```java:/* comment */a.B",
    ] {
        assert!(
            unlaxer_runtime::parse(&rules, 0, true, source).is_err(),
            "{source:?}"
        );
    }
}

#[test]
fn owned_unicode_source_nonzero_positions_and_matched_cursor_are_preserved() {
    let source = String::from("😀\n```java:a.B\r\ntail");
    let mut context = ParseContext::new(&source);
    context.parse(&Expr::literal("😀\n")).unwrap();
    let matched = context.parse(&Expr::CodeStart).unwrap();
    assert_eq!(matched.span.start, 2);
    assert_eq!(context.text(matched.span), Some("```java:a.B\r\n"));
    assert_eq!((context.position(), context.matched_position()), (15, 15));
    assert_eq!(context.remaining(), "tail");

    let source = String::from("😀\n```\ntail");
    let mut context = ParseContext::new(&source);
    context.parse(&Expr::literal("😀\n")).unwrap();
    context.parse(&Expr::JavaUntil("```")).unwrap();
    assert_eq!((context.position(), context.matched_position()), (2, 5));
    context.parse(&Expr::CodeEnd).unwrap();
    assert_eq!((context.position(), context.matched_position()), (6, 6));
    assert_eq!(context.remaining(), "tail");

    let source = String::from("😀\n```oops");
    let mut context = ParseContext::new(&source);
    context.parse(&Expr::literal("😀\n")).unwrap();
    context
        .parse(&Expr::JavaLookahead {
            pattern: "```",
            positive: true,
        })
        .unwrap();
    context.set_state("attempts", 7_u32);
    assert_eq!((context.position(), context.matched_position()), (2, 5));
    let error = context.parse(&Expr::CodeEnd.capture("failed")).unwrap_err();
    assert_eq!(error.offset, 5);
    assert_eq!((context.position(), context.matched_position()), (2, 5));
    assert_eq!(context.captured("failed"), None);
    assert_eq!(context.state::<u32>("attempts"), Some(&7));

    let source = String::from("😀```\n");
    let mut context = ParseContext::new(&source);
    context.parse(&Expr::literal("😀")).unwrap();
    assert!(context.parse(&Expr::CodeEnd).is_err());
    assert_eq!((context.position(), context.matched_position()), (1, 1));
}
