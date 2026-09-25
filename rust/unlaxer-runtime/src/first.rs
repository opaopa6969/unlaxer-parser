//! FIRST sets derived from the combinator graph (#300, the Rust counterpart of Java #292 / case 37).
//!
//! For every rule the runtime computes, once per grammar, which code points a successful match
//! that consumes input can start with, whether the rule can succeed without consuming input
//! (`nullable`), and whether its start cannot be modelled at all (`unknown`). The table is the
//! least fixed point over the rule graph; inline expressions are evaluated structurally against
//! that table when a candidate is about to be tried.
//!
//! Sequence trivia is skipped by the runtime (`ParseContext::skip`), not by a grammar element,
//! so a sequence contributes a trivia marker instead of code points. `TriviaScope` resolves the
//! marker, and otherwise the caller's current trivia policy decides it at the check site.
//!
//! The sets are only used to skip candidates whose evaluation is certain to fail. Anything that
//! is not modelled (custom parsers, backreferences, `Until` / `JavaUntil`, and lookaheads over
//! such children) is `unknown` and therefore never skipped.
use std::sync::{Arc, Mutex, OnceLock, Weak};

use crate::{Expr, Rule};

/// Whether a sequence at the start of an expression may consume trivia first.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum Trivia {
    /// No trivia can be consumed before the first code point.
    #[default]
    None,
    /// Trivia is consumed when the caller's trivia policy is enabled.
    Dynamic,
    /// Trivia is consumed regardless of the caller's policy (an enclosing `TriviaScope(true)`).
    Always,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct FirstSet {
    ascii: u128,
    non_ascii: bool,
    nullable: bool,
    unknown: bool,
    trivia: Trivia,
}

impl FirstSet {
    const EMPTY: Self = Self {
        ascii: 0,
        non_ascii: false,
        nullable: false,
        unknown: false,
        trivia: Trivia::None,
    };
    const NULLABLE: Self = Self {
        nullable: true,
        ..Self::EMPTY
    };
    const UNKNOWN: Self = Self {
        ascii: u128::MAX,
        non_ascii: true,
        nullable: true,
        unknown: true,
        trivia: Trivia::Always,
    };
    const ANY: Self = Self {
        ascii: u128::MAX,
        non_ascii: true,
        ..Self::EMPTY
    };

    fn of_char(c: char) -> Self {
        let mut set = Self::EMPTY;
        set.add_char(c);
        set
    }

    fn of_chars(chars: &str) -> Self {
        let mut set = Self::EMPTY;
        for c in chars.chars() {
            set.add_char(c);
        }
        set
    }

    fn add_char(&mut self, c: char) {
        if c.is_ascii() {
            self.ascii |= 1 << (c as u32);
        } else {
            self.non_ascii = true;
        }
    }

    /// Union of the start sets; `nullable` is left to the caller.
    fn union_starts(&mut self, other: Self) {
        self.ascii |= other.ascii;
        self.non_ascii |= other.non_ascii;
        self.unknown |= other.unknown;
        self.trivia = self.trivia.max(other.trivia);
    }

    fn union(mut self, other: Self) -> Self {
        self.union_starts(other);
        self.nullable |= other.nullable;
        self
    }

    /// Whether a match may start at `rest`. `false` proves that the expression fails there.
    pub(crate) fn may_start(self, rest: &str, whitespace: bool) -> bool {
        if self.unknown || self.nullable {
            return true;
        }
        let Some(&byte) = rest.as_bytes().first() else {
            // A non-nullable expression must consume a code point.
            return false;
        };
        let trivia = match self.trivia {
            Trivia::None => false,
            Trivia::Dynamic => whitespace,
            Trivia::Always => true,
        };
        if trivia
            && (matches!(byte, b' ' | b'\t' | b'\n' | b'\r' | 11 | 12)
                || rest.starts_with("//")
                || rest.starts_with("/*"))
        {
            return true;
        }
        if byte.is_ascii() {
            self.ascii & (1 << byte) != 0
        } else {
            self.non_ascii
        }
    }
}

