//! Generated AST positions are owned values, independent of later mapping operations.
use std::sync::{Arc, Barrier};
use std::thread;
use unlaxer_evolution_example::{
    generated::{ast::Ast, evaluator::evaluate, mapper, parser},
    semantics::Calculator,
};
use unlaxer_runtime::{Expr, ParseContext, Span};

fn assert_twos(ast: &Ast, start: usize) {
    let Ast::Binary {
        left, op, right, ..
    } = ast
    else {
        panic!("expected binary AST")
    };
    assert_eq!(op, "+");
    let (Ast::Number { value: a, .. }, Ast::Number { value: b, .. }) =
        (left.as_ref(), right.as_ref())
    else {
        panic!("expected number operands")
    };
    // Equal semantic values still have distinct node identities and positions.
    assert_eq!((a.as_str(), b.as_str()), ("2", "2"));
    assert!(!std::ptr::eq(left.as_ref(), right.as_ref()));
    assert_eq!(
        left.span(),
        Span {
            start,
            end: start + 1
        }
    );
    assert_eq!(
        right.span(),
        Span {
            start: start + 2,
            end: start + 3
        }
    );
    assert_eq!(
        ast.span(),
        Span {
            start,
            end: start + 3
        }
    );
    assert_eq!(evaluate(ast, &mut Calculator), Ok(4.0));
}

#[test]
fn repeated_mapping_in_one_context_keeps_both_owned_snapshots() {
    let (first, first_remapped, second) = {
        let mut source = String::from("😀:2+2;7");
        let mut context = ParseContext::new(&source);
        context.parse(&Expr::literal("😀:")).unwrap();
        let matched = context.parse(&parser::GeneratedParser).unwrap();
        let first_tree = context.tree(matched.root_node().unwrap()).unwrap();
        let first = mapper::map(&first_tree).unwrap();

        context.parse(&Expr::literal(";")).unwrap();
        let matched = context.parse(&parser::GeneratedParser).unwrap();
        let second_tree = context.tree(matched.root_node().unwrap()).unwrap();
        let second = mapper::map(&second_tree).unwrap();
        context.parse(&Expr::Eof).unwrap();
        let first_remapped = mapper::map(&first_tree).unwrap();

        drop(context);
        drop(first_tree);
        drop(second_tree);
        source.clear();
        source.push_str("the caller has replaced the entire original source");
        assert_eq!(first, first_remapped);
        (first, first_remapped, second)
    };

    assert_twos(&first, 2); // Unicode scalar offsets, not the UTF-8 byte offset 5.
    assert_twos(&first_remapped, 2);
    assert_eq!(second.span(), Span { start: 6, end: 7 });
    assert_eq!(evaluate(&second, &mut Calculator), Ok(7.0));
    let mut copied_span = first.span();
    copied_span.start = 999;
    assert_ne!(copied_span, first.span());
    assert_twos(&first, 2);
}

#[test]
fn later_successful_and_failed_operations_cannot_replace_retained_positions() {
    let mut source = String::from("2+2");
    let mut tree = parser::parse_tree(&source).unwrap();
    source.clear();
    source.push_str("😀 changed before mapping");
    let retained = mapper::map(&tree).unwrap();
    let before = retained.canonical_json();

    for input in ["9", "neg(2)", "if(1,3*4,5)"] {
        let later_tree = parser::parse_tree(input).unwrap();
        let later = mapper::map(&later_tree).unwrap();
        assert_eq!(later.span().start, 0);
        assert_eq!(later.span().end, input.chars().count());
        assert_eq!(retained.canonical_json(), before);
    }
    for input in ["", "neg(", "2+", "😀"] {
        assert!(parser::parse_tree(input).is_err());
        assert_eq!(retained.canonical_json(), before);
    }
    // Deliberately remove the root's semantic children to exercise mapper failure.
    // This mutates only this test's owned CST, never the previously mapped AST.
    tree.nodes[tree.root].children.clear();
    assert!(mapper::map(&tree).is_err());
    drop(tree);
    drop(source);
    assert_eq!(retained.canonical_json(), before);
    assert_twos(&retained, 0);
}

#[test]
fn concurrent_mapping_and_parsing_return_independent_sendable_asts() {
    fn require_send_sync_static<T: Send + Sync + 'static>() {}
    require_send_sync_static::<Ast>();

    const WORKERS: usize = 8;
    const ROUNDS: usize = 16;
    let shared = Arc::new(parser::parse_tree("2+2").unwrap());
    let retained = mapper::map(&shared).unwrap();
    let before = retained.canonical_json();
    let start = Arc::new(Barrier::new(WORKERS));
    let handles: Vec<_> = (1..=WORKERS)
        .map(|worker| {
            let shared = Arc::clone(&shared);
            let start = Arc::clone(&start);
            thread::spawn(move || {
                start.wait();
                let mut snapshots = Vec::new();
                for _ in 0..ROUNDS {
                    let mut source = format!("{}:2+2", "😀".repeat(worker));
                    let own_tree = {
                        let mut context = ParseContext::new(&source);
                        context
                            .parse(&Expr::Any.repeat(worker, Some(worker)))
                            .unwrap();
                        context.parse(&Expr::literal(":")).unwrap();
                        let matched = context.parse(&parser::GeneratedParser).unwrap();
                        context.tree(matched.root_node().unwrap()).unwrap()
                    };
                    source.clear();
                    let own = mapper::map(&own_tree).unwrap();
                    drop(own_tree);
                    let same_source = mapper::map(&shared).unwrap();
                    assert!(parser::parse_tree("if(").is_err());
                    assert_twos(&own, worker + 1);
                    assert_twos(&same_source, 0);
                    snapshots.push((own, same_source));
                }
                snapshots
            })
        })
        .collect();
    drop(shared);

    let snapshots: Vec<_> = handles
        .into_iter()
        .map(|handle| handle.join().unwrap())
        .collect();
    // All worker contexts, original strings, and the shared CST have been dropped.
    for (worker, snapshots) in snapshots.iter().enumerate() {
        assert_eq!(snapshots.len(), ROUNDS);
        for (own, same_source) in snapshots {
            assert_twos(own, worker + 2);
            assert_twos(same_source, 0);
        }
    }
    assert_eq!(retained.canonical_json(), before);
    assert_twos(&retained, 0);
}
