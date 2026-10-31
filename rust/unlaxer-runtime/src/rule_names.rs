//! Construction-time rule names: the Rust boundary of Java `ParserNameContext`,
//! `ReferenceByNameParser` and `ReferenceParser.of(Name)` (#845).
//!
//! Names are resolved once while the rule vector is built; parsing only follows
//! `Expr::Rule(index)`. Declaring every name before building the bodies gives
//! recursive and forward references stable IDs without lazy initialization.
//!
//! An ID is a position in the table that issued it and is meaningful only for the rule
//! vector shared through that table. Rebuilding the vector (adding, removing or
//! reordering rules) needs a new table and new references: [`RuleNames::share`] rejects
//! IDs outside the vector, but an in-range ID kept from an older table silently names
//! whichever rule now has that position. `Expr::Custom` callbacks are opaque code, so
//! the indices they use are the callback's responsibility.
use std::collections::HashMap;
use std::fmt;

use crate::{share_grammar, Expr, Rule, SharedGrammar};

/// Validated name to rule-index table for one grammar. Indices are declaration positions.
#[derive(Debug, Clone, Default)]
pub struct RuleNames {
    names: Vec<&'static str>,
    indices: HashMap<&'static str, usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RuleNameError {
    /// Two declarations share a name. One rule reused through several references is
    /// declared once and is not a duplicate.
    Duplicate {
        name: &'static str,
        first: usize,
        second: usize,
    },
    /// No declaration has this name. Java `ReferenceByNameParser` fails at parse time
    /// instead; [`RuleNames::reference_or_never`] keeps that behavior explicitly.
    Unregistered(String),
    /// The built rule vector does not follow the declared names and order.
    Mismatch {
        index: usize,
        declared: Option<&'static str>,
        actual: Option<&'static str>,
    },
    /// Rule `rule` contains `Expr::Rule(reference)` but the vector has only `rules` rules,
    /// for example an ID issued by another table. Parsing would treat it as an ordinary
    /// failure, so an enclosing choice would silently try its next alternative.
    OutOfRange {
        rule: usize,
        reference: usize,
        rules: usize,
    },
}

impl fmt::Display for RuleNameError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Duplicate {
                name,
                first,
                second,
            } => write!(
                formatter,
                "duplicate rule name {name} at {first} and {second}"
            ),
            Self::Unregistered(name) => write!(formatter, "unregistered rule name {name}"),
            Self::Mismatch {
                index,
                declared,
                actual,
            } => write!(
                formatter,
                "rule {index} is {actual:?}, declared as {declared:?}"
            ),
            Self::OutOfRange {
                rule,
                reference,
                rules,
            } => write!(
                formatter,
                "rule {rule} references rule {reference} of {rules}"
            ),
        }
    }
}

impl std::error::Error for RuleNameError {}

impl RuleNames {
    /// Declares rule names in index order, rejecting duplicates.
    pub fn new(names: impl IntoIterator<Item = &'static str>) -> Result<Self, RuleNameError> {
        let mut table = Self::default();
        for name in names {
            let index = table.names.len();
            if let Some(&first) = table.indices.get(name) {
                return Err(RuleNameError::Duplicate {
                    name,
                    first,
                    second: index,
                });
            }
            table.indices.insert(name, index);
            table.names.push(name);
        }
        Ok(table)
    }

    pub fn get(&self, name: &str) -> Option<usize> {
        self.indices.get(name).copied()
    }

    /// `Expr::Rule(index)` for a declared name.
    pub fn reference(&self, name: &str) -> Result<Expr, RuleNameError> {
        self.get(name)
            .map(Expr::Rule)
            .ok_or_else(|| RuleNameError::Unregistered(name.to_owned()))
    }

    /// Java-compatible reference: an unregistered name never matches, even inverted,
    /// so an enclosing choice tries its next alternative. Prefer [`Self::reference`].
    pub fn reference_or_never(&self, name: &str) -> Expr {
        self.reference(name).unwrap_or(Expr::JavaEmptyWord)
    }

    /// Shares rules built in the declared order; a missing, extra or renamed rule fails,
    /// and so does a rule body referencing an index outside the vector.
    pub fn share(&self, rules: Vec<Rule>) -> Result<SharedGrammar, RuleNameError> {
        for index in 0..self.names.len().max(rules.len()) {
            let declared = self.names.get(index).copied();
            let actual = rules.get(index).map(|rule| rule.name);
            if declared != actual {
                return Err(RuleNameError::Mismatch {
                    index,
                    declared,
                    actual,
                });
            }
        }
        for (rule, body) in rules.iter().enumerate() {
            if let Some(reference) = first_out_of_range(&body.expression, rules.len()) {
                return Err(RuleNameError::OutOfRange {
                    rule,
                    reference,
                    rules: rules.len(),
                });
            }
        }
        Ok(share_grammar(rules))
    }
}

