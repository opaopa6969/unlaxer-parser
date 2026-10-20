//! Rule stacks of the final syntax error (#703).
//!
//! Recorded only with [`Diagnostics::Detailed`]: every other policy keeps the
//! historical hot path, which never pushes a rule frame. The contract, shared with
//! Java `org.unlaxer.context.RuleStack`, is described in `docs/rule-stack-diagnostics.md`.
//!
//! The farthest failure's stack is chosen among *failed rule invocations*, not among
//! individual terminal failures: Java records failures inside composite tokens and
//! whitespace delimiters that Rust's atomic tokens and trivia never record, but both
//! evaluate the same rule invocations. A failed invocation is a candidate at its reach,
//! the farthest failure recorded while it was active; the candidate with the farthest
//! reach wins, then the one with more rule frames, then the earliest.
use crate::{Diagnostics, ParseContext, ParseDiagnostic, Span};
use std::rc::Rc;

/// Frames kept in a reported [`RuleStack`]; deeper stacks keep their innermost frames.
pub const RULE_STACK_LIMIT: usize = 32;

/// One active grammar rule: its name and the code point where it started.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuleFrame {
    pub rule: &'static str,
    /// Code-point offset into the original input where the rule started.
    pub start: usize,
}

/// The rules active at the final error, outermost first.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuleStack {
    /// Code-point offset of the failure the stack explains.
    pub offset: usize,
    /// The innermost [`RULE_STACK_LIMIT`] frames, outermost first.
    pub frames: Vec<RuleFrame>,
    /// Number of outer frames dropped because the stack was deeper than the limit.
    pub omitted: usize,
}

impl RuleStack {
    /// Total nesting depth at the failure, including omitted frames.
    pub fn depth(&self) -> usize {
        self.omitted + self.frames.len()
    }

    /// Original code-point span of a frame: from its start to the failure.
    pub fn span(&self, frame: &RuleFrame) -> Span {
        Span {
            start: frame.start,
            end: self.offset.max(frame.start),
        }
    }

    fn bounded(offset: usize, mut frames: Vec<RuleFrame>) -> Option<Self> {
        if frames.is_empty() {
            return None;
        }
        let omitted = frames.len().saturating_sub(RULE_STACK_LIMIT);
        frames.drain(..omitted);
        Some(Self {
            offset,
            frames,
            omitted,
        })
    }
}

/// A full-input diagnostic together with the rule stack of its failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuleStackDiagnostic {
    pub diagnostic: ParseDiagnostic,
    /// The stack of the raised failure or of the farthest failure; `None` without a
    /// failed rule at that failure, for resource limits and name-resolution failures.
    /// Compare [`RuleStack::offset`] with the offset you report before showing it.
    pub rule_stack: Option<RuleStack>,
}

/// Immutable, shared snapshot node; `start` is a byte offset.
#[derive(Debug)]
pub(crate) struct RuleNode {
    name: &'static str,
    start: usize,
    depth: usize,
    parent: Option<Rc<RuleNode>>,
}

/// A rule-stack snapshot. Snapshots share their outer frames.
#[derive(Debug, Clone, Default)]
pub(crate) struct RuleTrace(Option<Rc<RuleNode>>);

impl RuleTrace {
    pub(crate) fn depth(&self) -> usize {
        self.0.as_ref().map_or(0, |node| node.depth)
    }
}

/// A failed rule invocation selected inside a memo frame: its reach (byte offset) and its
/// stack, itself included. Memo entries share these snapshots.
#[derive(Debug, Clone)]
pub(crate) struct RuleCandidate {
    position: usize,
    trace: RuleTrace,
}

#[derive(Debug)]
struct ActiveRule {
    name: &'static str,
    start: usize,
    // Farthest failure (byte offset) recorded while this invocation was active.
    reach: Option<usize>,
    // Snapshot node of the stack up to this frame, built on first use by a memo frame.
    // Frames never change their name or start while active, so a built node stays valid.
    node: Option<Rc<RuleNode>>,
}

