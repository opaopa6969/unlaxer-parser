//! Bounded validation and per-call work accounting for generated CST mappers.
//! These limits cover graph traversal and requested text, not the input or returned AST allocation.
use crate::{Span, Tree};
use std::cell::Cell;
use std::ops::Deref;

pub const MAX_NODES: usize = 1_048_576;
pub const MAX_EDGES: usize = 8_388_608;
pub const MAX_DEPTH: usize = 256;
pub const MAX_WORK: usize = 8_388_608;
pub const MAX_TEXT_BYTES: usize = 268_435_456;

/// Private state of one generated mapping operation; never shared between calls or threads.
pub struct Context<'a> {
    tree: &'a Tree,
    costs: Vec<usize>,
    work: Cell<usize>,
    work_limit: usize,
    recovered: Vec<bool>,
    text_bytes: Cell<usize>,
}

impl<'a> Context<'a> {
    pub fn new(tree: &'a Tree, rule_count: usize) -> Result<Self, String> {
        Self::with_work_limit(tree, rule_count, MAX_WORK)
    }
    pub fn with_work_limit(
        tree: &'a Tree,
        rule_count: usize,
        work_limit: usize,
    ) -> Result<Self, String> {
        if tree.nodes.len() > MAX_NODES {
            return Err("E-MAPPING-LIMIT: arena nodes".into());
        }
        // Check sizes before allocating validation tables or walking attacker-controlled edges.
        let mut edges = 0usize;
        for node in &tree.nodes {
            edges = edges
                .saturating_add(node.children.len())
                .saturating_add(node.captures.len());
            if edges > MAX_EDGES {
                return Err("E-MAPPING-LIMIT: arena edges".into());
            }
            for capture in &node.captures {
                edges = edges.saturating_add(capture.nodes.len());
                if edges > MAX_EDGES {
                    return Err("E-MAPPING-LIMIT: arena edges".into());
                }
            }
        }
        tree.validate_structure()
            .map_err(|error| format!("E-MAPPING-CST: {error}"))?;
        let mut heights = vec![0usize; tree.nodes.len()];
        let mut costs = Vec::with_capacity(tree.nodes.len());
        let mut recovered = vec![false; tree.nodes.len()];
        let mut seen = vec![0usize; tree.nodes.len()];
        let mut search = Vec::new();
        let mut search_epoch = 0usize;
        let mut validation_work = 0usize;
        for (id, node) in tree.nodes.iter().enumerate() {
            if node.rule >= rule_count
                && !matches!(
                    node.rule,
                    crate::TEXT_VALUE_RULE
                        | crate::VALUE_BOUNDARY_RULE
                        | crate::RECOVERY_ERROR_RULE
                        | crate::TOKEN_LEAF_RULE
                        | crate::TOKEN_META_RULE
                )
                && crate::capture_tree::Kind::of_rule(node.rule).is_none()
            {
                return Err("E-MAPPING-CST: unknown rule".into());
            }
            let mut height = 1usize;
            let mut cost = 1 + node.children.len() + node.captures.len();
            for &child in &node.children {
                height = height.max(heights[child] + 1);
            }
            let mut direct = if node.captures.is_empty() {
                Vec::new()
            } else {
                node.children.clone()
            };
            direct.sort_unstable();
            direct.dedup();
            for capture in &node.captures {
                cost += capture.nodes.len();
                for &target in &capture.nodes {
                    height = height.max(heights[target] + 1);
                    if direct.binary_search(&target).is_ok() {
                        continue;
                    }
                    search_epoch += 1;
                    search.clear();
                    for &child in &direct {
                        if child >= target {
                            seen[child] = search_epoch;
                            search.push(child);
                        }
                    }
                    let mut found = false;
                    while let Some(child) = search.pop() {
                        validation_work += 1;
                        if validation_work > MAX_WORK {
                            return Err("E-MAPPING-LIMIT: capture reachability".into());
                        }
                        if child == target {
                            found = true;
                            break;
                        }
                        for &next in &tree.nodes[child].children {
                            validation_work += 1;
                            if validation_work > MAX_WORK {
                                return Err("E-MAPPING-LIMIT: capture reachability".into());
                            }
                            if next >= target && seen[next] != search_epoch {
                                seen[next] = search_epoch;
                                search.push(next);
                            }
                        }
                    }
                    if !found {
                        return Err("E-MAPPING-CST: capture is outside its original subtree".into());
                    }
                }
            }
            if height > MAX_DEPTH {
                return Err("E-MAPPING-LIMIT: projection depth".into());
            }
            recovered[id] = node.rule == crate::RECOVERY_ERROR_RULE
                || node
                    .children
                    .iter()
                    .chain(node.captures.iter().flat_map(|capture| &capture.nodes))
                    .any(|&child| recovered[child]);
            heights[id] = height;
            costs.push(cost);
        }
        Ok(Self {
            tree,
            costs,
            work: Cell::new(0),
            work_limit: work_limit.min(MAX_WORK),
            recovered,
            text_bytes: Cell::new(0),
        })
    }

    pub fn ensure_mappable(&self, root: usize) -> Result<(), String> {
        if *self
            .recovered
            .get(root)
            .ok_or("E-MAPPING-CST: invalid root")?
        {
            Err("cannot map recovered syntax".into())
        } else {
            Ok(())
        }
    }

    /// Charge actual projection visits, including repeated expansion of a valid DAG.
    pub fn visit(&self, id: usize) -> Result<(), String> {
        let cost = self.costs.get(id).ok_or("E-MAPPING-CST: invalid node")?;
        self.charge_work(*cost)
    }

    pub fn charge_work(&self, amount: usize) -> Result<(), String> {
        let work = self.work.get().saturating_add(amount);
        self.work.set(work);
        if work > self.work_limit {
            Err("E-MAPPING-LIMIT: projection work".into())
        } else {
            Ok(())
        }
    }

    /// Bounds aggregate requested UTF-8 text before generated code copies it.
    pub fn text(&self, span: Span) -> Result<&str, String> {
        if span.start > span.end {
            return Err("E-MAPPING-CST: invalid text span".into());
        }
        let start = *self
            .tree
            .byte_offsets
            .get(span.start)
            .ok_or("E-MAPPING-CST: invalid text span")?;
        let end = *self
            .tree
            .byte_offsets
            .get(span.end)
            .ok_or("E-MAPPING-CST: invalid text span")?;
        let text = self
            .tree
            .source
            .get(start..end)
            .ok_or("E-MAPPING-CST: invalid text span")?;
        self.charge_text_bytes(text.len())?;
        Ok(text)
    }

    pub fn charge_text_bytes(&self, amount: usize) -> Result<(), String> {
        let bytes = self.text_bytes.get().saturating_add(amount);
        self.text_bytes.set(bytes);
        if bytes > MAX_TEXT_BYTES {
            Err("E-MAPPING-LIMIT: requested text".into())
        } else {
            Ok(())
        }
    }
}

impl Deref for Context<'_> {
    type Target = Tree;
    fn deref(&self) -> &Tree {
        self.tree
    }
}
