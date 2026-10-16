use unlaxer_runtime::regex::{CompileErrorKind, CompileLimits, MatchError, MatchLimits, Regex};
fn decode(hex: &str) -> String {
    if hex == "~" {
        return String::new();
    }
    String::from_utf8(
        (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect(),
    )
    .unwrap()
}
#[test]
fn handwritten_prefix_and_coordinates_match_the_shared_core_contract() {
    for row in include_str!("../../../spec-corpus/regex-core/cases.tsv")
        .lines()
        .filter(|r| !r.starts_with('#'))
    {
        let f: Vec<_> = row.split('\t').collect();
        let pattern = decode(f[1]);
        let input = decode(f[2]);
        let regex = Regex::compile(&pattern).unwrap_or_else(|e| panic!("{} {pattern}: {e}", f[0]));
        let matched = regex
            .looking_at(&input)
            .unwrap_or_else(|e| panic!("{}: {e}", f[0]));
        if f[3] == "-" {
            assert_eq!(None, matched, "{} {pattern}", f[0]);
        } else {
            let found = matched.unwrap_or_else(|| panic!("{} {pattern}: missing prefix", f[0]));
            assert_eq!(decode(f[3]), input[..found.end_utf8], "{} {pattern}", f[0]);
            assert_eq!(
                (
                    f[4].parse().unwrap(),
                    f[5].parse().unwrap(),
                    f[6].parse().unwrap()
                ),
                (found.end_cp, found.end_utf16, found.end_utf8),
                "{}",
                f[0]
            );
        }
        for (invert, index) in [(false, 7), (true, 8)] {
            assert_eq!(
                f[index].parse::<usize>().unwrap(),
                regex
                    .token_prefix(&input, invert)
                    .unwrap()
                    .map_or(0, |m| m.end_cp),
                "{} invert={invert}",
                f[0]
            );
        }
    }
}
#[test]
fn unsupported_patterns_are_never_interpreted_as_another_dialect() {
    for row in include_str!("../../../spec-corpus/regex-core/invalid.tsv")
        .lines()
        .filter(|r| !r.starts_with('#'))
    {
        let f: Vec<_> = row.split('\t').collect();
        let pattern = decode(f[1]);
        let err = Regex::compile(&pattern).unwrap_err();
        assert_eq!(f[2], format!("{:?}", err.kind), "{} {pattern}: {err}", f[0]);
        assert!(err.offset <= pattern.chars().count());
    }
}
#[test]
fn hostile_inputs_fail_with_a_reusable_bounded_matcher() {
    let regex = Regex::compile("(a+)+$").unwrap();
    let limits = MatchLimits {
        max_steps: 1_000,
        ..MatchLimits::default()
    };
    assert_eq!(
        Err(MatchError::StepLimit),
        regex.looking_at_with_limits(&format!("{}!", "a".repeat(32)), limits)
    );
    assert_eq!(Some(3), regex.looking_at("aaa").unwrap().map(|m| m.end_cp));
    assert_eq!(
        Err(MatchError::BacktrackingLimit),
        Regex::compile("a|b").unwrap().looking_at_with_limits(
            "a",
            MatchLimits {
                max_backtracking_states: 0,
                ..MatchLimits::default()
            }
        )
    );
    assert_eq!(
        Err(MatchError::InputLimit),
        regex.looking_at_with_limits(
            "aaaa",
            MatchLimits {
                max_input_codepoints: 3,
                ..MatchLimits::default()
            }
        )
    );
    assert_eq!(
        Err(MatchError::StepLimit),
        Regex::compile("(){2147483647}")
            .unwrap()
            .looking_at_with_limits("", limits)
    );
    assert_eq!(
        Err(MatchError::StepLimit),
        regex.token_prefix_with_limits(
            "aaa!",
            false,
            MatchLimits {
                max_steps: 1,
                ..MatchLimits::default()
            }
        )
    );
    assert_eq!(
        Ok(None),
        regex.token_prefix_with_limits(
            "",
            false,
            MatchLimits {
                max_steps: 0,
                ..MatchLimits::default()
            }
        )
    );
    assert_eq!(
        Some(0),
        Regex::compile("(?:a?)*")
            .unwrap()
            .looking_at("")
            .unwrap()
            .map(|m| m.end_cp)
    );
}
#[test]
fn compile_limits_precede_host_stack_or_unbounded_allocation() {
    let cases = [
        (
            "abc",
            CompileLimits {
                max_pattern_codepoints: 2,
                ..CompileLimits::default()
            },
        ),
        (
            "abc",
            CompileLimits {
                max_nodes: 2,
                ..CompileLimits::default()
            },
        ),
        (
            "abc",
            CompileLimits {
                max_instructions: 2,
                ..CompileLimits::default()
            },
        ),
    ];
    assert_eq!(
        CompileErrorKind::ResourceLimit,
        Regex::compile_with_limits(
            "",
            CompileLimits {
                max_instructions: 0,
                ..CompileLimits::default()
            }
        )
        .unwrap_err()
        .kind
    );
    assert_eq!(
        CompileErrorKind::ResourceLimit,
        Regex::compile_with_limits(
            "[acex]",
            CompileLimits {
                max_set_work: 2,
                ..CompileLimits::default()
            }
        )
        .unwrap_err()
        .kind
    );
    for (pattern, limits) in cases {
        assert_eq!(
            CompileErrorKind::ResourceLimit,
            Regex::compile_with_limits(pattern, limits)
                .unwrap_err()
                .kind
        );
    }
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(|| {
            let deep = format!("{}a{}", "(".repeat(5_000), ")".repeat(5_000));
            assert_eq!(
                CompileErrorKind::ResourceLimit,
                Regex::compile(&deep).unwrap_err().kind
            );
            assert_eq!(
                CompileErrorKind::ResourceLimit,
                Regex::compile_with_limits(
                    &deep,
                    CompileLimits {
                        max_depth: usize::MAX,
                        ..CompileLimits::default()
                    }
                )
                .unwrap_err()
                .kind
            );
            let deep_class = format!("{}a{}", "[".repeat(5_000), "]".repeat(5_000));
            assert_eq!(
                CompileErrorKind::ResourceLimit,
                Regex::compile(&deep_class).unwrap_err().kind
            );
            let expensive_class = format!(
                "[{}]",
                (0..4_000)
                    .map(|i| char::from_u32(0x1000 + i * 2).unwrap())
                    .collect::<String>()
            );
            assert_eq!(
                CompileErrorKind::ResourceLimit,
                Regex::compile(&expensive_class).unwrap_err().kind
            );
            let nested_nullable = format!("{}a{}", "(?:".repeat(64), ")*".repeat(64));
            assert_eq!(
                1,
                Regex::compile(&nested_nullable)
                    .unwrap()
                    .looking_at("a")
                    .unwrap()
                    .unwrap()
                    .end_cp
            );
            let pattern = "a".repeat(10_000);
            let regex = Regex::compile_with_limits(
                &pattern,
                CompileLimits {
                    max_nodes: 20_000,
                    ..CompileLimits::default()
                },
            )
            .unwrap();
            assert_eq!(10_000, regex.looking_at(&pattern).unwrap().unwrap().end_cp);
            let alternatives = vec!["a"; 5_000].join("|");
            assert!(Regex::compile(&alternatives)
                .unwrap()
                .looking_at("b")
                .unwrap()
                .is_none());
        })
        .unwrap()
        .join()
        .unwrap();
}
#[test]
fn compiled_patterns_are_send_sync_and_execution_state_is_not_shared() {
    fn check<T: Send + Sync>() {}
    check::<Regex>();
    let regex = std::sync::Arc::new(Regex::compile("(?i)[a-z]+|😀+").unwrap());
    let threads: Vec<_> = (0..8)
        .map(|_| {
            let regex = regex.clone();
            std::thread::spawn(move || {
                for _ in 0..32 {
                    assert_eq!(3, regex.looking_at("AbC!").unwrap().unwrap().end_cp);
                    assert_eq!(2, regex.looking_at("😀😀!").unwrap().unwrap().end_cp);
                    assert!(regex.looking_at("1").unwrap().is_none());
                }
            })
        })
        .collect();
    for thread in threads {
        thread.join().unwrap();
    }
}