/// The selected failed rule invocation of the whole parse. The frames buffer is reused, so
/// replacing the candidate copies the active frames without allocating once it has grown.
#[derive(Debug, Clone, Default)]
struct Selected {
    position: Option<usize>,
    // (name, byte start), outermost first.
    frames: Vec<(&'static str, usize)>,
}

/// Parse-local rule-stack state. Empty unless diagnostics are detailed.
#[derive(Debug, Default)]
pub(crate) struct RuleStacks {
    active: Vec<ActiveRule>,
    selected: Selected,
    // One slot per open assertion: the selection before it, saved only once the assertion
    // first replaces it, so an assertion that selects nothing copies nothing.
    backups: Vec<Option<Selected>>,
    /// Rules a raised failure has propagated out of, innermost first, as (name, byte start).
    pub(crate) raised: Vec<(&'static str, usize)>,
    /// Outcome of the last outermost parse; the rule stack explains failed parses only.
    pub(crate) parse_succeeded: bool,
}

/// Raised failure retained by a failure memo entry, with the rules it left inside the rule.
#[derive(Debug)]
pub(crate) struct RaisedMemo {
    pub(crate) failure: crate::RaisedFailure,
    pub(crate) rules: Box<[(&'static str, usize)]>,
}

/// An open assertion over the rule-stack selection.
#[must_use]
pub(crate) struct RuleSpeculation {
    level: usize,
    reach: Option<usize>,
}

/// An open shared grammar call and the caller's selection it set aside.
#[must_use]
pub(crate) struct RuleCall {
    level: usize,
    reach: Option<usize>,
    caller: Selected,
}

/// Whether a failed invocation reaching `position` with `depth` rule frames replaces a
/// recorded one reaching `recorded` with `recorded_depth`: a farther reach always does, an
/// equal reach only with strictly more frames, so the earliest of the deepest wins.
fn replaces(position: usize, depth: usize, recorded: Option<(usize, usize)>) -> bool {
    recorded.is_none_or(|(recorded, recorded_depth)| {
        position > recorded || position == recorded && depth > recorded_depth
    })
}

impl RuleCandidate {
    fn key(candidate: Option<&Self>) -> Option<(usize, usize)> {
        candidate.map(|candidate| (candidate.position, candidate.trace.depth()))
    }

    /// Takes `other` where it is selected over `target` (memo frame merging).
    pub(crate) fn merge_into(target: &mut Option<Self>, other: Option<&Self>) {
        if let Some(other) = other {
            if replaces(
                other.position,
                other.trace.depth(),
                Self::key(target.as_ref()),
            ) {
                *target = Some(other.clone());
            }
        }
    }
}

impl Selected {
    fn key(&self) -> Option<(usize, usize)> {
        self.position.map(|position| (position, self.frames.len()))
    }
}

impl ParseContext<'_> {
    pub(crate) fn rule_stack_tracked(&self) -> bool {
        self.options.diagnostics == Diagnostics::Detailed
    }

    pub(crate) fn enter_rule_frame(&mut self, id: usize) {
        let name = self.rules[id].name;
        let start = self.frame_start(self.position);
        self.rule_stacks.active.push(ActiveRule {
            name,
            start,
            reach: None,
            node: None,
        });
    }

    /// Ends the innermost rule invocation. A failed one is a candidate at its reach; a
    /// raised failure leaving it adds it to the raised stack. The reach carries over to
    /// the enclosing invocation, which was active as well.
    pub(crate) fn leave_rule_frame(&mut self, failed: bool) {
        if failed {
            if let Some(reach) = self.rule_stacks.active.last().and_then(|frame| frame.reach) {
                self.offer_rule_candidate(reach);
            }
        }
        let frame = self
            .rule_stacks
            .active
            .pop()
            .expect("rule frames are balanced");
        if failed && self.raised.is_some() {
            self.rule_stacks.raised.push((frame.name, frame.start));
        }
        if let Some(reach) = frame.reach {
            self.note_failure_reach(reach);
        }
    }

    fn offer_rule_candidate(&mut self, reach: usize) {
        let depth = self.active_rule_depth();
        if replaces(reach, depth, self.rule_stacks.selected.key()) {
            self.save_rule_selection();
            let stacks = &mut self.rule_stacks;
            stacks.selected.position = Some(reach);
            stacks.selected.frames.clear();
            stacks
                .selected
                .frames
                .extend(stacks.active.iter().map(|frame| (frame.name, frame.start)));
        }
        if self
            .diagnostic_frames
            .last()
            .is_some_and(|frame| replaces(reach, depth, RuleCandidate::key(frame.rules.as_ref())))
        {
            let trace = self.rule_snapshot();
            if let Some(diagnostic) = self.diagnostic_frames.last_mut() {
                diagnostic.rules = Some(RuleCandidate {
                    position: reach,
                    trace,
                });
            }
        }
    }

    /// Saves the selection for the innermost open assertion before it is first replaced.
    fn save_rule_selection(&mut self) {
        let stacks = &mut self.rule_stacks;
        if let Some(slot @ None) = stacks.backups.last_mut() {
            *slot = Some(stacks.selected.clone());
        }
    }

    /// Records a failure at `position` (byte offset) in the innermost active rule.
    #[inline]
    pub(crate) fn note_failure_reach(&mut self, position: usize) {
        if let Some(frame) = self.rule_stacks.active.last_mut() {
            if frame.reach.is_none_or(|reach| position > reach) {
                frame.reach = Some(position);
            }
        }
    }

    pub(crate) fn active_rule_depth(&self) -> usize {
        self.rule_stacks.active.len()
    }

    /// Opens an assertion whose diagnostics may be discarded.
    pub(crate) fn begin_rule_speculation(&mut self) -> RuleSpeculation {
        if !self.rule_stack_tracked() {
            // Nothing is selected without detailed diagnostics; level 0 closes as a no-op.
            return RuleSpeculation {
                level: 0,
                reach: None,
            };
        }
        self.rule_stacks.backups.push(None);
        RuleSpeculation {
            level: self.rule_stacks.backups.len(),
            reach: self.rule_stacks.active.last().and_then(|frame| frame.reach),
        }
    }

    /// Closes an assertion. A discarded one restores the selection and the active rule's
    /// reach, as its other diagnostics are restored; a kept one hands its saved selection
    /// to the enclosing assertion, which has not saved one if nothing changed before.
    pub(crate) fn end_rule_speculation(&mut self, speculation: RuleSpeculation, discard: bool) {
        if speculation.level == 0 {
            return;
        }
        let stacks = &mut self.rule_stacks;
        assert_eq!(
            stacks.backups.len(),
            speculation.level,
            "assertions are balanced"
        );
        let saved = stacks.backups.pop().flatten();
        if discard {
            if let Some(saved) = saved {
                stacks.selected = saved;
            }
            if let Some(frame) = stacks.active.last_mut() {
                frame.reach = speculation.reach;
            }
        } else if let (Some(saved), Some(slot @ None)) = (saved, stacks.backups.last_mut()) {
            *slot = Some(saved);
        }
    }

    /// Opens a shared grammar call, which selects its own candidate from scratch. The
    /// enclosing assertion, if any, saves the caller's selection first.
    pub(crate) fn begin_rule_call(&mut self) -> RuleCall {
        self.save_rule_selection();
        let stacks = &mut self.rule_stacks;
        let caller = std::mem::take(&mut stacks.selected);
        // The call's own slot keeps later replacements from saving the empty selection.
        stacks.backups.push(Some(Selected::default()));
        RuleCall {
            level: stacks.backups.len(),
            reach: stacks.active.last().and_then(|frame| frame.reach),
            caller,
        }
    }

    /// Ends a shared grammar call: a successful call discards what it recorded, a failed
    /// one keeps its candidate where it is selected over the caller's earlier one.
    pub(crate) fn end_rule_call(&mut self, call: RuleCall, succeeded: bool) {
        let stacks = &mut self.rule_stacks;
        assert_eq!(
            stacks.backups.len(),
            call.level,
            "shared calls are balanced"
        );
        stacks.backups.pop();
        let keep_call = !succeeded
            && stacks
                .selected
                .key()
                .is_some_and(|(position, depth)| replaces(position, depth, call.caller.key()));
        if !keep_call {
            stacks.selected = call.caller;
        }
        if succeeded {
            if let Some(frame) = stacks.active.last_mut() {
                frame.reach = call.reach;
            }
        }
    }

    /// Snapshot of the active rules for a memo frame, sharing every node already built.
    fn rule_snapshot(&mut self) -> RuleTrace {
        let active = &mut self.rule_stacks.active;
        let mut built = active.len();
        while built > 0 && active[built - 1].node.is_none() {
            built -= 1;
        }
        let mut parent = built
            .checked_sub(1)
            .and_then(|index| active[index].node.clone());
        for frame in &mut active[built..] {
            let node = Rc::new(RuleNode {
                name: frame.name,
                start: frame.start,
                depth: parent.as_ref().map_or(1, |parent| parent.depth + 1),
                parent,
            });
            frame.node = Some(Rc::clone(&node));
            parent = Some(node);
        }
        RuleTrace(parent)
    }

    /// Frames of a memoized trace deeper than the memo frame's base, outermost first: the
    /// rule-local part, which follows the active frames when the entry is replayed.
    fn rule_local_frames(trace: &RuleTrace, base: usize) -> Vec<(&'static str, usize)> {
        let mut suffix = vec![];
        let mut node = trace.0.as_ref();
        while let Some(current) = node {
            if current.depth <= base {
                break;
            }
            suffix.push((current.name, current.start));
            node = current.parent.as_ref();
        }
        suffix.reverse();
        suffix
    }

    /// Replays a memoized failure into the rule stacks: its farthest failure belongs to
    /// the active rule, and its selected candidate is rebased onto the active rules where
    /// it is selected, so the stack does not depend on which caller first evaluated the rule.
    /// Call before the memoized diagnostic is merged anywhere.
    pub(crate) fn replay_rule_failure(&mut self, diagnostic: &crate::FailureDiagnostic) {
        if let Some(position) = diagnostic.farthest {
            self.note_failure_reach(position);
        }
        let Some(candidate) = diagnostic.rules.as_ref() else {
            return;
        };
        let depth = self.active_rule_depth()
            + candidate
                .trace
                .depth()
                .saturating_sub(diagnostic.rules_base);
        let global = replaces(candidate.position, depth, self.rule_stacks.selected.key());
        let frame = self.diagnostic_frames.last().is_some_and(|frame| {
            replaces(
                candidate.position,
                depth,
                RuleCandidate::key(frame.rules.as_ref()),
            )
        });
        if !global && !frame {
            return;
        }
        let local = Self::rule_local_frames(&candidate.trace, diagnostic.rules_base);
        if global {
            self.save_rule_selection();
            let stacks = &mut self.rule_stacks;
            stacks.selected.position = Some(candidate.position);
            stacks.selected.frames.clear();
            stacks
                .selected
                .frames
                .extend(stacks.active.iter().map(|frame| (frame.name, frame.start)));
            stacks.selected.frames.extend_from_slice(&local);
        }
        if frame {
            let mut parent = self.rule_snapshot().0;
            for (name, start) in local {
                parent = Some(Rc::new(RuleNode {
                    name,
                    start,
                    depth: parent.as_ref().map_or(1, |parent| parent.depth + 1),
                    parent,
                }));
            }
            if let Some(active) = self.diagnostic_frames.last_mut() {
                active.rules = Some(RuleCandidate {
                    position: candidate.position,
                    trace: RuleTrace(parent),
                });
            }
        }
    }

    /// The rule stack of the final error of a failed parse with [`Diagnostics::Detailed`].
    ///
    /// For a committed or labeled failure (#700) it is the stack where the raising
    /// operator ran. Otherwise it is the stack of the failed rule invocation that reached
    /// the farthest failure: the one with the most rule frames, the earliest on ties.
    /// `None` after a successful parse (see [`Self::unconsumed_input_rule_stack`]), with
    /// other diagnostic policies, for resource limits and name-resolution failures, and
    /// when no failed rule reached the farthest failure.
    pub fn rule_stack(&self) -> Option<RuleStack> {
        if self.rule_stacks.parse_succeeded {
            return None;
        }
        self.final_rule_stack()
    }

    /// For a successful parse that did not consume the whole input: the rule stack a
    /// full-input parse reports for the unconsumed input, as Java's
    /// `ParseContext#getUnconsumedInputRuleStack()` does.
    pub fn unconsumed_input_rule_stack(&self) -> Option<RuleStack> {
        // The unconsumed input is a failure at the cursor, outside every rule: past the
        // farthest failure it leaves no failed rule at the error.
        if !self.rule_stacks.parse_succeeded
            || self.position >= self.input.len()
            || self.position > self.farthest
        {
            return None;
        }
        self.final_rule_stack()
    }

    /// Records the outcome of an outermost parse entry.
    pub(crate) fn finish_outermost_parse(&mut self, succeeded: bool) {
        if self.call_depth == 0 {
            self.rule_stacks.parse_succeeded = succeeded;
        }
    }

    pub(crate) fn final_rule_stack(&self) -> Option<RuleStack> {
        if !self.rule_stack_tracked()
            || self.resource_failure.is_some()
            || self.name_failure().is_some()
        {
            return None;
        }
        if let Some(raised) = &self.raised {
            let frames = self
                .rule_stacks
                .raised
                .iter()
                .rev()
                .map(|&(rule, start)| RuleFrame {
                    rule,
                    start: self.code_point(start),
                })
                .collect();
            return RuleStack::bounded(raised.offset, frames);
        }
        let selected = &self.rule_stacks.selected;
        let position = selected
            .position
            .filter(|&position| position == self.farthest)?;
        let frames = selected
            .frames
            .iter()
            .map(|&(rule, start)| RuleFrame {
                rule,
                start: self.code_point(start),
            })
            .collect();
        RuleStack::bounded(self.code_point(position), frames)
    }
}
