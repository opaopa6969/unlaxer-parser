//! A deliberately bounded replay of the fixed bootstrap parser graph (d8af6d9b).
//! UBNFFile -> GrammarDecl -> Identifier is the ubnf.ubnf rule mapping. Parser
//! class names, rather than rule names, are the observable Java diagnostic API.
//! Reaching the body or an unterminated comment returns None: neither the native
//! frontend's first error nor an incomplete replay can supply Java provenance.
use super::{diagnostic, text};
use serde_json::Value;
use std::collections::BTreeSet;

enum Kind {
    Word(&'static str),
    Character(fn(char) -> bool),
    Chain,
    Choice,
    Repeat,
    Comment,
    Unsupported,
}

struct Node {
    name: &'static str,
    hint: Option<&'static str>,
    kind: Kind,
    children: Vec<usize>,
}

struct Recognizer<'a> {
    source: &'a str,
    nodes: Vec<Node>,
    stack: Vec<usize>,
    at: usize,
    farthest: usize,
    depth: usize,
    deepest: &'static str,
    expected: BTreeSet<String>,
}

impl Recognizer<'_> {
    fn diagnostic(self, consumed: usize) -> Value {
        let expected = self.expected.into_iter().take(20).collect::<Vec<_>>();
        diagnostic(
            self.source,
            self.farthest,
            consumed,
            &expected,
            self.deepest,
        )
    }

    fn node(
        &mut self,
        name: &'static str,
        hint: Option<&'static str>,
        kind: Kind,
        children: Vec<usize>,
    ) -> usize {
        let id = self.nodes.len();
        self.nodes.push(Node {
            name,
            hint,
            kind,
            children,
        });
        id
    }

    // ParseContext expands failed parsers to depth two, stopping at terminals.
    // A terminal without display text falls back to the failed parser's name.
    fn hints(&self, id: usize, depth: usize, out: &mut BTreeSet<String>) {
        let node = &self.nodes[id];
        if let Some(hint) = node.hint {
            out.insert(hint.into());
        } else if depth < 2 && !matches!(node.kind, Kind::Character(_) | Kind::Word(_)) {
            for &child in &node.children {
                self.hints(child, depth + 1, out);
            }
        }
    }

    fn failed(&mut self, id: usize) {
        if self.at < self.farthest {
            return;
        }
        if self.at > self.farthest {
            self.farthest = self.at;
            self.expected.clear();
            self.depth = 0;
        }
        if self.stack.len() > self.depth {
            self.depth = self.stack.len();
            self.deepest = self.nodes[id].name;
        }
        let mut hints = BTreeSet::new();
        self.hints(id, 0, &mut hints);
        if hints.is_empty() {
            hints.insert(self.nodes[id].name.into());
        }
        self.expected.extend(hints);
        // The innermost open terminal contributes separately from expansion.
        if let Some(hint) = self.stack.iter().rev().find_map(|&i| self.nodes[i].hint) {
            self.expected.insert(hint.into());
        }
    }

    fn run(&mut self, id: usize) -> Option<bool> {
        self.stack.push(id);
        let start = self.at;
        let children = self.nodes[id].children.clone();
        let success = match self.nodes[id].kind {
            Kind::Word(word) => {
                if self.source[self.at..].starts_with(word) {
                    self.at += word.len();
                    true
                } else {
                    false
                }
            }
            Kind::Character(matches) => {
                if let Some(c) = self.source[self.at..]
                    .chars()
                    .next()
                    .filter(|&c| matches(c))
                {
                    self.at += c.len_utf8();
                    true
                } else {
                    false
                }
            }
            Kind::Chain => {
                let mut success = true;
                for child in children {
                    if !self.run(child)? {
                        success = false;
                        self.at = start;
                        break;
                    }
                }
                success
            }
            Kind::Choice => {
                let mut success = false;
                for child in children {
                    if self.run(child)? {
                        success = true;
                        break;
                    }
                }
                success
            }
            Kind::Repeat => {
                while self.run(children[0])? {}
                true
            }
            Kind::Comment => {
                if self.run(children[0])? {
                    // Only replay terminated comments. Their speculative failures
                    // are strictly before the later header failure frontier.
                    self.at += self.source[self.at..].find(['\r', '\n'])?;
                    true
                } else {
                    false
                }
            }
            Kind::Unsupported => return None,
        };
        if !success {
            self.failed(id);
        }
        self.stack.pop();
        Some(success)
    }
}

