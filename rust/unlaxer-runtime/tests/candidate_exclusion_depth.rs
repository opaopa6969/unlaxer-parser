//! Deep grammars with FIRST-set candidate exclusion (#1027).
//!
//! The structural FIRST walk must not overflow a 2 MiB stack before the parser reports
//! `expression nesting below 1024`, and exclusion on / off / audit must give the same result.
//! A stack overflow aborts the process, so every cell runs in a fresh child process (this test
//! binary re-run with `--exact`). Grammars and trees are leaked: dropping a deep `Expr` is a
//! separate recursion this test does not measure.
use std::cell::RefCell;

use unlaxer_runtime::{
    parse_shared_with_options, set_candidate_exclusion_for_current_thread, share_grammar,
    CandidateExclusion, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult,
    Rule, SharedGrammar,
};

const CELL: &str = "UNLAXER_TEST_EXCLUSION_DEPTH_CELL";
const RESOURCE: &str = "err=expression nesting below 1024";

thread_local! {
    static INNER: RefCell<Option<SharedGrammar>> = const { RefCell::new(None) };
}

fn wrap(shape: &str, child: Expr) -> Expr {
    match shape {
        "optional" | "unreached" => child.optional(),
        "choice" | "nested" => Expr::choice([child, Expr::literal("b")]),
        "sequence" => Expr::sequence([child]),
        "nullable" => Expr::sequence([Expr::literal("x").optional(), child]),
        "repeat" => child.zero_or_more(),
        "trivia" => child.trivia_scope(true),
        "capture" => child.capture("c"),
        "lookahead" => child.ahead(),
        other => panic!("unknown shape {other}"),
    }
}

/// `depth` nested expressions ending in `"a"`.
fn deep(shape: &str, depth: usize) -> Expr {
    let mut expression = Expr::literal("a");
    for _ in 1..depth {
        expression = wrap(shape, expression);
    }
    expression
}

/// Enters the deep grammar 900 expressions below the root, so its table and candidates are
/// analysed with part of the nesting budget already used.
fn enter(context: &mut ParseContext<'_>) -> ParseResult {
    let grammar = INNER.with(|inner| inner.borrow().clone()).unwrap();
    context.parse_shared_grammar(&grammar, 0, false)
}

fn grammar(shape: &str, depth: usize) -> Vec<Rule> {
    match shape {
        // Never parsed: only the FIRST table walks the deep rule.
        "unreached" => vec![
            Rule {
                name: "root",
                expression: Expr::literal("b"),
            },
            Rule {
                name: "deep",
                expression: deep(shape, depth),
            },
        ],
        "nested" => {
            let inner = share_grammar(vec![Rule {
                name: "inner",
                expression: deep(shape, depth),
            }]);
            INNER.with(|cell| *cell.borrow_mut() = Some(inner));
            let mut outer = Expr::Custom(enter);
            for _ in 0..900 {
                outer = outer.optional();
            }
            vec![Rule {
                name: "outer",
                expression: outer,
            }]
        }
        _ => vec![Rule {
            name: "root",
            expression: deep(shape, depth),
        }],
    }
}

const ALL_DIAGNOSTICS: [&str; 3] = ["auto", "deferred", "detailed"];
const ALL_MEMO: [&str; 2] = ["off", "safe"];

