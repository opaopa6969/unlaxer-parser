//! Simple annotations (`'@' IDENTIFIER`, #715), shared in meaning with Java's `SimpleAnnotations`.
//!
//! A Simple annotation is retained metadata: Java has generated every grammar with one as if it
//! were absent, so it stays accepted and has no effect on generation. Only `@nestedCaptures` and
//! `@autoCommit` act as Simple annotations. Every other one is reported as `W-ANNOTATION-IGNORED`
//! at the annotation span, so a typo or a misspelt form of a functional annotation is not lost
//! silently; a strict / fail-on-warning run turns it into a failure.
//!
//! One interaction remains: the `@autoCommit` analysis treats a rule with such an annotation as
//! opaque, so in a grammar it analyses the annotation can change where commits are inserted.
//! The warning then says so ([`Warning::excludes_auto_commit`]).
use unlaxer_ubnf::*;

pub const IGNORED: &str = "W-ANNOTATION-IGNORED";

/// The Simple annotation names that have an effect.
pub const FUNCTIONAL: [&str; 2] = ["nestedCaptures", "autoCommit"];

/// The dedicated annotation keywords. A Simple annotation with one of these names was written
/// with trivia after `@` (`@ leftAssoc`) or, for an argument keyword, without its `(`.
pub const KEYWORDS: [&str; 22] = [
    "root",
    "mapping",
    "eval",
    "whitespace",
    "interleave",
    "lexicalContext",
    "backref",
    "scopeTree",
    "declares",
    "catalog",
    "leftAssoc",
    "rightAssoc",
    "longestChoice",
    "uniqueLongestChoice",
    "namePredicate",
    "predictiveChoice",
    "precedence",
    "doc",
    "recovery",
    "skip",
    "commonField",
    "enum",
];

/// The keywords that require arguments.
const ARGUMENTS: [&str; 13] = [
    "mapping",
    "eval",
    "interleave",
    "lexicalContext",
    "backref",
    "scopeTree",
    "declares",
    "catalog",
    "namePredicate",
    "precedence",
    "doc",
    "recovery",
    "commonField",
];

/// One ignored Simple annotation. `span` is the annotation, from `@` to the end of its name.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Warning {
    pub rule: String,
    /// The annotation name as written.
    pub subject: String,
    pub span: Span,
    /// The annotation the author probably meant: the keyword itself for a keyword name,
    /// otherwise the nearest known name (see [`suggestion`]).
    pub suggestion: Option<&'static str>,
    /// The grammar is analysed by `@autoCommit` ([`unlaxer_ubnf::auto_commit::analyses`]),
    /// which then leaves this rule out as opaque.
    pub excludes_auto_commit: bool,
}

impl Warning {
    pub fn code(&self) -> &'static str {
        IGNORED
    }

    pub fn severity(&self) -> &'static str {
        "warning"
    }

    pub fn message(&self) -> String {
        let name = &self.subject;
        let effect = if self.excludes_auto_commit {
            format!(
                "is not used by the generator, but it excludes rule {} from @autoCommit analysis",
                self.rule
            )
        } else {
            "has no effect".to_owned()
        };
        let text = if KEYWORDS.contains(&name.as_str()) {
            let arguments = if ARGUMENTS.contains(&name.as_str()) {
                "(...)"
            } else {
                ""
            };
            format!(
                "@{name} is a plain annotation here and {effect}; \
                 write @{name}{arguments} with no space after '@'"
            )
        } else if let Some(known) = self.suggestion {
            format!("unknown annotation @{name} {effect}; did you mean @{known}?")
        } else {
            format!("unknown annotation @{name} {effect}")
        };
        format!("{IGNORED}: {}: {text}", self.rule)
    }
}

/// One warning per Simple annotation without an effect, in rule order and then source order.
/// Pass the tree of [`unlaxer_ubnf::auto_commit::parse_before_auto_commit`], like
/// `auto_commit::warnings`: the rewritten tree no longer shows whether `@autoCommit` analyses
/// the grammar.
pub fn warnings(grammar: &GrammarDecl) -> Vec<Warning> {
    let mut out = Vec::new();
    let mut analysed = None;
    for rule in &grammar.rules {
        for annotation in &rule.annotations {
            let AnnotationKind::Simple { name } = &annotation.kind else {
                continue;
            };
            if FUNCTIONAL.contains(&name.as_str()) {
                continue;
            }
            let excludes_auto_commit =
                *analysed.get_or_insert_with(|| unlaxer_ubnf::auto_commit::analyses(grammar));
            out.push(Warning {
                rule: rule.name.clone(),
                subject: name.clone(),
                span: annotation.span,
                suggestion: suggestion(name),
                excludes_auto_commit,
            });
        }
    }
    out
}

/// The known annotation name nearest to `name`: an exact keyword is itself; otherwise the
/// smallest optimal-string-alignment distance after ASCII lowercasing, at most
/// `max(1, code points / 3)`, with ties going to the earlier name of [`KEYWORDS`] then
/// [`FUNCTIONAL`]. Java's `SimpleAnnotations.suggestion` is the same function.
pub fn suggestion(name: &str) -> Option<&'static str> {
    if let Some(keyword) = KEYWORDS.iter().find(|keyword| **keyword == name) {
        return Some(keyword);
    }
    if FUNCTIONAL.contains(&name) {
        return None;
    }
    let typed: Vec<char> = name.chars().map(|c| c.to_ascii_lowercase()).collect();
    let limit = (typed.len() / 3).max(1);
    let mut best: Option<(usize, &'static str)> = None;
    for known in KEYWORDS.iter().chain(FUNCTIONAL.iter()) {
        let candidate: Vec<char> = known.chars().map(|c| c.to_ascii_lowercase()).collect();
        let d = distance(&typed, &candidate);
        if d <= limit && best.is_none_or(|(least, _)| d < least) {
            best = Some((d, known));
        }
    }
    best.map(|(_, known)| known)
}

/// Optimal string alignment distance: insertions, deletions, substitutions and transpositions
/// of adjacent characters, each substring edited at most once.
fn distance(a: &[char], b: &[char]) -> usize {
    let mut rows = vec![vec![0usize; b.len() + 1]; a.len() + 1];
    for (i, row) in rows.iter_mut().enumerate() {
        row[0] = i;
    }
    for (j, cell) in rows[0].iter_mut().enumerate() {
        *cell = j;
    }
    for i in 1..=a.len() {
        for j in 1..=b.len() {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            let mut value = (rows[i - 1][j] + 1)
                .min(rows[i][j - 1] + 1)
                .min(rows[i - 1][j - 1] + cost);
            if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] {
                value = value.min(rows[i - 2][j - 2] + 1);
            }
            rows[i][j] = value;
        }
    }
    rows[a.len()][b.len()]
}
