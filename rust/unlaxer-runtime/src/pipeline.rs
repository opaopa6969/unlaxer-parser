//! Bounded demand evaluation of coarse analysis phases, independent of parser recursion.
use crate::source::{Location, Mapping, Result, Snapshot};
use std::collections::{BTreeMap, HashMap, HashSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Complete,
    Partial,
    Failed,
    Inactive,
    Deferred,
    Cycle,
    Limit,
    Unsupported,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Artifact {
    pub state: State,
    pub payload: String,
    pub origins: Vec<Location>,
    pub diagnostics: Vec<Mapping>,
}
impl Artifact {
    pub fn empty(state: State) -> Self {
        Self {
            state,
            payload: String::new(),
            origins: vec![],
            diagnostics: vec![],
        }
    }
}
#[derive(Debug, Clone)]
pub struct Phase {
    pub id: String,
    pub dependencies: Vec<String>,
    pub inputs: Vec<String>,
    pub configuration_keys: Vec<String>,
    pub executes_user_code: bool,
}
/// A phase is inactive unless this key has exactly the expected configuration value.
#[derive(Debug, Clone)]
pub struct Condition {
    pub key: String,
    pub expected: String,
}
pub struct Request {
    pub phase: Phase,
    pub inputs: BTreeMap<String, Snapshot>,
    pub configuration: BTreeMap<String, String>,
    pub dependencies: BTreeMap<String, Artifact>,
}
pub trait Executor {
    fn execute(&self, request: &Request) -> Result<Artifact>;
}
impl<F: Fn(&Request) -> Result<Artifact>> Executor for F {
    fn execute(&self, request: &Request) -> Result<Artifact> {
        self(request)
    }
}
#[derive(Debug)]
pub struct EvaluationResult {
    pub artifact: Artifact,
    /// A revision is scoped to one pipeline instance; unfinished evaluations have none.
    pub revision: Option<u64>,
    pub evaluated: Vec<String>,
    pub reused: Vec<String>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
struct Signature {
    inactive: bool,
    inputs: Vec<Snapshot>,
    configuration: BTreeMap<String, String>,
    dependencies: Vec<u64>,
}
#[derive(Clone)]
struct Cached {
    signature: Signature,
    artifact: Artifact,
    revision: u64,
}
#[derive(Clone)]
struct Value {
    artifact: Artifact,
    revision: Option<u64>,
}
impl Value {
    fn incomplete(state: State) -> Self {
        Self {
            artifact: Artifact::empty(state),
            revision: None,
        }
    }
}
pub struct AnalysisPipeline {
    phases: HashMap<String, Phase>,
    executors: HashMap<String, Box<dyn Executor>>,
    conditions: HashMap<String, Condition>,
    cache: HashMap<String, Cached>,
    revision: u64,
}
impl AnalysisPipeline {
    pub fn new(
        definitions: Vec<Phase>,
        executors: HashMap<String, Box<dyn Executor>>,
    ) -> Result<Self> {
        Self::with_conditions(definitions, executors, HashMap::new())
    }
    pub fn with_conditions(
        definitions: Vec<Phase>,
        executors: HashMap<String, Box<dyn Executor>>,
        conditions: HashMap<String, Condition>,
    ) -> Result<Self> {
        let mut phases = HashMap::new();
        for phase in definitions {
            if phase.id.is_empty() {
                return Err("empty phase id");
            }
            for values in [
                &phase.dependencies,
                &phase.inputs,
                &phase.configuration_keys,
            ] {
                let unique: HashSet<&String> = values.iter().collect();
                if unique.len() != values.len() || values.iter().any(String::is_empty) {
                    return Err("duplicate or empty phase key");
                }
            }
            if phases.insert(phase.id.clone(), phase).is_some() {
                return Err("duplicate phase");
            }
        }
        for phase in phases.values() {
            if phase
                .dependencies
                .iter()
                .any(|dependency| !phases.contains_key(dependency))
            {
                return Err("missing phase dependency");
            }
        }
        if conditions.keys().any(|id| !phases.contains_key(id)) {
            return Err("missing conditional phase");
        }
        if conditions
            .values()
            .any(|condition| condition.key.is_empty())
        {
            return Err("empty condition key");
        }
        Ok(Self {
            phases,
            executors,
            conditions,
            cache: HashMap::new(),
            revision: 0,
        })
    }
    pub fn clear_cache(&mut self) {
        self.cache.clear();
    }
    pub fn evaluate(
        &mut self,
        id: &str,
        snapshots: BTreeMap<String, Snapshot>,
        configuration: BTreeMap<String, String>,
        maximum_phases: usize,
        allow_user_code: bool,
    ) -> Result<EvaluationResult> {
        if maximum_phases > 256 || !self.phases.contains_key(id) {
            return Err("invalid evaluation");
        }
        let mut evaluation = Evaluation {
            pipeline: self,
            snapshots,
            configuration,
            maximum: maximum_phases,
            allow_user_code,
            active: HashSet::new(),
            finished: HashMap::new(),
            evaluated: vec![],
            reused: vec![],
            visited: 0,
        };
        let value = evaluation.visit(id)?;
        Ok(EvaluationResult {
            artifact: value.artifact,
            revision: value.revision,
            evaluated: evaluation.evaluated,
            reused: evaluation.reused,
        })
    }
}
struct Evaluation<'a> {
    pipeline: &'a mut AnalysisPipeline,
    snapshots: BTreeMap<String, Snapshot>,
    configuration: BTreeMap<String, String>,
    maximum: usize,
    allow_user_code: bool,
    active: HashSet<String>,
    finished: HashMap<String, Value>,
    evaluated: Vec<String>,
    reused: Vec<String>,
    visited: usize,
}
impl Evaluation<'_> {
    fn visit(&mut self, id: &str) -> Result<Value> {
        if self.active.contains(id) {
            return Ok(Value::incomplete(State::Cycle));
        }
        if let Some(value) = self.finished.get(id) {
            return Ok(value.clone());
        }
        if self.visited >= self.maximum {
            return Ok(Value::incomplete(State::Limit));
        }
        self.visited += 1;
        let phase = self.pipeline.phases[id].clone();
        let condition = self.pipeline.conditions.get(id).cloned();
        let mut condition_settings = BTreeMap::new();
        if let Some(condition) = &condition {
            if let Some(value) = self.configuration.get(&condition.key) {
                condition_settings.insert(condition.key.clone(), value.clone());
            }
            if self.configuration.get(&condition.key) != Some(&condition.expected) {
                let signature = Signature {
                    inactive: true,
                    inputs: vec![],
                    configuration: condition_settings,
                    dependencies: vec![],
                };
                if let Some(previous) = self.pipeline.cache.get(id) {
                    if previous.signature == signature {
                        self.reused.push(id.into());
                        let value = Value {
                            artifact: previous.artifact.clone(),
                            revision: Some(previous.revision),
                        };
                        self.finished.insert(id.into(), value.clone());
                        return Ok(value);
                    }
                }
                let artifact = Artifact::empty(State::Inactive);
                self.pipeline.revision = self
                    .pipeline
                    .revision
                    .checked_add(1)
                    .ok_or("revision overflow")?;
                let revision = self.pipeline.revision;
                self.pipeline.cache.insert(
                    id.into(),
                    Cached {
                        signature,
                        artifact: artifact.clone(),
                        revision,
                    },
                );
                self.evaluated.push(id.into());
                let value = Value {
                    artifact,
                    revision: Some(revision),
                };
                self.finished.insert(id.into(), value.clone());
                return Ok(value);
            }
        }
        if (phase.executes_user_code && !self.allow_user_code)
            || !self.pipeline.executors.contains_key(id)
        {
            return Ok(Value::incomplete(State::Unsupported));
        }
        let mut inputs = BTreeMap::new();
        let mut input_signature = vec![];
        for key in &phase.inputs {
            let Some(input) = self.snapshots.get(key) else {
                return Ok(Value::incomplete(State::Deferred));
            };
            inputs.insert(key.clone(), input.clone());
            input_signature.push(input.clone());
        }
        let mut settings = condition_settings;
        for key in &phase.configuration_keys {
            if let Some(value) = self.configuration.get(key) {
                settings.insert(key.clone(), value.clone());
            }
        }
        self.active.insert(id.into());
        let result = (|| {
            let mut dependencies = BTreeMap::new();
            let mut versions = vec![];
            for dependency in &phase.dependencies {
                let value = self.visit(dependency)?;
                let Some(revision) = value.revision else {
                    return Ok(value);
                };
                dependencies.insert(dependency.clone(), value.artifact);
                versions.push(revision);
            }
            let signature = Signature {
                inactive: false,
                inputs: input_signature,
                configuration: settings.clone(),
                dependencies: versions,
            };
            if let Some(previous) = self.pipeline.cache.get(id) {
                if previous.signature == signature {
                    self.reused.push(id.into());
                    let value = Value {
                        artifact: previous.artifact.clone(),
                        revision: Some(previous.revision),
                    };
                    self.finished.insert(id.into(), value.clone());
                    return Ok(value);
                }
            }
            let artifact = self.pipeline.executors[id].execute(&Request {
                phase,
                inputs,
                configuration: settings,
                dependencies,
            })?;
            self.evaluated.push(id.into());
            if matches!(
                artifact.state,
                State::Deferred | State::Cycle | State::Limit | State::Unsupported
            ) {
                return Ok(Value {
                    artifact,
                    revision: None,
                });
            }
            self.pipeline.revision = self
                .pipeline
                .revision
                .checked_add(1)
                .ok_or("revision overflow")?;
            let revision = self.pipeline.revision;
            self.pipeline.cache.insert(
                id.into(),
                Cached {
                    signature,
                    artifact: artifact.clone(),
                    revision,
                },
            );
            let value = Value {
                artifact,
                revision: Some(revision),
            };
            self.finished.insert(id.into(), value.clone());
            Ok(value)
        })();
        self.active.remove(id);
        result
    }
}
