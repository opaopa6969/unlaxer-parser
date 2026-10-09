//! Bounded portable type relations; named types are interpreted by a language provider.
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Kind {
    Named,
    Variable,
    Unknown,
    Union,
    Intersection,
    Nullable,
    Function,
    Alias,
    Null,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Capability {
    Named,
    Generics,
    Union,
    Intersection,
    Nullable,
    Function,
    Alias,
    Structural,
    Trait,
}
impl Capability {
    pub fn name(self) -> &'static str {
        match self {
            Self::Named => "NAMED",
            Self::Generics => "GENERICS",
            Self::Union => "UNION",
            Self::Intersection => "INTERSECTION",
            Self::Nullable => "NULLABLE",
            Self::Function => "FUNCTION",
            Self::Alias => "ALIAS",
            Self::Structural => "STRUCTURAL",
            Self::Trait => "TRAIT",
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    Yes,
    No,
    Unknown,
    Unsupported,
    Cycle,
    Limit,
    Invalid,
}
impl Status {
    pub fn name(self) -> &'static str {
        match self {
            Self::Yes => "YES",
            Self::No => "NO",
            Self::Unknown => "UNKNOWN",
            Self::Unsupported => "UNSUPPORTED",
            Self::Cycle => "CYCLE",
            Self::Limit => "LIMIT",
            Self::Invalid => "INVALID",
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Variance {
    Invariant,
    Covariant,
    Contravariant,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Policy {
    Nominal,
    Structural,
    Trait,
}
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct TypeRef {
    kind: Kind,
    name: String,
    arguments: Vec<TypeRef>,
}
impl TypeRef {
    pub fn new(kind: Kind, name: String, arguments: Vec<Self>) -> Result<Self, String> {
        let named = matches!(kind, Kind::Named | Kind::Variable | Kind::Alias);
        if named == name.is_empty() {
            return Err("invalid type name".into());
        }
        let n = arguments.len();
        if matches!(kind, Kind::Variable | Kind::Unknown | Kind::Null) && n != 0
            || kind == Kind::Nullable && n != 1
            || matches!(kind, Kind::Union | Kind::Intersection | Kind::Function) && n == 0
        {
            return Err("invalid type arity".into());
        }
        Ok(Self {
            kind,
            name,
            arguments,
        })
    }
    pub fn named(name: String, arguments: Vec<Self>) -> Result<Self, String> {
        Self::new(Kind::Named, name, arguments)
    }
    pub fn variable(name: String) -> Result<Self, String> {
        Self::new(Kind::Variable, name, vec![])
    }
    pub fn unknown() -> Self {
        Self {
            kind: Kind::Unknown,
            name: String::new(),
            arguments: vec![],
        }
    }
    pub fn kind(&self) -> Kind {
        self.kind
    }
    pub fn name(&self) -> &str {
        &self.name
    }
    pub fn arguments(&self) -> &[Self] {
        &self.arguments
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Decision {
    pub status: Status,
    pub rule: String,
    pub evidence: Vec<Decision>,
}
impl Decision {
    pub fn of(status: Status, rule: impl Into<String>) -> Self {
        Self {
            status,
            rule: rule.into(),
            evidence: vec![],
        }
    }
}
#[derive(Debug, Clone)]
pub struct Alias {
    pub name: String,
    pub parameters: Vec<String>,
    pub body: TypeRef,
}
#[derive(Debug, Clone)]
pub struct Definition {
    pub name: String,
    pub parameters: Vec<String>,
    pub variance: Vec<Variance>,
    pub parents: Vec<TypeRef>,
    pub fields: BTreeMap<String, TypeRef>,
    pub structural: bool,
}
pub trait Provider {
    fn capabilities(&self) -> &BTreeSet<Capability>;
    fn variance(&self, _name: &str) -> Option<&[Variance]> {
        None
    }
    fn validate(&self, _named: &TypeRef) -> Decision {
        Decision::of(Status::Yes, "PROVIDER_TYPE")
    }
    fn named(
        &self,
        actual: &TypeRef,
        expected: &TypeRef,
        relation: &mut dyn FnMut(&TypeRef, &TypeRef) -> Decision,
    ) -> Decision;
}
/// Read-only structural fields and explicit edges; this does not implement a whole compiler.
pub struct DeclaredProvider {
    policy: Policy,
    definitions: BTreeMap<String, Definition>,
    capabilities: BTreeSet<Capability>,
}
impl DeclaredProvider {
    pub fn new(
        policy: Policy,
        definitions: Vec<Definition>,
        capabilities: BTreeSet<Capability>,
    ) -> Result<Self, String> {
        let mut collected = BTreeMap::new();
        for definition in definitions {
            validate_parameters(&definition.parameters)?;
            if definition.name.is_empty()
                || definition.parameters.len() != definition.variance.len()
            {
                return Err("invalid named definition".into());
            }
            if collected
                .insert(definition.name.clone(), definition)
                .is_some()
            {
                return Err("duplicate type".into());
            }
        }
        Ok(Self {
            policy,
            definitions: collected,
            capabilities,
        })
    }
    fn named_checked(
        &self,
        actual: &TypeRef,
        expected: &TypeRef,
        relation: &mut dyn FnMut(&TypeRef, &TypeRef) -> Decision,
    ) -> Result<Decision, TypeError> {
        let (Some(a), Some(b)) = (
            self.definitions.get(&actual.name),
            self.definitions.get(&expected.name),
        ) else {
            return Ok(Decision::of(Status::Invalid, "UNDEFINED_TYPE"));
        };
        if a.parameters.len() != actual.arguments.len()
            || b.parameters.len() != expected.arguments.len()
        {
            return Ok(Decision::of(Status::Invalid, "TYPE_ARITY"));
        }
        if actual.name == expected.name {
            let mut checks = vec![];
            for (i, variance) in a.variance.iter().enumerate() {
                let (x, y) = (&actual.arguments[i], &expected.arguments[i]);
                match variance {
                    Variance::Covariant => checks.push(relation(x, y)),
                    Variance::Contravariant => checks.push(relation(y, x)),
                    Variance::Invariant => {
                        checks.push(relation(x, y));
                        checks.push(relation(y, x));
                    }
                }
            }
            return Ok(combine(true, "NAMED_ARGUMENTS", checks));
        }
        let a_bindings = bind(&a.parameters, &actual.arguments);
        let b_bindings = bind(&b.parameters, &expected.arguments);
        if self.policy == Policy::Structural && b.structural {
            if !self.capabilities.contains(&Capability::Structural) {
                return Ok(Decision::of(Status::Unsupported, "STRUCTURAL"));
            }
            let mut checks = vec![];
            for (field, t) in &b.fields {
                if let Some(s) = a.fields.get(field) {
                    let x = substitute(s, &a_bindings, 256)?;
                    let y = substitute(t, &b_bindings, 256)?;
                    let nested = relation(&x, &y);
                    checks.push(Decision {
                        status: nested.status,
                        rule: format!("FIELD:{field}"),
                        evidence: vec![nested],
                    });
                } else {
                    checks.push(Decision::of(Status::No, format!("MISSING_FIELD:{field}")));
                }
            }
            return Ok(combine(true, "STRUCTURAL_FIELDS", checks));
        }
        if self.policy == Policy::Trait && !self.capabilities.contains(&Capability::Trait) {
            return Ok(Decision::of(Status::Unsupported, "TRAIT"));
        }
        let mut parents = vec![];
        for parent in &a.parents {
            parents.push(relation(&substitute(parent, &a_bindings, 256)?, expected));
        }
        Ok(combine(
            false,
            if self.policy == Policy::Trait {
                "DECLARED_TRAIT_EDGES"
            } else {
                "NOMINAL_PARENTS"
            },
            parents,
        ))
    }
}
impl Provider for DeclaredProvider {
    fn capabilities(&self) -> &BTreeSet<Capability> {
        &self.capabilities
    }
    fn variance(&self, name: &str) -> Option<&[Variance]> {
        self.definitions.get(name).map(|d| d.variance.as_slice())
    }
    fn validate(&self, named: &TypeRef) -> Decision {
        let Some(definition) = self.definitions.get(&named.name) else {
            return Decision::of(Status::Invalid, "UNDEFINED_TYPE");
        };
        Decision::of(
            if definition.parameters.len() == named.arguments.len() {
                Status::Yes
            } else {
                Status::Invalid
            },
            "TYPE_ARITY",
        )
    }
    fn named(
        &self,
        actual: &TypeRef,
        expected: &TypeRef,
        relation: &mut dyn FnMut(&TypeRef, &TypeRef) -> Decision,
    ) -> Decision {
        self.named_checked(actual, expected, relation)
            .unwrap_or_else(TypeError::decision)
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TypeError {
    pub status: Status,
    pub reason: String,
}
impl TypeError {
    fn new(status: Status, reason: impl Into<String>) -> Self {
        Self {
            status,
            reason: reason.into(),
        }
    }
    pub fn decision(self) -> Decision {
        Decision::of(self.status, self.reason)
    }
}
impl std::fmt::Display for TypeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.status.name(), self.reason)
    }
}
impl std::error::Error for TypeError {}
pub struct TypeSystem<P: Provider> {
    provider: P,
    aliases: BTreeMap<String, Alias>,
    maximum_steps: usize,
}
struct Budget {
    remaining: usize,
}
impl Budget {
    fn use_step(&mut self, depth: usize) -> Result<(), TypeError> {
        if self.remaining == 0 || depth >= 128 {
            return Err(TypeError::new(Status::Limit, "TYPE_LIMIT"));
        }
        self.remaining -= 1;
        Ok(())
    }
}
impl<P: Provider> TypeSystem<P> {
    pub fn new(provider: P, aliases: Vec<Alias>, maximum_steps: usize) -> Result<Self, String> {
        if !(1..=4096).contains(&maximum_steps) {
            return Err("maximumSteps must be 1..4096".into());
        }
        let mut collected = BTreeMap::new();
        for alias in aliases {
            validate_parameters(&alias.parameters)?;
            if alias.name.is_empty() || collected.insert(alias.name.clone(), alias).is_some() {
                return Err("invalid alias".into());
            }
        }
        Ok(Self {
            provider,
            aliases: collected,
            maximum_steps,
        })
    }
    pub fn provider(&self) -> &P {
        &self.provider
    }
    pub fn compare(&self, actual: &TypeRef, expected: &TypeRef) -> Decision {
        let mut validation = Budget { remaining: 4096 };
        if let Err(error) = self
            .validate(actual, &mut validation, 0)
            .and_then(|()| self.validate(expected, &mut validation, 0))
        {
            return error.decision();
        }
        self.compare_inner(
            actual,
            expected,
            &mut Budget {
                remaining: self.maximum_steps,
            },
            &mut BTreeSet::new(),
            &mut BTreeSet::new(),
            0,
        )
    }
    fn validate(&self, t: &TypeRef, budget: &mut Budget, depth: usize) -> Result<(), TypeError> {
        budget.use_step(depth)?;
        for capability in required(t) {
            if !self.provider.capabilities().contains(&capability) {
                return Err(TypeError::new(Status::Unsupported, capability.name()));
            }
        }
        if t.kind == Kind::Named {
            let result = self.provider.validate(t);
            if result.status != Status::Yes {
                return Err(TypeError::new(result.status, result.rule));
            }
        }
        if t.kind == Kind::Alias {
            let Some(alias) = self.aliases.get(&t.name) else {
                return Err(TypeError::new(
                    Status::Invalid,
                    format!("UNDEFINED_ALIAS:{}", t.name),
                ));
            };
            if alias.parameters.len() != t.arguments.len() {
                return Err(TypeError::new(
                    Status::Invalid,
                    format!("ALIAS_ARITY:{}", t.name),
                ));
            }
        }
        for arg in &t.arguments {
            self.validate(arg, budget, depth + 1)?;
        }
        Ok(())
    }
    fn compare_inner(
        &self,
        actual: &TypeRef,
        expected: &TypeRef,
        budget: &mut Budget,
        active: &mut BTreeSet<(TypeRef, TypeRef)>,
        active_aliases: &mut BTreeSet<(bool, TypeRef)>,
        depth: usize,
    ) -> Decision {
        self.compare_checked(actual, expected, budget, active, active_aliases, depth)
            .unwrap_or_else(TypeError::decision)
    }
    fn compare_checked(
        &self,
        actual: &TypeRef,
        expected: &TypeRef,
        budget: &mut Budget,
        active: &mut BTreeSet<(TypeRef, TypeRef)>,
        active_aliases: &mut BTreeSet<(bool, TypeRef)>,
        depth: usize,
    ) -> Result<Decision, TypeError> {
        budget.use_step(depth)?;
        let requirements: BTreeSet<_> = required(actual)
            .into_iter()
            .chain(required(expected))
            .collect();
        for capability in requirements {
            if !self.provider.capabilities().contains(&capability) {
                return Ok(Decision::of(Status::Unsupported, capability.name()));
            }
        }
        if actual.kind == Kind::Alias || expected.kind == Kind::Alias {
            let left = actual.kind == Kind::Alias;
            let t = if left { actual } else { expected };
            let key = (left, t.clone());
            if !active_aliases.insert(key.clone()) {
                return Ok(Decision::of(
                    Status::Cycle,
                    format!("ALIAS_CYCLE:{}", t.name),
                ));
            }
            let result = (|| {
                let Some(alias) = self.aliases.get(&t.name) else {
                    return Ok(Decision::of(
                        Status::Invalid,
                        format!("UNDEFINED_ALIAS:{}", t.name),
                    ));
                };
                if alias.parameters.len() != t.arguments.len() {
                    return Ok(Decision::of(
                        Status::Invalid,
                        format!("ALIAS_ARITY:{}", t.name),
                    ));
                }
                let expanded = substitute_inner(
                    &alias.body,
                    &bind(&alias.parameters, &t.arguments),
                    budget,
                    &mut BTreeSet::new(),
                    depth + 1,
                )?;
                self.validate(&expanded, budget, depth + 1)?;
                let nested = self.compare_inner(
                    if left { &expanded } else { actual },
                    if left { expected } else { &expanded },
                    budget,
                    active,
                    active_aliases,
                    depth + 1,
                );
                Ok(Decision {
                    status: nested.status,
                    rule: format!("ALIAS:{}", t.name),
                    evidence: vec![nested],
                })
            })();
            active_aliases.remove(&key);
            return result;
        }
        if matches!(actual.kind, Kind::Unknown | Kind::Variable)
            || matches!(expected.kind, Kind::Unknown | Kind::Variable)
        {
            return Ok(Decision::of(Status::Unknown, "UNRESOLVED_TYPE"));
        }
        let pair = (actual.clone(), expected.clone());
        if !active.insert(pair.clone()) {
            return Ok(Decision::of(Status::Cycle, "RELATION_CYCLE"));
        }
        let mut relation = |a: &TypeRef, b: &TypeRef| {
            self.compare_inner(a, b, budget, active, active_aliases, depth + 1)
        };
        let result = if actual.kind == Kind::Nullable {
            combine(
                true,
                "ACTUAL_NULLABLE",
                vec![
                    relation(&actual.arguments[0], expected),
                    relation(&nil(), expected),
                ],
            )
        } else if expected.kind == Kind::Nullable {
            combine(
                false,
                "EXPECTED_NULLABLE",
                vec![
                    relation(actual, &expected.arguments[0]),
                    relation(actual, &nil()),
                ],
            )
        } else if actual.kind == Kind::Union {
            combine(
                true,
                "ACTUAL_UNION",
                actual
                    .arguments
                    .iter()
                    .map(|t| relation(t, expected))
                    .collect(),
            )
        } else if expected.kind == Kind::Intersection {
            combine(
                true,
                "EXPECTED_INTERSECTION",
                expected
                    .arguments
                    .iter()
                    .map(|t| relation(actual, t))
                    .collect(),
            )
        } else if expected.kind == Kind::Union {
            combine(
                false,
                "EXPECTED_UNION",
                expected
                    .arguments
                    .iter()
                    .map(|t| relation(actual, t))
                    .collect(),
            )
        } else if actual.kind == Kind::Intersection {
            combine(
                false,
                "ACTUAL_INTERSECTION",
                actual
                    .arguments
                    .iter()
                    .map(|t| relation(t, expected))
                    .collect(),
            )
        } else if actual.kind == Kind::Null || expected.kind == Kind::Null {
            Decision::of(
                if actual.kind == expected.kind {
                    Status::Yes
                } else {
                    Status::No
                },
                "NULL",
            )
        } else if actual.kind == Kind::Function && expected.kind == Kind::Function {
            if actual.arguments.len() != expected.arguments.len() {
                Decision::of(Status::No, "FUNCTION_ARITY")
            } else {
                let last = actual.arguments.len() - 1;
                let mut checks = vec![];
                for i in 0..last {
                    checks.push(relation(&expected.arguments[i], &actual.arguments[i]));
                }
                checks.push(relation(&actual.arguments[last], &expected.arguments[last]));
                combine(true, "FUNCTION", checks)
            }
        } else if actual.kind != Kind::Named || expected.kind != Kind::Named {
            Decision::of(Status::No, "TYPE_KIND")
        } else {
            self.provider.named(actual, expected, &mut relation)
        };
        active.remove(&pair);
        Ok(result)
    }
}
fn nil() -> TypeRef {
    TypeRef {
        kind: Kind::Null,
        name: String::new(),
        arguments: vec![],
    }
}
fn required(t: &TypeRef) -> Vec<Capability> {
    match t.kind {
        Kind::Named => {
            if t.arguments.is_empty() {
                vec![Capability::Named]
            } else {
                vec![Capability::Named, Capability::Generics]
            }
        }
        Kind::Union => vec![Capability::Union],
        Kind::Intersection => vec![Capability::Intersection],
        Kind::Nullable => vec![Capability::Nullable],
        Kind::Function => vec![Capability::Function],
        Kind::Alias => vec![Capability::Alias],
        _ => vec![],
    }
}
pub fn combine(all: bool, rule: &str, checks: Vec<Decision>) -> Decision {
    for status in [
        Status::Limit,
        Status::Cycle,
        Status::Invalid,
        Status::Unsupported,
    ] {
        if checks.iter().any(|c| c.status == status) {
            return Decision {
                status,
                rule: rule.into(),
                evidence: checks,
            };
        }
    }
    let decisive = if all { Status::No } else { Status::Yes };
    let status = if checks.iter().any(|c| c.status == decisive) {
        decisive
    } else if checks.iter().any(|c| c.status == Status::Unknown) {
        Status::Unknown
    } else if all {
        Status::Yes
    } else {
        Status::No
    };
    Decision {
        status,
        rule: rule.into(),
        evidence: checks,
    }
}
fn validate_parameters(parameters: &[String]) -> Result<(), String> {
    if parameters.iter().any(String::is_empty)
        || parameters.iter().collect::<BTreeSet<_>>().len() != parameters.len()
    {
        return Err("invalid type parameters".into());
    }
    Ok(())
}
fn bind(parameters: &[String], arguments: &[TypeRef]) -> BTreeMap<String, TypeRef> {
    parameters
        .iter()
        .cloned()
        .zip(arguments.iter().cloned())
        .collect()
}
pub fn substitute(
    t: &TypeRef,
    bindings: &BTreeMap<String, TypeRef>,
    maximum_steps: usize,
) -> Result<TypeRef, TypeError> {
    if !(1..=4096).contains(&maximum_steps) {
        return Err(TypeError::new(
            Status::Invalid,
            "maximumSteps must be 1..4096",
        ));
    }
    substitute_inner(
        t,
        bindings,
        &mut Budget {
            remaining: maximum_steps,
        },
        &mut BTreeSet::new(),
        0,
    )
}
fn substitute_inner(
    t: &TypeRef,
    bindings: &BTreeMap<String, TypeRef>,
    budget: &mut Budget,
    active: &mut BTreeSet<String>,
    depth: usize,
) -> Result<TypeRef, TypeError> {
    budget.use_step(depth)?;
    if t.kind == Kind::Variable {
        if let Some(replacement) = bindings.get(&t.name) {
            if replacement == t {
                return Ok(t.clone());
            }
            if !active.insert(t.name.clone()) {
                return Err(TypeError::new(
                    Status::Cycle,
                    format!("SUBSTITUTION_CYCLE:{}", t.name),
                ));
            }
            let result = substitute_inner(replacement, bindings, budget, active, depth + 1);
            active.remove(&t.name);
            return result;
        }
    }
    let mut arguments = vec![];
    for argument in &t.arguments {
        arguments.push(substitute_inner(
            argument,
            bindings,
            budget,
            active,
            depth + 1,
        )?);
    }
    Ok(TypeRef {
        kind: t.kind,
        name: t.name.clone(),
        arguments,
    })
}
