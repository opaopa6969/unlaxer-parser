#![allow(dead_code)]
mod boundary;
mod childa;
mod childb;
mod parent;
use std::collections::HashMap;
use std::sync::OnceLock;
use unlaxer_runtime::shared_calls::Registry;
use unlaxer_runtime::source::Language;
use unlaxer_runtime::{Expr, Memoization, ParseContext, ParseOptions, ParseResult};
fn language(grammar: &str, version: &str) -> Language {
    let id = if grammar == "ChildA" { "a" } else { "b" };
    Language {
        id: id.into(),
        package_id: format!("example/{id}"),
        version: version.into(),
        grammar: grammar.into(),
        entry: "Root".into(),
    }
}
fn registry() -> &'static Registry {
    static VALUE: OnceLock<Registry> = OnceLock::new();
    VALUE.get_or_init(|| {
        Registry::new(HashMap::from([
            (language("ChildA", "1"), childa::parser::embedded_grammar()),
            (language("ChildB", "1"), childb::parser::embedded_grammar()),
        ]))
        .unwrap()
    })
}
fn state_token(context: &mut ParseContext<'_>) -> ParseResult {
    assert!(context.state::<String>("sentinel").is_none());
    assert!(!context.scopes().is_declared("parent-name"));
    context.set_state("sentinel", "child".to_owned());
    let position = context.position();
    context.scopes_mut().declare("inside", position);
    context.parse(&Expr::Literal("a").capture("private"))
}
fn call_a(context: &mut ParseContext<'_>) -> ParseResult {
    registry().parse(context, &language("ChildA", "1"))
}
fn call_b(context: &mut ParseContext<'_>) -> ParseResult {
    registry().parse(context, &language("ChildB", "1"))
}
fn call_missing(context: &mut ParseContext<'_>) -> ParseResult {
    registry().parse(context, &language("ChildA", "2"))
}
fn ahead_a(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse(&Expr::Custom(call_a).ahead())
}
fn not_a(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse(&Expr::Custom(call_a).not_ahead())
}
fn main() {
    controls(Memoization::Off);
    controls(Memoization::SafeFailures);
    for row in std::fs::read_to_string(std::env::args().nth(1).unwrap())
        .unwrap()
        .lines()
    {
        let f: Vec<_> = row.split('\t').collect();
        let bytes: Vec<_> = f[1]
            .as_bytes()
            .chunks_exact(2)
            .map(|c| u8::from_str_radix(std::str::from_utf8(c).unwrap(), 16).unwrap())
            .collect();
        let source = String::from_utf8(bytes).unwrap();
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            let mut context =
                ParseContext::with_options(&source, ParseOptions::with_memoization(memo));
            context.set_state("sentinel", "parent".to_owned());
            context.scopes_mut().declare("parent-name", 0);
            let parsed = parent::parser::parse_context(&mut context);
            assert_eq!(parsed.is_ok(), f[2] == "true", "{}", f[0]);
            assert_eq!(
                context.position(),
                f[3].parse::<usize>().unwrap(),
                "{}",
                f[0]
            );
            assert_eq!(context.state::<String>("sentinel").unwrap(), "parent");
            assert!(context.capture_spans("private").is_empty());
            assert!(context.scopes().is_declared("parent-name"));
            assert!(!context.scopes().is_declared("inside"));
            if let Ok(parsed) = parsed {
                let tree = context.tree(parsed.root_node().unwrap()).unwrap();
                let calls: Vec<_> = tree
                    .nodes
                    .iter()
                    .enumerate()
                    .filter_map(|(i, _)| tree.shared_call(i))
                    .collect();
                assert_eq!(calls.len(), usize::from(f[5] != "-"), "{}", f[0]);
                if let Some(call) = calls.first() {
                    assert_eq!(call.language.grammar, f[5]);
                    assert_eq!(call.span.start, f[6].parse().unwrap());
                    assert_eq!(call.span.end, f[7].parse().unwrap());
                    assert_eq!(call.tree.nodes[call.tree.root].rule, 0);
                    let span = if f[5] == "ChildA" {
                        childa::mapper::map(&call.tree).unwrap().span()
                    } else {
                        childb::mapper::map(&call.tree).unwrap().span()
                    };
                    assert_eq!(
                        span,
                        unlaxer_runtime::Span {
                            start: f[6].parse().unwrap(),
                            end: f[9].parse().unwrap()
                        },
                        "{}",
                        f[0]
                    );
                    let ast = if f[5] == "ChildA" {
                        childa::mapper::map(&call.tree).unwrap().canonical_json()
                    } else {
                        childb::mapper::map(&call.tree).unwrap().canonical_json()
                    };
                    assert!(ast.contains(if f[5] == "ChildA" { "AValue" } else { "BValue" }));
                    assert!(ast.contains("\"value\":\"a\""));
                }
                let ast = parent::mapper::map(&tree).unwrap().canonical_json();
                assert!(
                    ast.contains(&format!("\"value\":{}", unlaxer_runtime::json_string(f[8]))),
                    "{} {ast}",
                    f[0]
                );
                assert!(!ast.contains("AValue") && !ast.contains("BValue"));
            } else {
                assert_eq!(
                    context.failure().offset,
                    f[4].parse::<usize>().unwrap(),
                    "{}",
                    f[0]
                );
            }
        }
        println!("{}\tPASS", f[0]);
    }
}
fn boundary_language(entry: &str) -> Language {
    Language {
        id: "boundary".into(),
        package_id: "example/boundary".into(),
        version: "1".into(),
        grammar: "Boundary".into(),
        entry: entry.into(),
    }
}
fn boundary_registry(entry: &str) -> Registry {
    Registry::new(HashMap::from([(
        boundary_language(entry),
        boundary::parser::embedded_grammar(),
    )]))
    .unwrap()
}
fn recur_call(context: &mut ParseContext<'_>) -> ParseResult {
    boundary_registry("Recur").parse(context, &boundary_language("Recur"))
}
fn controls(memo: Memoization) {
    use std::collections::{BTreeMap, HashSet};
    use unlaxer_runtime::language_queries::*;
    use unlaxer_runtime::source::{Location, Operation, Snapshot, State};
    use unlaxer_runtime::{Diagnostics, Span};
    let show = |name: &str, f: unlaxer_runtime::shared_calls::Failure| {
        println!(
            "{name}\t{}\t{}",
            format!("{:?}", f.kind).to_uppercase(),
            f.error.offset
        )
    };
    let mut context = ParseContext::with_options("😀D:aX;", ParseOptions::with_memoization(memo));
    context.advance(3);
    show(
        "direct-failure",
        registry()
            .call(&mut context, &language("ChildA", "1"))
            .unwrap_err(),
    );
    assert_eq!(context.position(), 3);
    let mut context = ParseContext::with_options("😀D:aX;", ParseOptions::with_memoization(memo));
    assert!(context
        .parse(&Expr::literal("😀D:aX;").then(Expr::literal("z")))
        .is_err());
    context.advance(3);
    let failure = registry()
        .call(&mut context, &language("ChildA", "1"))
        .unwrap_err();
    println!(
        "local-failure\t{}\t{}",
        failure.error.offset,
        context.failure().offset
    );
    let oversized = format!("ab{}", "x".repeat(1_048_575));
    let mut context = ParseContext::with_options(&oversized, ParseOptions::with_memoization(memo));
    show(
        "input-limit",
        registry()
            .call(&mut context, &language("ChildA", "1"))
            .unwrap_err(),
    );
    let literal = Expr::literal("ab").not_ahead();
    let optional = Expr::literal("a")
        .then(Expr::literal("b"))
        .then(Expr::literal("!").optional_java())
        .not_ahead();
    let successful = Expr::literal("a")
        .then(Expr::literal("X"))
        .not_ahead()
        .then(Expr::literal("Z"));
    for (i, assertion) in [literal, optional, successful].into_iter().enumerate() {
        let mut context =
            ParseContext::with_options("😀N:ab;", ParseOptions::with_memoization(memo));
        assert!(context
            .parse(&Expr::literal("😀N:").then(assertion))
            .is_err());
        assert_eq!(context.position(), 0);
        println!("not-policy-{i}\t{}", context.failure().offset);
    }
    for (name, source, entry) in [
        ("nullable", "x", "Empty"),
        ("empty-host", "", "Empty"),
        ("recovered", "bad;", "Recover"),
    ] {
        let mut context = ParseContext::with_options(source, ParseOptions::with_memoization(memo));
        show(
            name,
            boundary_registry(entry)
                .call(&mut context, &boundary_language(entry))
                .unwrap_err(),
        );
        assert_eq!(context.position(), 0);
    }
    assert!(Registry::new(HashMap::from([(
        boundary_language("Missing"),
        boundary::parser::embedded_grammar()
    )]))
    .is_err());
    println!("missing-entry\tREJECTED");
    let mut context = ParseContext::with_options(
        "ab",
        ParseOptions::with_memoization(memo).with_diagnostics(Diagnostics::DetailedOnFailure),
    );
    show(
        "deferred",
        registry()
            .call(&mut context, &language("ChildA", "1"))
            .unwrap_err(),
    );
    let large = format!("abababababab{}", "x".repeat(1_048_576 - 12));
    {
        let mut context = ParseContext::with_options(&large, ParseOptions::with_memoization(memo));
        for _ in 0..4 {
            call_a(&mut context).unwrap();
        }
        show(
            "retention",
            registry()
                .call(&mut context, &language("ChildA", "1"))
                .unwrap_err(),
        );
    }
    {
        let mut context = ParseContext::with_options(&large, ParseOptions::with_memoization(memo));
        let failed: Result<(), unlaxer_runtime::ParseError> = context.transaction(|context| {
            for _ in 0..4 {
                call_a(context)?;
            }
            Err(context.error("rollback"))
        });
        assert!(failed.is_err());
        ahead_a(&mut context).unwrap();
        for _ in 0..4 {
            call_a(&mut context).unwrap();
        }
        println!("budget-rollback\t{}", context.position());
    }
    {
        let mut context = ParseContext::with_options(&large, ParseOptions::with_memoization(memo));
        let registry = boundary_registry("Outer");
        let language = boundary_language("Outer");
        for _ in 0..2 {
            registry.call(&mut context, &language).unwrap();
        }
        show(
            "nested-retention",
            registry.call(&mut context, &language).unwrap_err(),
        );
    }
    for length in [32, 33] {
        let source = format!("{}z", "a".repeat(length));
        let mut context = ParseContext::with_options(&source, ParseOptions::with_memoization(memo));
        match boundary_registry("Recur").call(&mut context, &boundary_language("Recur")) {
            Ok(value) => println!("depth-{length}\tCOMPLETE\t{}", value.span.end),
            Err(failure) => show(&format!("depth-{length}"), failure),
        }
    }
    let retained = {
        let mut context = ParseContext::with_options("ab", ParseOptions::with_memoization(memo));
        let parsed = call_a(&mut context).unwrap();
        context.tree(parsed.root_node().unwrap()).unwrap()
    };
    let call = retained.shared_call(retained.root).unwrap();
    let ast = childa::mapper::map(&call.tree).unwrap().canonical_json();
    assert!(ast.contains("AValue") && ast.contains("\"value\":\"a\""));
    println!("retained-tree\tAValue\ta");
    let host = Snapshot::new("file:///shared", 7, "😀D:ab;").unwrap();
    let parent_language = Language {
        id: "parent".into(),
        package_id: "example/parent".into(),
        version: "1".into(),
        grammar: "Parent".into(),
        entry: "Root".into(),
    };
    let parent_grammar = parent::parser::embedded_grammar();
    let child_grammar = childa::parser::embedded_grammar();
    let providers: HashMap<Language, &dyn unlaxer_runtime::embedded::Grammar> = HashMap::from([
        (
            parent_language.clone(),
            &parent_grammar as &dyn unlaxer_runtime::embedded::Grammar,
        ),
        (
            language("ChildA", "1"),
            &child_grammar as &dyn unlaxer_runtime::embedded::Grammar,
        ),
    ]);
    let regions = unlaxer_runtime::embedded::parse(&host, &parent_language, &providers, 8, 32)
        .unwrap()
        .tree()
        .unwrap();
    assert_eq!(regions.regions().len(), 2);
    let child = regions
        .regions()
        .into_iter()
        .find(|r| r.language == language("ChildA", "1"))
        .unwrap();
    assert_eq!(child.body, Span { start: 3, end: 5 });
    let project = Project {
        id: "p".into(),
        version: 1,
        documents: BTreeMap::from([(host.uri.clone(), host.clone())]),
        configuration: BTreeMap::new(),
    };
    struct Owned;
    impl Provider for Owned {
        fn capabilities(&self) -> HashSet<Operation> {
            HashSet::from([Operation::Completion])
        }
        fn query(&self, r: &Request<'_>) -> unlaxer_runtime::source::Result<Response> {
            assert_eq!(r.cursor, 1);
            Ok(Response {
                snapshot: r.region.source_map.output().clone(),
                project: "p".into(),
                project_version: 1,
                state: State::Complete,
                items: vec![Item {
                    label: "a".into(),
                    detail: "owned".into(),
                    locations: vec![Location {
                        snapshot: r.region.source_map.output().clone(),
                        span: Span { start: 0, end: 1 },
                    }],
                    edits: vec![],
                }],
            })
        }
    }
    let queries = LanguageQueries::new(
        regions,
        project.clone(),
        HashMap::from([(
            language("ChildA", "1"),
            Box::new(Owned) as Box<dyn Provider>,
        )]),
    )
    .unwrap();
    let response = queries
        .query(&host, &project, 4, Operation::Completion, &BTreeMap::new())
        .unwrap();
    assert_eq!(
        response.items[0].locations[0].location.span,
        Span { start: 3, end: 4 }
    );
    println!("query\tCOMPLETE\t3\t4");
    let stale = Snapshot::new(&host.uri, 8, &host.text).unwrap();
    assert!(queries
        .query(&stale, &project, 4, Operation::Completion, &BTreeMap::new())
        .is_err());
    println!("stale\tREJECTED");
}