/// The first `Expr::Rule(index)` with `index >= rules`, found without recursion so deep
/// bodies are safe. Listing every variant makes a new child-bearing variant a compile error.
fn first_out_of_range(expression: &Expr, rules: usize) -> Option<usize> {
    let mut pending = vec![expression];
    while let Some(expression) = pending.pop() {
        match expression {
            Expr::Rule(index) => {
                if *index >= rules {
                    return Some(*index);
                }
            }
            Expr::Sequence(children)
            | Expr::NonOrdered(children)
            | Expr::Choice(children)
            | Expr::LongestChoice(children)
            | Expr::UniqueLongestChoice(children)
            | Expr::PredictiveChoice {
                alternatives: children,
                ..
            } => pending.extend(children.iter().rev()),
            Expr::Capture(_, child)
            | Expr::MetadataCapture(_, child)
            | Expr::CaptureScope(child)
            | Expr::OwnedCapture(_, child)
            | Expr::MatchOnly(child)
            | Expr::InvertMatch(child)
            | Expr::StopInvert(child)
            | Expr::StopConsume(child)
            | Expr::TextValue(child)
            | Expr::ValueBoundary(child)
            | Expr::CaptureBoundary(_, child)
            | Expr::InputCaptureBoundary(_, child)
            | Expr::RuleEffects { child, .. }
            | Expr::NamePredicate { child, .. }
            | Expr::NameResolutionScope { child, .. }
            | Expr::CaptureEquality { child, .. }
            | Expr::LexicalTriviaScope { child, .. }
            | Expr::LexicalContextScope { child, .. }
            | Expr::TriviaScope { child, .. }
            | Expr::Optional(child)
            | Expr::JavaOptional(child)
            | Expr::Repeat { child, .. }
            | Expr::ZeroMatch(child)
            | Expr::JavaRepeat { child, .. }
            | Expr::Lookahead { child, .. }
            | Expr::Recovery { child, .. }
            | Expr::Commit { child, .. }
            | Expr::Label { child, .. } => pending.push(child),
            Expr::CatchLabels {
                child, alternative, ..
            } => {
                pending.push(alternative);
                pending.push(child);
            }
            // The terminator runs before each body attempt, so it is checked first.
            Expr::JavaTerminatedRepeat {
                child, terminator, ..
            } => {
                pending.push(child);
                pending.push(terminator);
            }
            Expr::Lexical(..)
            | Expr::Literal(_)
            | Expr::JavaLiteral(_)
            | Expr::CaseInsensitive(_)
            | Expr::Number
            | Expr::Identifier
            | Expr::Grapheme
            | Expr::CodeStart
            | Expr::CodeEnd
            | Expr::LongCodeBlock
            | Expr::Quoted(_)
            | Expr::Replay(_)
            | Expr::Any
            | Expr::Eof
            | Expr::Empty
            | Expr::CharRange(..)
            | Expr::Character { .. }
            | Expr::Except(_)
            | Expr::Until(_)
            | Expr::JavaLookahead { .. }
            | Expr::JavaEmpty
            | Expr::JavaEmptyWord
            | Expr::JavaEmptyLiteral
            | Expr::JavaUntil(_)
            | Expr::Error(_)
            | Expr::Custom(_)
            | Expr::CustomWith { .. }
            | Expr::Backreference(_) => {}
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn share_checks_references_in_deep_bodies_without_recursion() {
        let names = RuleNames::new(["Deep", "Leaf"]).unwrap();
        let build = |reference: usize| {
            let mut expression = Expr::Rule(reference);
            for _ in 0..20_000 {
                expression = Expr::Optional(Box::new(expression));
            }
            vec![
                Rule {
                    name: "Deep",
                    expression: Expr::choice([Expr::literal("x"), expression]),
                },
                Rule {
                    name: "Leaf",
                    expression: Expr::literal("a"),
                },
            ]
        };
        let mut rules = build(1);
        assert!(first_out_of_range(&rules[0].expression, rules.len()).is_none());
        rules = build(2);
        assert_eq!(
            first_out_of_range(&rules[0].expression, rules.len()),
            Some(2)
        );
        assert!(matches!(
            names.share(rules),
            Err(RuleNameError::OutOfRange {
                rule: 0,
                reference: 2,
                rules: 2
            })
        ));
        let catch = Expr::CatchLabels {
            child: Box::new(Expr::Rule(0)),
            alternative: Box::new(Expr::Rule(7)),
            labels: vec!["l"],
        };
        assert_eq!(first_out_of_range(&catch, 2), Some(7));
        // Both the body and the terminator of a Java terminated repetition are checked.
        for (child, terminator, missing) in [(0, 8, Some(8)), (9, 1, Some(9)), (0, 1, None)] {
            let terminated = Expr::Rule(child).repeat_java_until(Expr::Rule(terminator), 0, None);
            assert_eq!(first_out_of_range(&terminated, 2), missing);
        }
    }
}
