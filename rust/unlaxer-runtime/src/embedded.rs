//! Bounded recursive calls from generated grammar declarations and committed CST captures.
use crate::source::{
    Kind, Language, LanguageRegions, Location, Region, Result, Segment, Snapshot, SourceMap, State,
};
use crate::{SharedGrammar, Span};
use std::collections::HashMap;

#[derive(Debug, Clone)]
pub struct Child {
    pub language: Language,
    pub full: Span,
    pub body: Span,
}
#[derive(Debug, Clone)]
pub struct Parsed {
    pub snapshot: Snapshot,
    pub state: State,
    pub children: Vec<Child>,
}
pub trait Grammar {
    fn name(&self) -> &str;
    fn parse(&self, entry: &str, snapshot: &Snapshot) -> Result<Parsed>;
}
#[derive(Debug, Clone)]
pub struct Output {
    pub snapshot: Snapshot,
    pub regions: Vec<Region>,
}
impl Output {
    pub fn tree(&self) -> Result<LanguageRegions> {
        LanguageRegions::new(self.snapshot.clone(), self.regions.clone())
    }
}
pub fn parse(
    snapshot: &Snapshot,
    language: &Language,
    providers: &HashMap<Language, &dyn Grammar>,
    maximum_depth: usize,
    maximum_regions: usize,
) -> Result<Output> {
    if !(1..=64).contains(&maximum_depth) || !(1..=10000).contains(&maximum_regions) {
        return Err("invalid embedding budget");
    }
    let mut output = Output {
        snapshot: snapshot.clone(),
        regions: vec![],
    };
    let span = Span {
        start: 0,
        end: snapshot.len(),
    };
    let mut traversal = Traversal {
        host: snapshot,
        providers,
        maximum_depth,
        maximum_regions,
        regions: &mut output.regions,
    };
    traversal.visit(snapshot, language, "root".into(), None, span, span, 1)?;
    output.tree()?;
    Ok(output)
}
struct Traversal<'a> {
    host: &'a Snapshot,
    providers: &'a HashMap<Language, &'a dyn Grammar>,
    maximum_depth: usize,
    maximum_regions: usize,
    regions: &'a mut Vec<Region>,
}
impl Traversal<'_> {
    #[allow(clippy::too_many_arguments)]
    fn visit(
        &mut self,
        input: &Snapshot,
        language: &Language,
        id: String,
        parent: Option<String>,
        full: Span,
        body: Span,
        depth: usize,
    ) -> Result<()> {
        if depth > self.maximum_depth || self.regions.len() >= self.maximum_regions {
            return Err("embedding budget exceeded");
        }
        if [
            &language.id,
            &language.package_id,
            &language.version,
            &language.grammar,
            &language.entry,
        ]
        .iter()
        .any(|v| v.is_empty())
        {
            return Err("empty language identity");
        }
        let parsed = if let Some(grammar) = self.providers.get(language) {
            if grammar.name() != language.grammar {
                return Err("grammar identity mismatch");
            }
            grammar.parse(&language.entry, input)?
        } else {
            Parsed {
                snapshot: input.clone(),
                state: State::Unavailable,
                children: vec![],
            }
        };
        if &parsed.snapshot != input {
            return Err("stale grammar response");
        }
        if !matches!(parsed.state, State::Complete | State::Partial) && !parsed.children.is_empty()
        {
            return Err("failed parse cannot provide child regions");
        }
        for (index, child) in parsed.children.iter().enumerate() {
            input.check(child.full)?;
            input.check(child.body)?;
            if child.full.start > child.body.start || child.body.end > child.full.end {
                return Err("body outside embedding");
            }
            if parsed.children[..index]
                .iter()
                .any(|other| child.full.start < other.full.end && other.full.start < child.full.end)
            {
                return Err("overlapping embedded siblings");
            }
        }
        let segments = if input.is_empty() {
            vec![]
        } else {
            vec![Segment {
                output: Span {
                    start: 0,
                    end: input.len(),
                },
                kind: Kind::Copy,
                origin: Some(Location::new(self.host.clone(), body)?),
            }]
        };
        self.regions.push(Region {
            id: id.clone(),
            parent,
            language: language.clone(),
            full,
            body,
            source_map: SourceMap::new(input.clone(), segments)?,
            parse_state: parsed.state,
        });
        for (index, child) in parsed.children.iter().enumerate() {
            input.check(child.full)?;
            input.check(child.body)?;
            if child.full.start > child.body.start || child.body.end > child.full.end {
                return Err("body outside embedding");
            }
            let child_id = format!("{id}/{index}");
            let snapshot = Snapshot::new(
                format!("{}#embedded/{child_id}", self.host.uri),
                self.host.version,
                input.slice(child.body)?,
            )?;
            let shift = |span: Span| Span {
                start: body.start + span.start,
                end: body.start + span.end,
            };
            self.visit(
                &snapshot,
                &child.language,
                child_id,
                Some(id.clone()),
                shift(child.full),
                shift(child.body),
                depth + 1,
            )?;
        }
        Ok(())
    }
}
#[derive(Debug, Clone)]
pub struct Binding {
    pub rule: usize,
    pub capture: String,
    pub language: Language,
}
/// The generated parser graph and rule indices; no grammar text is re-parsed at runtime.
pub struct CstGrammar {
    pub name: String,
    pub grammar: SharedGrammar,
    pub entries: HashMap<String, usize>,
    pub bindings: Vec<Binding>,
    pub whitespace: bool,
}
impl Grammar for CstGrammar {
    fn name(&self) -> &str {
        &self.name
    }
    fn parse(&self, entry: &str, snapshot: &Snapshot) -> Result<Parsed> {
        let Some(&root) = self.entries.get(entry) else {
            return Ok(Parsed {
                snapshot: snapshot.clone(),
                state: State::Unsupported,
                children: vec![],
            });
        };
        let Ok(tree) = crate::parse(&self.grammar, root, self.whitespace, &snapshot.text) else {
            return Ok(Parsed {
                snapshot: snapshot.clone(),
                state: State::Failed,
                children: vec![],
            });
        };
        let mut children = vec![];
        self.discover(&tree, tree.root, &mut children)?;
        Ok(Parsed {
            snapshot: snapshot.clone(),
            state: if tree.recoveries().is_empty() {
                State::Complete
            } else {
                State::Partial
            },
            children,
        })
    }
}
impl CstGrammar {
    fn discover(&self, tree: &crate::Tree, index: usize, children: &mut Vec<Child>) -> Result<()> {
        let node = &tree.nodes[index];
        for binding in &self.bindings {
            if node.rule == binding.rule {
                let bodies: Vec<_> = node
                    .captures
                    .iter()
                    .filter(|c| c.name == binding.capture)
                    .collect();
                if bodies.len() != 1 {
                    return Err("embedding needs exactly one body capture");
                }
                children.push(Child {
                    language: binding.language.clone(),
                    full: node.span,
                    body: bodies[0].span,
                });
                return Ok(());
            }
        }
        for &child in &node.children {
            self.discover(tree, child, children)?;
        }
        Ok(())
    }
}
