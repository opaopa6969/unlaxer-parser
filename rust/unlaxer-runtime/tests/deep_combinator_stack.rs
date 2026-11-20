//! Deep combinators on a 2 MiB stack, with or without FIRST-set candidate exclusion (#1028).
//!
//! LongestChoice (also in Classic mode) and, in debug, Repeat used to overflow before
//! `expression nesting below 1024`, and the `Auto` and memo-safety analyses overflowed a debug
//! stack on grammars far deeper than that. Nesting within the limit must still parse normally. A stack overflow aborts the
//! process, so every cell runs in a fresh child process (this test binary re-run with
//! `--exact`). Grammars and trees are leaked: dropping a deep `Expr` is a separate recursion
//! this test does not measure.
use unlaxer_runtime::{
    grammar_allows_deferred_diagnostics, parse_shared_with_options,
    set_candidate_exclusion_for_current_thread, share_grammar, CandidateExclusion, Diagnostics,
    Expr, Memoization, ParseContext, ParseOptions, ParseResult, Rule,
};

const CELL: &str = "UNLAXER_TEST_DEEP_COMBINATOR_CELL";
const RESOURCE: &str = "err=expression nesting below 1024";
const NO_PROGRESS: &str = "err=a|progress in unbounded repetition";

fn wrap(shape: &str, child: Expr) -> Expr {
    match shape {
        "longest" | "classic" => Expr::longest_choice([child, Expr::literal("b")]),
        "choice" | "unreached" => Expr::choice([child, Expr::literal("b")]),
        "sequence" => Expr::sequence([child]),
        "nullable" => Expr::sequence([Expr::literal("x").optional(), child]),
        "repeat" => child.zero_or_more(),
        other => panic!("unknown shape {other}"),
    }
}

/// `depth` nested expressions ending in `leaf`.
fn deep(shape: &str, depth: usize, leaf: Expr) -> Expr {
    let mut expression = leaf;
    for _ in 1..depth {
        expression = wrap(shape, expression);
    }
    expression
}

fn custom(_context: &mut ParseContext<'_>) -> ParseResult {
    unreachable!("the analysis never runs a custom parser")
}

fn grammar(shape: &str, depth: usize) -> Vec<Rule> {
    let leaf = match shape {
        // A match-only leaf selects Classic mode; the leaf adds two nested expressions.
        "classic" => Expr::sequence([Expr::literal("a").match_only(), Expr::literal("a")]),
        _ => Expr::literal("a"),
    };
    let root = Rule {
        name: "root",
        expression: deep(shape, depth, leaf),
    };
    match shape {
        // Never parsed: only the preparation analyses walk the deep rule.
        "unreached" => vec![
            Rule {
                name: "root",
                expression: Expr::literal("b"),
            },
            Rule {
                name: "deep",
                ..root
            },
        ],
        _ => vec![root],
    }
}

const ALL_DIAGNOSTICS: [&str; 3] = ["auto", "deferred", "detailed"];

fn cells() -> Vec<(String, &'static str)> {
    let mut cells = vec![];
    let mut add = |shape: &str, depth: usize, expected: &'static str| {
        for diagnostics in ALL_DIAGNOSTICS {
            for memo in ["off", "safe"] {
                for exclusion in ["on", "off"] {
                    cells.push((
                        format!("{shape} {depth} {diagnostics} {memo} {exclusion}"),
                        expected,
                    ));
                }
            }
        }
    };
    // 1023 and 1024 nested expressions are within the limit and parse as before.
    for depth in [1023, 1024] {
        add("longest", depth, "ok");
        add("repeat", depth, NO_PROGRESS);
    }
    for depth in [1200, 1500, 2000] {
        add("longest", depth, RESOURCE);
    }
    // Classic LongestChoice at 1024 and 1025 active expressions.
    add("classic", 1022, "ok");
    add("classic", 1023, RESOURCE);
    // Far past the limit, where the preparation analyses overflowed a debug stack.
    for shape in ["longest", "choice", "sequence", "nullable"] {
        add(shape, 10_000, RESOURCE);
    }
    add("unreached", 10_000, "err=b");
    // The analysis itself stays exact below its native recursion budget.
    cells.push(("allows plain 10000".to_owned(), "true"));
    cells.push(("allows custom 10000".to_owned(), "false"));
    cells
}

#[test]
fn deep_combinators_parse_or_reach_the_resource_guard_in_fresh_processes() {
    let mut observed = 0;
    for (cell, expected) in cells() {
        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "deep_combinator_child",
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
    // (2 x 2 + 3 + 2 + 5) shapes and depths x 3 diagnostics x 2 memo x 2 exclusion, plus 2.
    assert_eq!(observed, 14 * 12 + 2);
}

#[test]
fn deep_combinator_child() {
    let Ok(cell) = std::env::var(CELL) else {
        return;
    };
    let fields: Vec<String> = cell.split(' ').map(str::to_owned).collect();
    if let [allows, leaf, depth] = &fields[..] {
        assert_eq!(allows, "allows");
        let leaf = match leaf.as_str() {
            "plain" => Expr::literal("a"),
            "custom" => Expr::Custom(custom),
            other => panic!("{other}"),
        };
        let depth = depth.parse::<usize>().unwrap();
        let line = std::thread::Builder::new()
            .stack_size(2 * 1024 * 1024)
            .spawn(move || {
                let rules = vec![Rule {
                    name: "root",
                    expression: deep("choice", depth, leaf),
                }];
                let allowed = grammar_allows_deferred_diagnostics(&rules);
                std::mem::forget(rules);
                format!("CELL {allowed}")
            })
            .unwrap()
            .join()
            .unwrap();
        println!("{line}");
        return;
    }
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
            line
        })
        .unwrap()
        .join()
        .unwrap();
    println!("{line}");
}