/// The FIRST set of an expression; `Rule(id)` is looked up in `rules` (the fixed-point table).
pub(crate) fn of(expression: &Expr, rules: &[FirstSet]) -> FirstSet {
    match expression {
        Expr::Literal(text) => text
            .chars()
            .next()
            .map_or(FirstSet::NULLABLE, FirstSet::of_char),
        Expr::Number => FirstSet::of_chars("+-.0123456789"),
        Expr::Identifier => {
            FirstSet::of_chars("_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        }
        Expr::CodeStart | Expr::CodeEnd => FirstSet::of_char('`'),
        Expr::Quoted(quote) => FirstSet::of_char(*quote),
        // Outside the table (a grammar installed without its analysis) nothing is proven.
        Expr::Rule(id) => rules.get(*id).copied().unwrap_or(FirstSet::UNKNOWN),
        Expr::Sequence(elements) => {
            // ParseContext::skip runs before the first element when trivia is enabled.
            let mut set = FirstSet {
                nullable: true,
                trivia: Trivia::Dynamic,
                ..FirstSet::EMPTY
            };
            for element in elements {
                let element = of(element, rules);
                if element.unknown {
                    return FirstSet::UNKNOWN;
                }
                set.union_starts(element);
                if !element.nullable {
                    set.nullable = false;
                    break;
                }
            }
            set
        }
        Expr::Choice(alternatives) | Expr::LongestChoice(alternatives) => alternatives
            .iter()
            .fold(FirstSet::EMPTY, |set, alternative| {
                set.union(of(alternative, rules))
            }),
        Expr::PredictiveChoice { alternatives, .. } => alternatives
            .iter()
            .fold(FirstSet::EMPTY, |set, alternative| {
                set.union(of(alternative, rules))
            }),
        Expr::Capture(_, child)
        | Expr::TextValue(child)
        | Expr::ValueBoundary(child)
        | Expr::RuleEffects { child, .. } => of(child, rules),
        Expr::TriviaScope { child, whitespace } => {
            let mut set = of(child, rules);
            set.trivia = match (set.trivia, whitespace) {
                (Trivia::Dynamic, true) => Trivia::Always,
                (Trivia::Dynamic, false) => Trivia::None,
                (trivia, _) => trivia,
            };
            set
        }
        Expr::Optional(child) | Expr::JavaOptional(child) => FirstSet {
            nullable: true,
            ..of(child, rules)
        },
        Expr::Repeat { child, min, .. } | Expr::JavaRepeat { child, min, .. } => {
            let mut set = of(child, rules);
            set.nullable |= *min == 0;
            set
        }
        // A lookahead consumes nothing, but its child still runs at this position.
        Expr::Lookahead { child, .. } => {
            if of(child, rules).unknown {
                FirstSet::UNKNOWN
            } else {
                FirstSet::NULLABLE
            }
        }
        Expr::Any => FirstSet::ANY,
        Expr::CharRange(min, max) => {
            let mut set = FirstSet::EMPTY;
            if min <= max {
                for byte in (*min as u32)..=(*max as u32).min(127) {
                    set.ascii |= 1 << byte;
                }
                set.non_ascii = *max as u32 >= 128;
            }
            set
        }
        Expr::Except(excluded) => {
            let mut set = FirstSet::ANY;
            for c in excluded.chars().filter(char::is_ascii) {
                set.ascii &= !(1 << (c as u32));
            }
            set
        }
        Expr::Eof | Expr::Empty | Expr::JavaEmpty | Expr::JavaLookahead { .. } => {
            FirstSet::NULLABLE
        }
        Expr::Error(_) => FirstSet::EMPTY,
        Expr::Until(_)
        | Expr::JavaUntil(_)
        | Expr::Custom(_)
        | Expr::CustomWith { .. }
        | Expr::Backreference(_) => FirstSet::UNKNOWN,
    }
}

/// Least fixed point of the rule FIRST sets. Convergence compares whole sets, not
/// `may_start` answers: a nullable set accepts every code point, yet its start set still
/// flows into enclosing sequences (the Java case 37 fixed-point bug).
pub(crate) fn compute(rules: &[Rule]) -> Vec<FirstSet> {
    let mut table = vec![FirstSet::EMPTY; rules.len()];
    loop {
        let mut changed = false;
        for (id, rule) in rules.iter().enumerate() {
            let set = of(&rule.expression, &table).union(table[id]);
            if set != table[id] {
                table[id] = set;
                changed = true;
            }
        }
        if !changed {
            return table;
        }
    }
}

type CacheEntry = (Weak<[Rule]>, Arc<[FirstSet]>);

/// FIRST tables of long-lived shared grammars, keyed by allocation. A `Weak` keeps the
/// allocation (not the rules) alive, so another grammar cannot reuse a cached address.
fn cache() -> &'static Mutex<Vec<CacheEntry>> {
    static CACHE: OnceLock<Mutex<Vec<CacheEntry>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(Vec::new()))
}

