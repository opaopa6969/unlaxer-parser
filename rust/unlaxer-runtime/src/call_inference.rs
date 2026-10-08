//! Bounded call constraints. See docs/type-system.md for the portable subset.
use crate::type_system::*;
use crate::Span;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Resolved,
    Ambiguous,
    Unknown,
    Incompatible,
    Unsupported,
    Cycle,
    Limit,
    Invalid,
}
impl State {
    pub fn name(self) -> &'static str {
        match self {
            Self::Resolved => "RESOLVED",
            Self::Ambiguous => "AMBIGUOUS",
            Self::Unknown => "UNKNOWN",
            Self::Incompatible => "INCOMPATIBLE",
            Self::Unsupported => "UNSUPPORTED",
            Self::Cycle => "CYCLE",
            Self::Limit => "LIMIT",
            Self::Invalid => "INVALID",
        }
    }
}
#[derive(Debug, Clone)]
pub struct Parameter {
    pub id: String,
    pub bound: TypeRef,
}
#[derive(Debug, Clone)]
pub struct Signature {
    pub id: String,
    pub variables: Vec<Parameter>,
    pub parameters: Vec<TypeRef>,
    pub result: TypeRef,
    pub varargs: bool,
    pub uri: String,
    pub version: i64,
    pub span: Span,
}
impl Signature {
    fn validate(&self) -> Result<(), String> {
        if self.id.is_empty()
            || self.uri.is_empty()
            || self.version < 0
            || self.varargs && self.parameters.is_empty()
            || self.span.start > self.span.end
            || self.variables.iter().any(|v| v.id.is_empty())
            || self
                .variables
                .iter()
                .map(|v| &v.id)
                .collect::<BTreeSet<_>>()
                .len()
                != self.variables.len()
        {
            return Err("invalid signature".into());
        }
        Ok(())
    }
}
#[derive(Debug, Clone)]
pub struct Argument {
    pub type_ref: TypeRef,
    pub span: Span,
}
#[derive(Debug, Clone)]
pub struct Call {
    pub uri: String,
    pub version: i64,
    pub span: Span,
    pub arguments: Vec<Argument>,
    pub expected_return: TypeRef,
}
impl Call {
    fn validate(&self) -> Result<(), String> {
        if self.uri.is_empty() || self.version < 0 || self.span.start > self.span.end {
            return Err("invalid call snapshot".into());
        }
        let mut end = self.span.start;
        for argument in &self.arguments {
            if argument.span.start < end
                || argument.span.start > argument.span.end
                || argument.span.end > self.span.end
            {
                return Err("argument outside call".into());
            }
            end = argument.span.end;
        }
        Ok(())
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Constraint {
    pub role: String,
    pub actual: TypeRef,
    pub expected: TypeRef,
    pub uri: String,
    pub version: i64,
    pub span: Span,
    pub decision: Decision,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub signature: String,
    pub status: Status,
    pub substitution: BTreeMap<String, TypeRef>,
    pub parameters: Vec<TypeRef>,
    pub result: TypeRef,
    pub varargs: bool,
    pub uri: String,
    pub version: i64,
    pub span: Span,
    pub constraints: Vec<Constraint>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Resolution {
    pub state: State,
    pub uri: String,
    pub version: i64,
    pub candidates: Vec<Candidate>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Value {
    pub id: String,
    pub name: String,
    pub type_ref: TypeRef,
    pub uri: String,
    pub version: i64,
    pub span: Span,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Completion {
    pub value: Value,
    pub decision: Decision,
}
pub struct CallInference<'a, P: Provider> {
    types: &'a TypeSystem<P>,
    maximum_steps: usize,
}
struct Budget {
    remaining: usize,
}
impl Budget {
    fn use_step(&mut self, depth: usize) -> Result<(), TypeError> {
        if self.remaining == 0 || depth >= 128 {
            return Err(failure(Status::Limit, "INFERENCE_LIMIT"));
        }
        self.remaining -= 1;
        Ok(())
    }
}
fn failure(status: Status, reason: impl Into<String>) -> TypeError {
    TypeError {
        status,
        reason: reason.into(),
    }
}
impl<'a, P: Provider> CallInference<'a, P> {
    pub fn new(types: &'a TypeSystem<P>, maximum_steps: usize) -> Result<Self, String> {
        if !(1..=4096).contains(&maximum_steps) {
            return Err("maximumSteps must be 1..4096".into());
        }
        Ok(Self {
            types,
            maximum_steps,
        })
    }
    pub fn infer(&self, signatures: &[Signature], call: &Call) -> Result<Resolution, String> {
        call.validate()?;
        if signatures
            .iter()
            .map(|s| &s.id)
            .collect::<BTreeSet<_>>()
            .len()
            != signatures.len()
        {
            return Err("duplicate signature".into());
        }
        for signature in signatures {
            signature.validate()?;
        }
        let mut budget = Budget {
            remaining: self.maximum_steps,
        };
        let mut candidates = vec![];
        for signature in signatures {
            let candidate = self
                .infer_one(signature, call, &mut budget)
                .unwrap_or_else(|error| Candidate {
                    signature: signature.id.clone(),
                    status: error.status,
                    substitution: BTreeMap::new(),
                    parameters: signature.parameters.clone(),
                    result: signature.result.clone(),
                    varargs: signature.varargs,
                    uri: signature.uri.clone(),
                    version: signature.version,
                    span: signature.span,
                    constraints: vec![Constraint {
                        role: "solver".into(),
                        actual: TypeRef::unknown(),
                        expected: TypeRef::unknown(),
                        uri: call.uri.clone(),
                        version: call.version,
                        span: call.span,
                        decision: error.decision(),
                    }],
                });
            let limit = candidate.status == Status::Limit;
            candidates.push(candidate);
            if limit {
                break;
            }
        }
        let hard = [
            (Status::Limit, State::Limit),
            (Status::Cycle, State::Cycle),
            (Status::Invalid, State::Invalid),
            (Status::Unsupported, State::Unsupported),
        ]
        .into_iter()
        .find(|(s, _)| candidates.iter().any(|c| c.status == *s));
        let viable: Vec<_> = candidates
            .iter()
            .filter(|c| matches!(c.status, Status::Yes | Status::Unknown))
            .collect();
        let state = if let Some((_, state)) = hard {
            state
        } else if viable.len() > 1 {
            State::Ambiguous
        } else if viable.len() == 1 {
            if viable[0].status == Status::Yes {
                State::Resolved
            } else {
                State::Unknown
            }
        } else if signatures.is_empty() {
            State::Unknown
        } else {
            State::Incompatible
        };
        Ok(Resolution {
            state,
            uri: call.uri.clone(),
            version: call.version,
            candidates,
        })
    }
    pub fn expected_argument(
        &self,
        signatures: &[Signature],
        call: &Call,
        index: usize,
    ) -> Result<Resolution, String> {
        if index > call.arguments.len() {
            return Err("invalid argument index".into());
        }
        let mut copy = call.clone();
        if index == copy.arguments.len() {
            copy.arguments.push(Argument {
                type_ref: TypeRef::unknown(),
                span: Span {
                    start: call.span.end,
                    end: call.span.end,
                },
            });
        } else {
            copy.arguments[index].type_ref = TypeRef::unknown();
        }
        self.infer(signatures, &copy)
    }
    fn infer_one(
        &self,
        s: &Signature,
        call: &Call,
        budget: &mut Budget,
    ) -> Result<Candidate, TypeError> {
        budget.use_step(0)?;
        let mut checks = vec![];
        let count = s.parameters.len();
        if !s.varargs && call.arguments.len() != count
            || s.varargs && call.arguments.len() < count - 1
        {
            checks.push(Constraint {
                role: "arity".into(),
                actual: TypeRef::unknown(),
                expected: TypeRef::unknown(),
                uri: call.uri.clone(),
                version: call.version,
                span: call.span,
                decision: Decision::of(Status::No, "CALL_ARITY"),
            });
            return Ok(candidate(
                s,
                BTreeMap::new(),
                s.parameters.clone(),
                s.result.clone(),
                checks,
            ));
        }
        let mut lower: BTreeMap<_, Vec<_>> =
            s.variables.iter().map(|p| (p.id.clone(), vec![])).collect();
        let mut upper = lower.clone();
        for (i, arg) in call.arguments.iter().enumerate() {
            self.collect(
                parameter(&s.parameters, s.varargs, i),
                &arg.type_ref,
                true,
                &mut lower,
                &mut upper,
                budget,
                0,
            )?;
        }
        if call.expected_return.kind() != Kind::Unknown {
            self.collect(
                &s.result,
                &call.expected_return,
                false,
                &mut lower,
                &mut upper,
                budget,
                0,
            )?;
        }
        let mut bindings = BTreeMap::new();
        for variable in &s.variables {
            budget.use_step(0)?;
            let lows = &lower[&variable.id];
            let ups = &upper[&variable.id];
            let selected = if lows.is_empty() {
                self.join(ups, false, budget)?
            } else {
                self.join(lows, true, budget)?
            };
            if let Some(selected) = selected {
                bindings.insert(variable.id.clone(), selected);
            }
        }
        let parameters: Vec<_> = s
            .parameters
            .iter()
            .map(|t| substitute(t, &bindings, self.maximum_steps))
            .collect::<Result<_, _>>()?;
        let result = substitute(&s.result, &bindings, self.maximum_steps)?;
        for (i, arg) in call.arguments.iter().enumerate() {
            self.check(
                &mut checks,
                &format!("argument:{i}"),
                &arg.type_ref,
                parameter(&parameters, s.varargs, i),
                (&call.uri, call.version, arg.span),
                budget,
            )?;
        }
        for variable in &s.variables {
            if variable.bound.kind() == Kind::Unknown {
                continue;
            }
            let actual = bindings
                .get(&variable.id)
                .cloned()
                .unwrap_or_else(|| TypeRef::variable(variable.id.clone()).unwrap());
            let bound = substitute(&variable.bound, &bindings, self.maximum_steps)?;
            self.check(
                &mut checks,
                &format!("bound:{}", variable.id),
                &actual,
                &bound,
                (&s.uri, s.version, s.span),
                budget,
            )?;
        }
        if call.expected_return.kind() != Kind::Unknown {
            self.check(
                &mut checks,
                "return",
                &result,
                &call.expected_return,
                (&call.uri, call.version, call.span),
                budget,
            )?;
        }
        self.check(
            &mut checks,
            "result",
            &result,
            &result,
            (&call.uri, call.version, call.span),
            budget,
        )?;
        if has_variable(&result) || parameters.iter().any(has_variable) {
            checks.push(Constraint {
                role: "unbound".into(),
                actual: result.clone(),
                expected: result.clone(),
                uri: call.uri.clone(),
                version: call.version,
                span: call.span,
                decision: Decision::of(Status::Unknown, "UNBOUND_VARIABLE"),
            });
        }
        Ok(candidate(s, bindings, parameters, result, checks))
    }
    fn check(
        &self,
        checks: &mut Vec<Constraint>,
        role: &str,
        actual: &TypeRef,
        expected: &TypeRef,
        origin: (&str, i64, Span),
        budget: &mut Budget,
    ) -> Result<(), TypeError> {
        budget.use_step(0)?;
        let (uri, version, span) = origin;
        checks.push(Constraint {
            role: role.into(),
            actual: actual.clone(),
            expected: expected.clone(),
            uri: uri.into(),
            version,
            span,
            decision: self.types.compare(actual, expected),
        });
        Ok(())
    }
    fn join(
        &self,
        values: &[TypeRef],
        lower: bool,
        budget: &mut Budget,
    ) -> Result<Option<TypeRef>, TypeError> {
        let Some(first) = values.first() else {
            return Ok(None);
        };
        let mut selected = first.clone();
        for next in &values[1..] {
            budget.use_step(0)?;
            let forward = self.types.compare(next, &selected);
            let reverse = self.types.compare(&selected, next);
            for decision in [&forward, &reverse] {
                if !matches!(decision.status, Status::Yes | Status::No | Status::Unknown) {
                    return Err(failure(decision.status, decision.rule.clone()));
                }
            }
            if (if lower {
                forward.status
            } else {
                reverse.status
            }) == Status::Yes
            {
                continue;
            }
            if (if lower {
                reverse.status
            } else {
                forward.status
            }) == Status::Yes
            {
                selected = next.clone();
            } else {
                let cap = if lower {
                    Capability::Union
                } else {
                    Capability::Intersection
                };
                if !self.types.provider().capabilities().contains(&cap) {
                    return Err(failure(Status::Unsupported, cap.name()));
                }
                selected = TypeRef::new(
                    if lower {
                        Kind::Union
                    } else {
                        Kind::Intersection
                    },
                    String::new(),
                    vec![selected, next.clone()],
                )
                .unwrap();
            }
        }
        Ok(Some(selected))
    }
    #[allow(clippy::too_many_arguments)]
    fn collect(
        &self,
        pattern: &TypeRef,
        actual: &TypeRef,
        lower: bool,
        lows: &mut BTreeMap<String, Vec<TypeRef>>,
        ups: &mut BTreeMap<String, Vec<TypeRef>>,
        budget: &mut Budget,
        depth: usize,
    ) -> Result<(), TypeError> {
        budget.use_step(depth)?;
        if matches!(actual.kind(), Kind::Unknown | Kind::Variable) {
            return Ok(());
        }
        if pattern.kind() == Kind::Variable && lows.contains_key(pattern.name()) {
            (if lower { lows } else { ups })
                .get_mut(pattern.name())
                .unwrap()
                .push(actual.clone());
            return Ok(());
        }
        if pattern.kind() != actual.kind() || pattern.arguments().len() != actual.arguments().len()
        {
            return Ok(());
        }
        if pattern.kind() == Kind::Named {
            if pattern.name() != actual.name() || pattern.arguments().is_empty() {
                return Ok(());
            }
            let variance = self
                .types
                .provider()
                .variance(pattern.name())
                .ok_or_else(|| failure(Status::Unsupported, "INFERENCE_VARIANCE"))?;
            if variance.len() != pattern.arguments().len() {
                return Err(failure(Status::Invalid, "TYPE_ARITY"));
            }
            for (i, v) in variance.iter().enumerate() {
                let (p, a) = (&pattern.arguments()[i], &actual.arguments()[i]);
                if *v != Variance::Contravariant {
                    self.collect(p, a, lower, lows, ups, budget, depth + 1)?;
                }
                if *v != Variance::Covariant {
                    self.collect(p, a, !lower, lows, ups, budget, depth + 1)?;
                }
            }
        } else if pattern.kind() == Kind::Function {
            for i in 0..pattern.arguments().len() {
                self.collect(
                    &pattern.arguments()[i],
                    &actual.arguments()[i],
                    if i == pattern.arguments().len() - 1 {
                        lower
                    } else {
                        !lower
                    },
                    lows,
                    ups,
                    budget,
                    depth + 1,
                )?;
            }
        } else if pattern.kind() == Kind::Nullable {
            self.collect(
                &pattern.arguments()[0],
                &actual.arguments()[0],
                lower,
                lows,
                ups,
                budget,
                depth + 1,
            )?;
        }
        Ok(())
    }
    pub fn expected_types(&self, resolution: &Resolution, index: usize) -> Vec<TypeRef> {
        if !matches!(
            resolution.state,
            State::Resolved | State::Ambiguous | State::Unknown
        ) {
            return vec![];
        }
        let mut result = vec![];
        for c in &resolution.candidates {
            if matches!(c.status, Status::Yes | Status::Unknown)
                && (index < c.parameters.len() || c.varargs)
            {
                let t = parameter(&c.parameters, c.varargs, index);
                if !result.contains(t) {
                    result.push(t.clone());
                }
            }
        }
        result
    }
    pub fn assess(&self, resolution: &Resolution, index: usize, actual: &TypeRef) -> Decision {
        combine(
            false,
            "EXPECTED_ARGUMENT",
            self.expected_types(resolution, index)
                .iter()
                .map(|t| self.types.compare(actual, t))
                .collect(),
        )
    }
    pub fn complete(
        &self,
        visible: &[Value],
        resolution: &Resolution,
        index: usize,
    ) -> Vec<Completion> {
        let mut result = vec![];
        for value in visible {
            let decision = self.assess(resolution, index, &value.type_ref);
            if matches!(decision.status, Status::Yes | Status::Unknown) {
                result.push(Completion {
                    value: value.clone(),
                    decision,
                });
            }
        }
        result.sort_by(|a, b| {
            (a.decision.status != Status::Yes, &a.value.name)
                .cmp(&(b.decision.status != Status::Yes, &b.value.name))
        });
        result
    }
}
fn candidate(
    s: &Signature,
    substitution: BTreeMap<String, TypeRef>,
    parameters: Vec<TypeRef>,
    result: TypeRef,
    constraints: Vec<Constraint>,
) -> Candidate {
    let status = combine(
        true,
        "CALL_CONSTRAINTS",
        constraints.iter().map(|c| c.decision.clone()).collect(),
    )
    .status;
    Candidate {
        signature: s.id.clone(),
        status,
        substitution,
        parameters,
        result,
        varargs: s.varargs,
        uri: s.uri.clone(),
        version: s.version,
        span: s.span,
        constraints,
    }
}
fn parameter(parameters: &[TypeRef], varargs: bool, index: usize) -> &TypeRef {
    &parameters[if varargs {
        index.min(parameters.len() - 1)
    } else {
        index
    }]
}
fn has_variable(t: &TypeRef) -> bool {
    t.kind() == Kind::Variable || t.arguments().iter().any(has_variable)
}
