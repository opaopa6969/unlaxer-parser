//! Cold syntax-only continuation after the recursive AST budget is exhausted.
//! Each token is consumed once. The explicit stack holds a constant number of
//! continuations per open construct (O(tokens) time/space); no nested AST is built.
use super::*;

#[derive(Clone, Copy)]
enum Task {
    Body,
    ChoiceTail,
    SequenceTail,
    Element,
    Atomic,
    Postfix,
    Capture,
    Close(char),
    Arguments,
    CommitMessage,
    CatchAlternative,
    CatchLabels,
}

impl Parser<'_> {
    // Keep the iterative machine and its locals out of the recursive body frame
    // (#805: Rust 1.85 debug must fit the caller's 2 MiB stack).
    #[cold]
    #[inline(never)]
    pub(super) fn recognize_limited_body(&mut self) -> Result<RuleBody> {
        let start = self.current().span;
        if self.pending_value.is_none() {
            self.pending_value = Some(diagnostic(
                self.source,
                start,
                DiagnosticKind::NestingLimit,
                "UBNF nesting limit exceeded",
            ));
        }
        self.recognize_body()?;
        // Only a bounded skeleton exists outside the machine. parse() must reject
        // it after recognizing the entire file, before expansion or returning an AST.
        Ok(RuleBody {
            alternatives: Vec::new(),
            span: self.span_from(start),
        })
    }

    fn recognize_body(&mut self) -> Result<()> {
        let mut stack = vec![Task::Body];
        while let Some(task) = stack.pop() {
            match task {
                Task::Body => {
                    if !self.element_ahead() {
                        return Err(self.error(
                            "expected nonempty rule sequence (use an EMPTY token for epsilon)",
                        ));
                    }
                    stack.extend([Task::ChoiceTail, Task::SequenceTail, Task::Element]);
                }
                Task::ChoiceTail => {
                    if self.eat('|') {
                        stack.push(Task::Body);
                    }
                }
                Task::SequenceTail => {
                    if self.element_ahead() {
                        stack.extend([Task::SequenceTail, Task::Element]);
                    }
                }
                Task::Element => {
                    self.typeof_prefix(self.current().span)?;
                    stack.extend([Task::Capture, Task::Postfix, Task::Atomic]);
                }
                Task::Atomic => self.recognize_atomic(&mut stack)?,
                Task::Postfix => {
                    if self.eat('%') {
                        stack.push(Task::Atomic);
                    } else if !(self.eat('+') || self.eat('?') || self.eat('*'))
                        && self.is('{')
                        && self
                            .tokens
                            .get(self.pos + 1)
                            .is_some_and(|t| matches!(t.kind, Kind::Number(_)))
                    {
                        self.pos += 1;
                        self.repeat_bound()?;
                        if self.eat(',') && !self.is('}') {
                            self.repeat_bound()?;
                        }
                        self.expect('}')?;
                    }
                }
                Task::Capture => {
                    if self.is('@')
                        && !(self.annotation_is("typeof")
                            && self
                                .tokens
                                .get(self.pos + 2)
                                .is_some_and(|t| t.kind == Kind::Symbol('(')))
                    {
                        self.pos += 1;
                        self.identifier()?;
                    }
                }
                Task::Close(close) => self.expect(close)?,
                Task::Arguments => {
                    if self.eat(',') {
                        stack.extend([Task::Arguments, Task::Body]);
                    }
                }
                Task::CommitMessage => {
                    if self.eat(',') {
                        self.quoted()?;
                    }
                }
                Task::CatchAlternative => {
                    self.expect(',')?;
                    stack.extend([Task::CatchLabels, Task::Body]);
                }
                Task::CatchLabels => {
                    while self.eat(',') {
                        self.identifier()?;
                    }
                }
            }
        }
        Ok(())
    }

    fn element_ahead(&self) -> bool {
        matches!(
            self.current().kind,
            Kind::Identifier(_) | Kind::Quoted(_) | Kind::Symbol('(' | '[' | '{' | '@')
        )
    }

    fn recognize_atomic(&mut self, stack: &mut Vec<Task>) -> Result<()> {
        if self.at_parameterized_call() {
            self.pos += 2;
            stack.extend([Task::Close(')'), Task::Arguments, Task::Body]);
        } else if self.at_builtin_call() {
            let operation = self.identifier()?;
            self.expect('(')?;
            stack.push(Task::Close(')'));
            match operation.as_str() {
                "SAME_AS" => {
                    self.identifier()?;
                }
                "CAPTURE" | "LABEL" => {
                    self.identifier()?;
                    self.expect(',')?;
                    stack.push(Task::Body);
                }
                "CAPTURE_SCOPE" => stack.push(Task::Body),
                "COMMIT" => stack.extend([Task::CommitMessage, Task::Body]),
                "CATCH" => stack.extend([Task::CatchAlternative, Task::Body]),
                _ => unreachable!(),
            }
        } else if matches!(self.current().kind, Kind::Symbol('(' | '[' | '{')) {
            let close = if self.eat('(') {
                ')'
            } else if self.eat('[') {
                ']'
            } else {
                self.expect('{')?;
                '}'
            };
            stack.extend([Task::Close(close), Task::Body]);
        } else if matches!(self.current().kind, Kind::Quoted(_)) {
            self.pos += 1;
        } else if self.word_is("ERROR")
            && self
                .tokens
                .get(self.pos + 1)
                .is_some_and(|t| t.kind == Kind::Symbol('('))
            && self
                .tokens
                .get(self.pos + 2)
                .is_some_and(|t| matches!(t.kind, Kind::Quoted(_)))
            && self
                .tokens
                .get(self.pos + 3)
                .is_some_and(|t| t.kind == Kind::Symbol(')'))
        {
            self.pos += 4;
        } else {
            self.rule_reference()?;
        }
        Ok(())
    }
}
