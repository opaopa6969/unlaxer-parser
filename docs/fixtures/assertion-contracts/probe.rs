use unlaxer_runtime::{
    json_string, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult, Severity,
};

fn effect(context: &mut ParseContext<'_>) -> ParseResult {
    let result = context.parse(&Expr::literal("a"))?;
    context.set_state("owned", 9_u32);
    context.scopes_mut().enter();
    context.scopes_mut().declare("inside", result.span.start);
    context.scopes_mut().add_reference("inside", 0, 1);
    context
        .scopes_mut()
        .add_diagnostic("speculation", 0, 1, Severity::Warning);
    Ok(result)
}
fn read(context: &mut ParseContext<'_>) -> ParseResult {
    assert_eq!(context.state::<u32>("owned"), Some(&9));
    assert!(context.scopes().is_declared("inside"));
    assert_eq!(context.captured("site"), Some("a"));
    context.parse(&Expr::literal("b"))
}
fn child(kind: &str) -> Expr {
    let ab = Expr::literal("a").then(Expr::literal("b"));
    match kind {
        "literal" => Expr::literal("ab"),
        "unicode" => Expr::literal("😀\r\n界"),
        "optional" => ab.then(Expr::literal("!").optional()),
        "choice" => ab.then(Expr::literal("!")).or(Expr::literal("ab")),
        "failure" => ab.then(Expr::literal("!")),
        "nested" => Expr::literal("ab").ahead(),
        "nested-sibling" => Expr::literal("a").ahead().then(Expr::literal("b")),
        "state" => Expr::Custom(effect)
            .capture("site")
            .then(Expr::Custom(read)),
        "state-failure" => Expr::Custom(effect)
            .capture("site")
            .then(Expr::Custom(read))
            .then(Expr::literal("!")),
        _ => panic!("unknown child {kind}"),
    }
}
fn observe(_id: &str, input: &str, prefix: &'static str, kind: &str, suffix: Option<&'static str>) {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for policy in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ] {
            let mut context = ParseContext::with_options(
                input,
                ParseOptions::with_memoization(memo).with_diagnostics(policy),
            );
            context.set_state("owned", 7_u32);
            context.scopes_mut().declare("before", 0);
            context.scopes_mut().add_reference("before", 0, 1);
            context
                .scopes_mut()
                .add_diagnostic("baseline", 0, 1, Severity::Info);
            let mut assertion = String::new();
            let result = context.transaction(|context| {
                context.parse(&Expr::literal(prefix))?;
                let expr = if kind == "java-literal" { Expr::JavaLookahead { pattern: "ab", positive: true } } else { child(kind).ahead() };
                let asserted = context.parse(&expr);
                let span = asserted.as_ref().map_or("null".to_string(), |value|
                    format!("[\"consumed\",{},{},{}]", value.span.start, value.span.end, json_string(context.text(value.span).unwrap())));
                assertion = format!("{{\"accepted\":{},\"consumed\":{},\"matched\":{},\"tokens\":[],\"captures\":[],\"span\":{}}}",
                    asserted.is_ok(), context.position(), context.matched_position(), span);
                let matched = asserted?;
                if let Some(suffix) = suffix { context.parse(&Expr::literal(suffix)) } else { Ok(matched) }
            });
            assert!(context.capture_spans("site").is_empty());
            assert!(result
                .as_ref()
                .map_or(true, |parsed| parsed.nodes.is_empty()
                    && parsed.captures.is_empty()));
            assert_eq!(context.scopes().all_declarations()[0].name, "before");
            assert_eq!(context.scopes().all_references()[0].name, "before");
            assert_eq!(context.scopes().diagnostics()[0].message, "baseline");
            let mut expected = context.failure().expected;
            expected.sort();
            let expected = expected
                .iter()
                .map(|value| json_string(value))
                .collect::<Vec<_>>()
                .join(",");
            println!(concat!("{{\"accepted\":{},\"consumed\":{},\"matched\":{},\"tokens\":[],\"captures\":[],",
                "\"assertion\":{},\"farthest\":{},\"expected\":[{}],\"state\":{},\"scopeDepth\":{},\"declarations\":{},\"references\":{},\"semanticDiagnostics\":{}}}"),
                result.is_ok(), context.position(), context.matched_position(), assertion, context.failure().offset, expected,
                context.state::<u32>("owned").unwrap(), context.scopes().current_scope_depth(), context.scopes().all_declarations().len(),
                context.scopes().all_references().len(), context.scopes().diagnostics().len());
        }
    }
}

static CALLS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
fn retry_assertion(context: &mut ParseContext<'_>) -> ParseResult {
    CALLS.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    context.parse(&child("optional").ahead())
}
fn retry(input: &str) {
    use unlaxer_runtime::{parse_detailed_with_options, Rule};
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for policy in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ] {
            CALLS.store(0, std::sync::atomic::Ordering::Relaxed);
            let expression = Expr::literal("😀P:")
                .then(Expr::CustomWith {
                    parser: retry_assertion,
                    reads_diagnostics: false,
                    replayable: true,
                })
                .then(Expr::literal("ab;"));
            let result = parse_detailed_with_options(
                &[Rule {
                    name: "Root",
                    expression,
                }],
                0,
                false,
                input,
                ParseOptions::with_memoization(memo).with_diagnostics(policy),
            );
            let offset = result
                .as_ref()
                .err()
                .map_or(-1, |failure| failure.farthest.offset as i32);
            let mut expected = result
                .as_ref()
                .err()
                .map_or(vec![], |failure| failure.farthest.expected.clone());
            expected.sort();
            println!(
                "{{\"accepted\":{},\"farthest\":{},\"expected\":[{}],\"passes\":{}}}",
                result.is_ok(),
                offset,
                expected
                    .iter()
                    .map(|value| json_string(value))
                    .collect::<Vec<_>>()
                    .join(","),
                CALLS.load(std::sync::atomic::Ordering::Relaxed)
            );
        }
    }
}
