use super::*;

#[derive(Debug, Clone)]
pub(super) enum Instruction {
    Accept,
    Scalar(Set, usize),
    Assert(Anchor, Flags, usize),
    Split(usize, usize),
    AtomicEnter(usize),
    AtomicExit(usize),
    RepeatEnter {
        body: usize,
        next: usize,
        min: usize,
        max: Option<usize>,
        reluctant: bool,
    },
    RepeatEnd(usize),
}
pub(super) fn compile(node: Node, limits: CompileLimits) -> Result<Regex, CompileError> {
    if limits.max_instructions == 0 {
        return Err(CompileError {
            kind: CompileErrorKind::ResourceLimit,
            offset: 0,
            message: "compiled instruction limit",
        });
    }
    let mut program = vec![Instruction::Accept];
    fn add(
        program: &mut Vec<Instruction>,
        op: Instruction,
        limits: CompileLimits,
    ) -> Result<usize, CompileError> {
        if program.len() >= limits.max_instructions {
            return Err(CompileError {
                kind: CompileErrorKind::ResourceLimit,
                offset: 0,
                message: "compiled instruction limit",
            });
        }
        let at = program.len();
        program.push(op);
        Ok(at)
    }
    fn emit(
        node: Node,
        next: usize,
        p: &mut Vec<Instruction>,
        limits: CompileLimits,
    ) -> Result<usize, CompileError> {
        Ok(match node {
            Node::Empty => next,
            Node::Set(set) => add(p, Instruction::Scalar(set, next), limits)?,
            Node::Anchor(anchor, flags) => {
                add(p, Instruction::Assert(anchor, flags, next), limits)?
            }
            Node::Sequence(nodes) => {
                let mut at = next;
                for n in nodes.into_iter().rev() {
                    at = emit(n, at, p, limits)?;
                }
                at
            }
            Node::Choice(nodes) => {
                let mut choices = nodes.into_iter().rev();
                let mut at = emit(choices.next().unwrap(), next, p, limits)?;
                for child in choices {
                    let first = emit(child, next, p, limits)?;
                    at = add(p, Instruction::Split(first, at), limits)?;
                }
                at
            }
            Node::Repeat {
                body,
                min,
                max,
                reluctant,
            } => {
                let start = add(p, Instruction::Accept, limits)?;
                let end = add(p, Instruction::RepeatEnd(start), limits)?;
                let body = emit(*body, end, p, limits)?;
                p[start] = Instruction::RepeatEnter {
                    body,
                    next,
                    min,
                    max,
                    reluctant,
                };
                start
            }
            Node::Atomic(body) => {
                let exit = add(p, Instruction::AtomicExit(next), limits)?;
                let body = emit(*body, exit, p, limits)?;
                add(p, Instruction::AtomicEnter(body), limits)?
            }
        })
    }
    let start = emit(node, 0, &mut program, limits)?;
    Ok(Regex { program, start })
}

