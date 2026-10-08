//! Selectable, parse-local lexical input. Positions exposed to consumers are code points.
use crate::lexical::{LexicalExpression, Op};
use crate::{Diagnostics, ParseContext, ParseOptions, SharedGrammar, Span, Tree};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Arc;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Mode {
    #[default]
    Direct,
    TriviaCache,
    TokensLazy,
    TokensEager,
}
#[derive(Clone, Copy, Debug)]
pub struct Options {
    pub mode: Mode,
    pub preserve_trivia: bool,
}
impl Default for Options {
    fn default() -> Self {
        Self {
            mode: Mode::Direct,
            preserve_trivia: true,
        }
    }
}
#[derive(Clone, Debug)]
pub struct Terminal {
    pub name: &'static str,
    pub literal: bool,
    pub expression: LexicalExpression,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Lexeme {
    pub kind: &'static str,
    pub name: &'static str,
    pub span: Span,
}
#[derive(Clone, Copy, Debug)]
pub struct Metrics {
    pub terminal_evaluations: u64,
    pub trivia_evaluations: u64,
    pub inventory_evaluations: u64,
    pub retained_entries: usize,
    pub source_code_points: usize,
}
pub struct Outcome<'a> {
    pub tree: Option<Tree>,
    pub succeeded: bool,
    pub consumed: usize,
    pub matched: usize,
    pub farthest: usize,
    pub expected: Vec<String>,
    pub session: Session<'a>,
}
#[derive(Clone)]
struct Entry {
    kind: &'static str,
    name: &'static str,
    terminal: Option<usize>,
    start: usize,
    end: usize,
}
pub struct Session<'a> {
    source: &'a str,
    options: Options,
    terminals: Arc<[Terminal]>,
    whitespace: bool,
    offsets: Vec<usize>,
    code_points: Vec<usize>,
    entries: BTreeMap<usize, Entry>,
    trivia_cache: HashMap<usize, usize>,
    frontier: usize,
    terminal_evaluations: u64,
    trivia_evaluations: u64,
    inventory_evaluations: u64,
}
impl<'a> Session<'a> {
    pub fn new(
        source: &'a str,
        options: Options,
        terminals: Arc<[Terminal]>,
        whitespace: bool,
    ) -> Result<Self, String> {
        let mut names = HashSet::new();
        for t in terminals.iter() {
            if !names.insert((t.literal, t.name)) || nullable(&t.expression) {
                return Err(format!(
                    "E-LEXING-TOKEN: duplicate or nullable terminal {}",
                    t.name
                ));
            }
        }
        let mut offsets: Vec<_> = source.char_indices().map(|(p, _)| p).collect();
        offsets.push(source.len());
        let mut code_points = vec![usize::MAX; source.len() + 1];
        for (cp, p) in offsets.iter().enumerate() {
            code_points[*p] = cp;
        }
        let mut result = Self {
            source,
            options,
            terminals,
            whitespace,
            offsets,
            code_points,
            entries: BTreeMap::new(),
            trivia_cache: HashMap::new(),
            frontier: 0,
            terminal_evaluations: 0,
            trivia_evaluations: 0,
            inventory_evaluations: 0,
        };
        if options.mode == Mode::TokensEager {
            result.scan_through(source.len(), false);
        }
        Ok(result)
    }
    pub fn source(&self) -> &'a str {
        self.source
    }
    pub fn options(&self) -> Options {
        self.options
    }
    pub fn metrics(&self) -> Metrics {
        Metrics {
            terminal_evaluations: self.terminal_evaluations,
            trivia_evaluations: self.trivia_evaluations,
            inventory_evaluations: self.inventory_evaluations,
            retained_entries: self.entries.len() + self.trivia_cache.len(),
            source_code_points: self.offsets.len() - 1,
        }
    }
    fn token_mode(&self) -> bool {
        matches!(self.options.mode, Mode::TokensEager | Mode::TokensLazy)
    }
    pub(crate) fn match_at(
        &mut self,
        name: &str,
        literal: bool,
        expression: Option<&LexicalExpression>,
        p: usize,
    ) -> Option<usize> {
        if !self.token_mode() {
            self.terminal_evaluations += 1;
            return if let Some(expression) = expression {
                expression.match_at(self.source, p)
            } else {
                self.source[p..].starts_with(name).then_some(p + name.len())
            };
        }
        if literal && name.is_empty() {
            return Some(p);
        }
        self.scan_through(p, false);
        let entry = self.entries.get(&p)?;
        let terminal = &self.terminals[entry.terminal?];
        (terminal.literal == literal && terminal.name == name).then_some(entry.end)
    }
    pub(crate) fn skip(&mut self, position: usize) -> usize {
        if !self.whitespace {
            return position;
        }
        if self.token_mode() {
            let mut end = position;
            while end < self.source.len() {
                self.scan_through(end, false);
                let Some(entry) = self.entries.get(&end).filter(|e| is_trivia(e.kind)) else {
                    break;
                };
                end = entry.end;
            }
            return end;
        }
        if self.options.mode == Mode::TriviaCache {
            if let Some(end) = self.trivia_cache.get(&position) {
                return *end;
            }
        }
        let mut end = position;
        while end < self.source.len() {
            self.trivia_evaluations += 1;
            let Some(entry) = self.trivia(end) else { break };
            end = entry.end;
        }
        if self.options.mode == Mode::TriviaCache {
            self.trivia_cache.insert(position, end);
        }
        end
    }
    fn scan_through(&mut self, position: usize, inventory: bool) {
        while self.frontier <= position && self.frontier < self.source.len() {
            let trivia = if self.whitespace {
                if inventory {
                    self.inventory_evaluations += 1
                } else {
                    self.trivia_evaluations += 1
                }
                self.trivia(self.frontier)
            } else {
                None
            };
            let entry = trivia.unwrap_or_else(|| {
                let mut best = None;
                let mut end = self.frontier;
                for (i, terminal) in self.terminals.iter().enumerate() {
                    if inventory {
                        self.inventory_evaluations += 1
                    } else {
                        self.terminal_evaluations += 1
                    }
                    if let Some(next) = terminal
                        .expression
                        .match_at(self.source, self.frontier)
                        .filter(|next| *next > end)
                    {
                        best = Some(i);
                        end = next;
                    }
                }
                if let Some(index) = best {
                    Entry {
                        kind: "token",
                        name: self.terminals[index].name,
                        terminal: best,
                        start: self.frontier,
                        end,
                    }
                } else {
                    Entry {
                        kind: "error",
                        name: "",
                        terminal: None,
                        start: self.frontier,
                        end: self.frontier
                            + self.source[self.frontier..]
                                .chars()
                                .next()
                                .unwrap()
                                .len_utf8(),
                    }
                }
            });
            self.frontier = entry.end;
            self.entries.insert(entry.start, entry);
        }
    }
    fn trivia(&self, p: usize) -> Option<Entry> {
        let mut end = p;
        while self
            .source
            .as_bytes()
            .get(end)
            .is_some_and(|b| matches!(b, b' ' | b'\t' | b'\r' | b'\n' | 11 | 12))
        {
            end += 1;
        }
        let kind = if end > p {
            "space"
        } else if self.source[p..].starts_with("//") {
            end = p + self.source[p..]
                .find(['\r', '\n'])
                .unwrap_or(self.source.len() - p);
            "lineComment"
        } else if self.source[p..].starts_with("/*") {
            end = p + 4 + self.source[p + 2..].find("*/")?;
            "blockComment"
        } else {
            return None;
        };
        Some(Entry {
            kind,
            name: "",
            terminal: None,
            start: p,
            end,
        })
    }
    /// Materializes the inventory, including the unparsed suffix; extra work has its own counter.
    pub fn lexemes(&mut self) -> Vec<Lexeme> {
        self.scan_through(self.source.len(), true);
        self.entries
            .values()
            .filter(|e| self.options.preserve_trivia || !is_trivia(e.kind))
            .map(|e| Lexeme {
                kind: e.kind,
                name: e.name,
                span: Span {
                    start: self.code_points[e.start],
                    end: self.code_points[e.end],
                },
            })
            .collect()
    }
    pub fn text(&self, lexeme: &Lexeme) -> &'a str {
        &self.source[self.offsets[lexeme.span.start]..self.offsets[lexeme.span.end]]
    }
}
fn is_trivia(kind: &str) -> bool {
    kind != "token" && kind != "error"
}
fn nullable(e: &LexicalExpression) -> bool {
    match e.op {
        Op::LITERAL => e.text.is_empty(),
        Op::ANY | Op::RANGE | Op::EXCEPT => false,
        Op::EOF | Op::BOF | Op::BOL | Op::EOL | Op::LOOK | Op::NOT | Op::BACKREF => true,
        Op::SEQUENCE => e.children.iter().all(nullable),
        Op::CHOICE => e.children.iter().any(nullable),
        Op::REPEAT => e.min == 0 || nullable(&e.children[0]),
        Op::CAPTURE | Op::SCOPE => nullable(&e.children[0]),
    }
}
pub fn parse<'a>(
    grammar: &SharedGrammar,
    root: usize,
    whitespace: bool,
    source: &'a str,
    options: Options,
    terminals: Arc<[Terminal]>,
) -> Result<Outcome<'a>, String> {
    let mut context = ParseContext::with_options(
        source,
        ParseOptions::default().with_diagnostics(Diagnostics::Detailed),
    );
    context.lexing = Some(Session::new(source, options, terminals, whitespace)?);
    let parsed = context.parse_shared_grammar(grammar, root, whitespace);
    let root_node = parsed.as_ref().ok().and_then(|p| p.root_node());
    let consumed = context.position();
    let matched = context.matched_position();
    let complete = root_node.is_some() && consumed == source.chars().count();
    let error = context.failure();
    let trailing = root_node.is_some() && !complete;
    let recoveries = root_node
        .map(|root| context.selected_recoveries(root))
        .unwrap_or_default();
    let tree = root_node.map(|root| Tree {
        source: source.to_owned(),
        nodes: std::mem::take(&mut context.nodes),
        root,
        byte_offsets: std::mem::take(&mut context.byte_offsets),
        scopes: std::mem::take(&mut context.scopes),
        recoveries,
    });
    Ok(Outcome {
        tree,
        succeeded: complete,
        consumed,
        matched,
        farthest: if trailing { consumed } else { error.offset },
        expected: if trailing {
            vec!["end of input".into()]
        } else {
            error.expected
        },
        session: context.lexing.take().unwrap(),
    })
}