fn cells() -> Vec<(String, &'static str)> {
    let mut cells = vec![];
    let mut add =
        |shape: &str, depth: usize, diagnostics: &[&str], memo: &[&str], expected: &'static str| {
            for diagnostics in diagnostics {
                for memo in memo {
                    for exclusion in ["on", "off", "audit"] {
                        cells.push((
                            format!("{shape} {depth} {diagnostics} {memo} {exclusion}"),
                            expected,
                        ));
                    }
                }
            }
        };
    for depth in [1200, 1500, 2000] {
        for shape in [
            "optional",
            "choice",
            "sequence",
            "nullable",
            "repeat",
            "trivia",
            "capture",
            "lookahead",
            "nested",
        ] {
            add(shape, depth, &ALL_DIAGNOSTICS, &ALL_MEMO, RESOURCE);
        }
        add("unreached", depth, &ALL_DIAGNOSTICS, &ALL_MEMO, "err=b");
    }
    // Far past the nesting budget, where the walk also overflowed a release stack. Only the
    // deferred pass without memo: the `Auto` and memo-safety analyses are other recursions.
    for (shape, expected) in [
        ("optional", RESOURCE),
        ("choice", RESOURCE),
        ("unreached", "err=b"),
    ] {
        add(shape, 10_000, &["deferred"], &["off"], expected);
    }
    // At the boundary, exclusion must not skip the `"x"` the parser would reach at 1024.
    add("nullable", 1023, &ALL_DIAGNOSTICS, &ALL_MEMO, "ok");
    add("nullable", 1024, &ALL_DIAGNOSTICS, &ALL_MEMO, RESOURCE);
    cells
}

#[test]
fn deep_grammars_reach_the_resource_guard_in_fresh_processes() {
    let mut observed = 0;
    for (cell, expected) in cells() {
        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "candidate_exclusion_depth_child",
                "--nocapture",
                "--test-threads=1",
            ])
            .env(CELL, &cell)
            .env_remove("UNLAXER_CANDIDATE_EXCLUSION")
            .output()
            .unwrap();
        let stdout = String::from_utf8_lossy(&output.stdout);
        assert!(
            output.status.success(),
            "{cell}: {:?}\n{stdout}{}",
            output.status,
            String::from_utf8_lossy(&output.stderr)
        );
        assert!(
            // `--nocapture` prints the line after the harness's `test ... ` prefix.
            stdout.contains(&format!("CELL {expected}\n")),
            "{cell}: expected {expected}\n{stdout}"
        );
        observed += 1;
    }
    // (3 depths x 10 shapes + 2 boundary) x 3 diagnostics x 2 memo x 3 modes, plus 3 x 3.
    assert_eq!(observed, 32 * 18 + 9);
}

#[test]
fn candidate_exclusion_depth_child() {
    let Ok(cell) = std::env::var(CELL) else {
        return;
    };
    let fields: Vec<String> = cell.split(' ').map(str::to_owned).collect();
    let [shape, depth, diagnostics, memo, exclusion] = &fields[..] else {
        panic!("{cell}");
    };
    let (shape, depth) = (shape.clone(), depth.parse::<usize>().unwrap());
    let options = ParseOptions {
        diagnostics: match diagnostics.as_str() {
            "auto" => Diagnostics::Auto,
            "deferred" => Diagnostics::DetailedOnFailure,
            "detailed" => Diagnostics::Detailed,
            other => panic!("{other}"),
        },
        memoization: match memo.as_str() {
            "off" => Memoization::Off,
            "safe" => Memoization::SafeFailures,
            other => panic!("{other}"),
        },
        ..ParseOptions::default()
    };
    let exclusion = match exclusion.as_str() {
        "on" => CandidateExclusion::On,
        "off" => CandidateExclusion::Off,
        "audit" => CandidateExclusion::Audit,
        other => panic!("{other}"),
    };
    let line = std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(move || {
            set_candidate_exclusion_for_current_thread(Some(exclusion));
            let grammar = share_grammar(grammar(&shape, depth));
            let line = match parse_shared_with_options(&grammar, 0, false, "a", options) {
                Ok(tree) => {
                    std::mem::forget(tree);
                    "CELL ok".to_owned()
                }
                Err(error) => format!("CELL err={}", error.expected.join("|")),
            };
            std::mem::forget(grammar);
            INNER.with(|inner| std::mem::forget(inner.borrow_mut().take()));
            line
        })
        .unwrap()
        .join()
        .unwrap();
    println!("{line}");
}