/// The FIRST table for `grammar`. Grammars shared beyond this parse (for example a generated
/// parser's `OnceLock<SharedGrammar>`) are analysed once; a grammar owned only by this parse
/// is analysed without being cached.
pub(crate) fn table(grammar: &Arc<[Rule]>) -> Arc<[FirstSet]> {
    if Arc::strong_count(grammar) <= 1 {
        return Arc::from(compute(grammar));
    }
    let mut entries = cache()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some((_, table)) = entries
        .iter()
        .find(|(weak, _)| std::ptr::eq(weak.as_ptr(), Arc::as_ptr(grammar)))
    {
        return Arc::clone(table);
    }
    entries.retain(|(weak, _)| weak.strong_count() > 0);
    let table: Arc<[FirstSet]> = Arc::from(compute(grammar));
    entries.push((Arc::downgrade(grammar), Arc::clone(&table)));
    table
}

/// How candidate exclusion behaves; read once from `UNLAXER_CANDIDATE_EXCLUSION`
/// (`off` / `audit`, anything else is `on`) and overridable per thread for tests.
#[doc(hidden)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CandidateExclusion {
    /// Never skip candidates (the pre-#300 behaviour).
    Off,
    /// Skip candidates whose FIRST set proves failure (deferred diagnostics only).
    On,
    /// Evaluate every candidate that would be skipped and panic if it succeeds.
    Audit,
}

thread_local! {
    static OVERRIDE: std::cell::Cell<Option<CandidateExclusion>> =
        const { std::cell::Cell::new(None) };
}

/// Test and benchmark hook: overrides the exclusion mode for contexts created on this thread.
#[doc(hidden)]
pub fn set_candidate_exclusion_for_current_thread(mode: Option<CandidateExclusion>) {
    OVERRIDE.with(|cell| cell.set(mode));
}