#[derive(Clone)]
struct RepeatFrame {
    instruction: usize,
    count: usize,
    start: usize,
}
#[derive(Clone)]
struct State {
    pc: usize,
    position: usize,
    repeats: Vec<RepeatFrame>,
    cuts: Vec<usize>,
}
pub(super) fn execute(
    regex: &Regex,
    input: &[char],
    limits: MatchLimits,
) -> Result<Option<usize>, MatchError> {
    let mut state = State {
        pc: regex.start,
        position: 0,
        repeats: Vec::new(),
        cuts: Vec::new(),
    };
    let mut backtrack: Vec<State> = Vec::new();
    let mut steps = 0;
    fn save(stack: &mut Vec<State>, state: State, limits: MatchLimits) -> Result<(), MatchError> {
        if stack.len() >= limits.max_backtracking_states {
            return Err(MatchError::BacktrackingLimit);
        }
        stack.push(state);
        Ok(())
    }
    loop {
        if steps >= limits.max_steps {
            return Err(MatchError::StepLimit);
        }
        steps += 1;
        let failed = match &regex.program[state.pc] {
            Instruction::Accept => return Ok(Some(state.position)),
            Instruction::Scalar(set, next) => {
                if input.get(state.position).is_some_and(|c| set.contains(*c)) {
                    state.position += 1;
                    state.pc = *next;
                    false
                } else {
                    true
                }
            }
            Instruction::Assert(anchor, flags, next) => {
                if assertion(*anchor, *flags, input, state.position) {
                    state.pc = *next;
                    false
                } else {
                    true
                }
            }
            Instruction::Split(first, second) => {
                let mut other = state.clone();
                other.pc = *second;
                save(&mut backtrack, other, limits)?;
                state.pc = *first;
                false
            }
            Instruction::AtomicEnter(next) => {
                state.cuts.push(backtrack.len());
                state.pc = *next;
                false
            }
            Instruction::AtomicExit(next) => {
                backtrack.truncate(state.cuts.pop().unwrap());
                state.pc = *next;
                false
            }
            Instruction::RepeatEnter {
                body,
                next,
                min,
                max,
                reluctant,
            } => {
                if state
                    .repeats
                    .last()
                    .is_none_or(|r| r.instruction != state.pc)
                {
                    state.repeats.push(RepeatFrame {
                        instruction: state.pc,
                        count: 0,
                        start: state.position,
                    });
                }
                let frame = state.repeats.last_mut().unwrap();
                if max.is_some_and(|max| frame.count >= max) {
                    state.repeats.pop();
                    state.pc = *next;
                } else {
                    frame.start = state.position;
                    if frame.count >= *min {
                        let mut exit = state.clone();
                        exit.repeats.pop();
                        exit.pc = *next;
                        if *reluctant {
                            let mut repeat = state;
                            repeat.pc = *body;
                            save(&mut backtrack, repeat, limits)?;
                            state = exit;
                        } else {
                            save(&mut backtrack, exit, limits)?;
                            state.pc = *body;
                        }
                    } else {
                        state.pc = *body;
                    }
                }
                false
            }
            Instruction::RepeatEnd(start) => {
                let frame = state.repeats.last_mut().unwrap();
                frame.count += 1;
                let Instruction::RepeatEnter { next, min, .. } = regex.program[*start] else {
                    unreachable!()
                };
                // Empty iterations may satisfy a minimum, but cannot spin forever.
                if frame.start == state.position && frame.count >= min {
                    state.repeats.pop();
                    state.pc = next;
                } else {
                    state.pc = *start;
                }
                false
            }
        };
        if failed {
            if let Some(next) = backtrack.pop() {
                state = next;
            } else {
                return Ok(None);
            }
        }
    }
}
fn assertion(anchor: Anchor, flags: Flags, input: &[char], at: usize) -> bool {
    fn final_end(input: &[char], at: usize, unix: bool) -> bool {
        if at == input.len() {
            return true;
        }
        if !unix && input.get(at..) == Some(&['\r', '\n']) {
            return true;
        }
        at + 1 == input.len()
            && terminator(input[at], unix)
            && (unix || input[at] != '\n' || at == 0 || input[at - 1] != '\r')
    }
    match anchor {
        Anchor::AbsoluteBegin => at == 0,
        Anchor::AbsoluteEnd => at == input.len(),
        Anchor::Begin if !flags.multiline => at == 0,
        Anchor::Begin => {
            at < input.len()
                && (at == 0
                    || terminator(input[at - 1], flags.unix)
                        && (flags.unix || input[at - 1] != '\r' || input[at] != '\n'))
        }
        Anchor::End if !flags.multiline => final_end(input, at, flags.unix),
        Anchor::End => {
            at == input.len()
                || terminator(input[at], flags.unix)
                    && (flags.unix || input[at] != '\n' || at == 0 || input[at - 1] != '\r')
        }
        Anchor::FinalEnd => final_end(input, at, flags.unix),
    }
}
