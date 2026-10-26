//! Construction-time rule names: the Rust boundary of Java `ParserNameContext`,
//! `ReferenceByNameParser` and `ReferenceParser.of(Name)` (#845).
//!
//! Names are resolved once while the rule vector is built; parsing only follows
//! `Expr::Rule(index)`. Declaring every name before building the bodies gives
//! recursive and forward references stable IDs without lazy initialization.
use std::collections::HashMap;
use std::fmt;

use crate::{share_grammar, Expr, Rule, SharedGrammar};

/// Validated name to rule-index table. Indices are declaration positions.
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

    /// Shares rules built in the declared order; a missing, extra or renamed rule fails.
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
        Ok(share_grammar(rules))
    }
}