pub(crate) fn mode() -> CandidateExclusion {
    if let Some(mode) = OVERRIDE.with(std::cell::Cell::get) {
        return mode;
    }
    static MODE: OnceLock<CandidateExclusion> = OnceLock::new();
    *MODE.get_or_init(
        || match std::env::var("UNLAXER_CANDIDATE_EXCLUSION").as_deref() {
            Ok("off") => CandidateExclusion::Off,
            Ok("audit") => CandidateExclusion::Audit,
            _ => CandidateExclusion::On,
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(name: &'static str, expression: Expr) -> Rule {
        Rule { name, expression }
    }

    #[test]
    fn a_nullable_repetition_keeps_growing_its_start_set_until_the_fixed_point() {
        // Java case 37's convergence bug: `Annotation*` is nullable in every round, so
        // comparing `may_start` answers would stop before `@` reached the declaration.
        let rules = vec![
            rule(
                "declaration",
                Expr::sequence([Expr::Rule(1).zero_or_more(), Expr::Identifier]),
            ),
            rule(
                "annotation",
                Expr::sequence([Expr::literal("@"), Expr::Rule(2)]),
            ),
            rule("name", Expr::Identifier),
        ];
        let table = compute(&rules);
        assert!(table[0].may_start("@root x", false));
        assert!(table[0].may_start("x", false));
        assert!(!table[0].may_start("1", false));
        assert!(!table[0].may_start("", false));
        assert!(!table[1].nullable);
    }

    #[test]
    fn left_recursion_through_nullable_prefixes_converges() {
        let rules = vec![rule(
            "list",
            Expr::choice([
                Expr::sequence([Expr::Rule(0), Expr::literal(",")]),
                Expr::literal("x").optional(),
            ]),
        )];
        let table = compute(&rules);
        assert!(table[0].nullable);
        assert_eq!(table[0].ascii, (1 << b'x') | (1 << b','));
    }

    #[test]
    fn sequence_trivia_follows_the_caller_policy_unless_a_scope_fixes_it() {
        let sequence = Expr::sequence([Expr::literal("a")]);
        let set = of(&sequence, &[]);
        assert!(set.may_start(" a", true));
        assert!(set.may_start("/* c */a", true));
        assert!(set.may_start("// c\na", true));
        assert!(!set.may_start(" a", false));
        assert!(!set.may_start("/a", true));
        let fixed = of(&sequence.clone().trivia_scope(true), &[]);
        assert!(fixed.may_start(" a", false));
        let off = of(&sequence.trivia_scope(false), &[]);
        assert!(!off.may_start(" a", true));
        // An atom never skips trivia.
        assert!(!of(&Expr::literal("a"), &[]).may_start(" a", true));
    }

    #[test]
    fn unmodelled_starts_are_unknown_and_never_excluded() {
        fn custom(context: &mut crate::ParseContext<'_>) -> crate::ParseResult {
            Err(context.error("custom"))
        }
        for expression in [
            Expr::Custom(custom),
            Expr::Backreference("name"),
            Expr::Until("x"),
            Expr::JavaUntil("x"),
            Expr::Custom(custom).ahead(),
            Expr::sequence([Expr::literal("a").optional(), Expr::Custom(custom)]),
            Expr::choice([Expr::literal("a"), Expr::Backreference("name")]),
            Expr::Rule(7),
        ] {
            let set = of(&expression, &[]);
            assert!(set.unknown, "{expression:?}");
            assert!(set.may_start("", false), "{expression:?}");
        }
        // After a consumed prefix, an unmodelled parser does not widen the start set.
        let set = of(
            &Expr::sequence([Expr::literal("a"), Expr::Custom(custom)]),
            &[],
        );
        assert!(!set.unknown);
        assert!(!set.may_start("b", false));
    }

    #[test]
    fn lexical_atoms_have_exact_ascii_sets() {
        let digit = of(&Expr::Number, &[]);
        for start in ["1", "+", "-", "."] {
            assert!(digit.may_start(start, false), "{start}");
        }
        assert!(!digit.may_start("x", false));
        let range = of(&Expr::CharRange('a', 'c'), &[]);
        assert!(range.may_start("b", false) && !range.may_start("d", false));
        assert!(!range.may_start("é", false));
        let wide = of(&Expr::CharRange('x', 'é'), &[]);
        assert!(wide.may_start("é", false) && wide.may_start("z", false));
        let except = of(&Expr::Except("ab"), &[]);
        assert!(!except.may_start("a", false) && except.may_start("c", false));
        assert!(except.may_start("é", false));
        assert!(!of(&Expr::Error("never"), &[]).may_start("x", false));
        assert!(of(&Expr::Eof, &[]).may_start("x", false));
        assert!(of(&Expr::literal(""), &[]).may_start("x", false));
    }
}