pub(super) fn replay(source: &str, start: usize, consumed: usize) -> Option<Value> {
    let mut r = Recognizer {
        source,
        nodes: vec![],
        stack: vec![],
        at: start,
        farthest: start,
        depth: 0,
        deepest: "",
        expected: BTreeSet::new(),
    };
    let space = r.node(
        "SpaceParser",
        Some("' '"),
        Kind::Character(text::regex_space),
        vec![],
    );
    let slash = r.node("WordParser", Some("'//'"), Kind::Word("//"), vec![]);
    let comment = r.node("CPPComment", None, Kind::Comment, vec![slash]);
    let choice = r.node("Choice", None, Kind::Choice, vec![space, comment]);
    let trivia = r.node("UBNFSpaceDelimitor", None, Kind::Repeat, vec![choice]);
    let keyword = r.node(
        "WordParser",
        Some("'grammar'"),
        Kind::Word("grammar"),
        vec![],
    );
    let first = r.node(
        "AlphabetUnderScoreParser",
        None,
        Kind::Character(|c| c.is_ascii_alphabetic() || c == '_'),
        vec![],
    );
    let rest = r.node(
        "AlphabetNumericUnderScoreParser",
        None,
        Kind::Character(text::word),
        vec![],
    );
    let suffix = r.node("ZeroOrMore", None, Kind::Repeat, vec![rest]);
    let identifier = r.node("IdentifierParser", None, Kind::Chain, vec![first, suffix]);
    let left = r.node("LeftBraceParser", Some("'{'"), Kind::Word("{"), vec![]);
    let right = r.node("RightBraceParser", Some("'}'"), Kind::Word("}"), vec![]);
    let body = r.node("body-not-replayed", None, Kind::Unsupported, vec![]);
    // Preserve the complete parent's depth-two terminal hints even when it
    // rolls back before the failed child. The body is never evaluated here.
    let grammar = r.node(
        "GrammarDeclParser",
        None,
        Kind::Chain,
        vec![
            trivia, keyword, trivia, identifier, trivia, left, trivia, body, right, trivia,
        ],
    );
    let root = r.node("UBNFFileParser", None, Kind::Unsupported, vec![grammar]);
    r.stack.push(root);
    if r.run(grammar)? {
        return None;
    }
    // The root's failed OneOrMore rolls back only if no grammar succeeded.
    if consumed == 0 {
        r.at = start;
        r.failed(root);
    }
    Some(r.diagnostic(consumed))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unsupported_attempts_do_not_authorize_a_diagnostic() {
        for source in ["grammar A { @ro", "grammar // comment", "grammar A // 😀"] {
            assert!(replay(source, 0, 0).is_none(), "{source}");
        }
        // A complete syntax root that fails only in the mapper must retain the
        // existing replay state; do not fabricate a failed header at EOF.
        let replay = crate::failure::replay("grammar A {}");
        assert!(replay.complete);
        assert!(replay.root_diagnostic.is_none());
    }

    #[test]
    fn candidate_expansion_stops_at_two_and_adds_innermost_terminal() {
        let mut r = Recognizer {
            source: "",
            nodes: vec![],
            stack: vec![],
            at: 0,
            farthest: 0,
            depth: 0,
            deepest: "",
            expected: BTreeSet::new(),
        };
        let hidden = r.node(
            "hidden",
            Some("hidden-at-depth-three"),
            Kind::Word("x"),
            vec![],
        );
        let depth_two = r.node("two", None, Kind::Chain, vec![hidden]);
        let mut children = vec![depth_two];
        for _ in 0..2 {
            for hint in [
                "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p",
                "q", "r", "s", "t", "u",
            ] {
                children.push(r.node("terminal", Some(hint), Kind::Word("x"), vec![]));
            }
        }
        let middle = r.node("middle", None, Kind::Chain, children);
        let failed = r.node("failed", None, Kind::Chain, vec![middle]);
        let outer = r.node("outer", Some("0-outer"), Kind::Word("x"), vec![]);
        let inner = r.node("inner", Some("0-inner"), Kind::Word("x"), vec![]);
        r.stack = vec![outer, inner, failed];
        r.failed(failed);
        assert!(!r.expected.contains("hidden-at-depth-three"));
        assert!(!r.expected.contains("0-outer"));
        let diagnostic = r.diagnostic(0);
        assert_eq!(
            diagnostic["data"]["expectedTokens"],
            serde_json::json!([
                "0-inner", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n",
                "o", "p", "q", "r", "s"
            ])
        );
    }
}
