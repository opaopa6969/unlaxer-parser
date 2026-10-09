//! Transactional dependency queries. Providers are fixed and deterministic for a database lifetime.
use std::collections::{BTreeMap, BTreeSet};
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc,
};

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct Key {
    pub kind: String,
    pub identity: String,
    pub arguments: String,
}
impl Key {
    pub fn new(kind: &str, identity: &str, arguments: &str) -> Result<Self, Failure> {
        if kind.is_empty() || identity.is_empty() {
            return Err(Failure::Invalid);
        }
        Ok(Self {
            kind: kind.into(),
            identity: identity.into(),
            arguments: arguments.into(),
        })
    }
    fn weight(&self) -> usize {
        24 + self.kind.len() + self.identity.len() + self.arguments.len()
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Input {
    pub uri: String,
    pub version: i64,
    pub value: String,
}
#[derive(Clone, Copy, Debug)]
pub struct Limits {
    pub entries: usize,
    pub bytes: usize,
    pub inputs: usize,
    pub steps: usize,
    pub dependencies: usize,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Failure {
    Cancelled,
    Closed,
    Cycle,
    Limit,
    Stale,
    Unsupported,
    Invalid,
}
impl Failure {
    pub fn name(self) -> &'static str {
        match self {
            Self::Cancelled => "CANCELLED",
            Self::Closed => "CLOSED",
            Self::Cycle => "CYCLE",
            Self::Limit => "LIMIT",
            Self::Stale => "STALE",
            Self::Unsupported => "UNSUPPORTED",
            Self::Invalid => "INVALID",
        }
    }
}
#[derive(Clone, Default)]
pub struct Cancellation(Arc<AtomicBool>);
impl Cancellation {
    pub fn cancel(&self) {
        self.0.store(true, Ordering::SeqCst);
    }
    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Dependency {
    pub key: Key,
    pub revision: u64,
}
#[derive(Clone, Copy, Debug, Default)]
pub struct Stats {
    pub hits: u64,
    pub evaluated: u64,
    pub evicted: u64,
    pub entries: usize,
    pub bytes: usize,
    pub maximum_bytes: usize,
    pub maximum_entries: usize,
}
#[derive(Clone)]
pub struct QueryResult {
    owner: u64,
    project: String,
    project_version: i64,
    key: Key,
    revision: u64,
    /// Historical data. Use `accept` immediately before publishing diagnostics or applying edits.
    value: String,
    inputs: BTreeMap<String, u64>,
    queries: Vec<Dependency>,
    cancellation: Cancellation,
}
impl QueryResult {
    pub fn project(&self) -> &str {
        &self.project
    }
    pub fn project_version(&self) -> i64 {
        self.project_version
    }
    pub fn key(&self) -> &Key {
        &self.key
    }
    pub fn revision(&self) -> u64 {
        self.revision
    }
    pub fn value(&self) -> &str {
        &self.value
    }
    pub fn inputs(&self) -> &BTreeMap<String, u64> {
        &self.inputs
    }
    pub fn queries(&self) -> &[Dependency] {
        &self.queries
    }
}

pub trait Context {
    fn input(&mut self, key: &str) -> Result<Option<Input>, Failure>;
    fn query(&mut self, key: Key) -> Result<String, Failure>;
    fn checkpoint(&self) -> Result<(), Failure>;
}
pub trait Executor {
    fn execute(&self, key: &Key, context: &mut dyn Context) -> Result<String, Failure>;
}
#[derive(Clone)]
struct Versioned {
    input: Input,
    revision: u64,
}
#[derive(Clone)]
struct Cached {
    value: String,
    revision: u64,
    inputs: BTreeMap<String, u64>,
    queries: Vec<Dependency>,
    bytes: usize,
}
static OWNERS: AtomicU64 = AtomicU64::new(1);
pub struct SemanticQueries {
    owner: u64,
    project: String,
    version: Option<i64>,
    limits: Limits,
    executors: BTreeMap<String, Arc<dyn Executor>>,
    inputs: BTreeMap<String, Versioned>,
    cache: BTreeMap<Key, Cached>,
    order: Vec<Key>,
    revision: u64,
    stats: Stats,
    closed: bool,
}
impl SemanticQueries {
    pub fn new(
        project: &str,
        limits: Limits,
        executors: BTreeMap<String, Arc<dyn Executor>>,
    ) -> Result<Self, Failure> {
        if project.is_empty()
            || executors.keys().any(String::is_empty)
            || !(1..=4096).contains(&limits.steps)
            || !(1..=4096).contains(&limits.dependencies)
        {
            return Err(Failure::Invalid);
        }
        Ok(Self {
            owner: OWNERS.fetch_add(1, Ordering::SeqCst),
            project: project.into(),
            version: None,
            limits,
            executors,
            inputs: BTreeMap::new(),
            cache: BTreeMap::new(),
            order: vec![],
            revision: 0,
            stats: Stats::default(),
            closed: false,
        })
    }
    fn open(&self) -> Result<(), Failure> {
        if self.closed {
            Err(Failure::Closed)
        } else {
            Ok(())
        }
    }
    fn next_revision(&mut self) -> Result<u64, Failure> {
        self.revision = self.revision.checked_add(1).ok_or(Failure::Limit)?;
        Ok(self.revision)
    }
    pub fn update(
        &mut self,
        version: i64,
        replacement: BTreeMap<String, Input>,
    ) -> Result<(), Failure> {
        self.open()?;
        if version < 0 || self.version.is_some_and(|old| version <= old) {
            return Err(Failure::Stale);
        }
        if replacement.len() > self.limits.inputs {
            return Err(Failure::Limit);
        }
        for (key, input) in &replacement {
            if key.is_empty() || input.version < 0 {
                return Err(Failure::Invalid);
            }
            if let Some(old) = self.inputs.get(key) {
                if old.input.uri == input.uri
                    && (input.version < old.input.version
                        || input.version == old.input.version && input.value != old.input.value)
                {
                    return Err(Failure::Stale);
                }
            }
        }
        let mut next = BTreeMap::new();
        for (key, input) in replacement {
            let value = if let Some(old) = self.inputs.get(&key).filter(|old| old.input == input) {
                old.clone()
            } else {
                Versioned {
                    input,
                    revision: self.next_revision()?,
                }
            };
            next.insert(key, value);
        }
        self.inputs = next;
        self.version = Some(version);
        self.cache.retain(|_, cached| {
            cached.inputs.iter().all(|(id, revision)| {
                self.inputs.get(id).map_or(0, |input| input.revision) == *revision
            })
        });
        self.order.retain(|key| self.cache.contains_key(key));
        self.stats.entries = self.cache.len();
        self.stats.bytes = self.cache.values().map(|c| c.bytes).sum();
        Ok(())
    }
    fn input_revision(&self, key: &str) -> u64 {
        self.inputs.get(key).map_or(0, |input| input.revision)
    }
    /// Supply the snapshot version used to construct an asynchronous request key.
    pub fn evaluate_at(
        &mut self,
        expected_version: i64,
        key: Key,
        cancellation: Cancellation,
    ) -> Result<QueryResult, Failure> {
        self.open()?;
        if self.version != Some(expected_version) {
            return Err(Failure::Stale);
        }
        self.evaluate(key, cancellation)
    }
    /// Synchronous convenience for keys constructed on the current serialized editor lane.
    pub fn evaluate(
        &mut self,
        key: Key,
        cancellation: Cancellation,
    ) -> Result<QueryResult, Failure> {
        self.open()?;
        let version = self.version.ok_or(Failure::Stale)?;
        if key.kind.is_empty() || key.identity.is_empty() {
            return Err(Failure::Invalid);
        }
        let mut evaluation = Evaluation {
            db: self,
            cancellation: cancellation.clone(),
            staged: BTreeMap::new(),
            order: vec![],
            active: BTreeSet::new(),
            steps: 0,
            hits: 0,
            evaluated: 0,
            broken: None,
        };
        let result = evaluation.visit(&key)?;
        evaluation.checkpoint()?;
        for key in &evaluation.order {
            evaluation
                .db
                .put(key.clone(), evaluation.staged[key].clone());
        }
        evaluation.db.stats.hits += evaluation.hits;
        evaluation.db.stats.evaluated += evaluation.evaluated;
        Ok(QueryResult {
            owner: evaluation.db.owner,
            project: evaluation.db.project.clone(),
            project_version: version,
            key,
            revision: result.revision,
            value: result.value,
            inputs: result.inputs,
            queries: result.queries,
            cancellation,
        })
    }
    fn put(&mut self, key: Key, value: Cached) {
        if let Some(old) = self.cache.remove(&key) {
            self.stats.bytes -= old.bytes;
        }
        self.order.retain(|k| k != &key);
        if self.limits.entries == 0 || value.bytes > self.limits.bytes {
            self.stats.entries = self.cache.len();
            return;
        }
        while self.cache.len() >= self.limits.entries
            || self.stats.bytes > self.limits.bytes - value.bytes
        {
            let oldest = self.order.remove(0);
            let removed = self.cache.remove(&oldest).expect("cache order");
            self.stats.bytes -= removed.bytes;
            self.stats.evicted += 1;
        }
        self.stats.bytes += value.bytes;
        self.cache.insert(key.clone(), value);
        self.order.push(key);
        self.stats.entries = self.cache.len();
        self.stats.maximum_bytes = self.stats.maximum_bytes.max(self.stats.bytes);
        self.stats.maximum_entries = self.stats.maximum_entries.max(self.stats.entries);
    }
    pub fn is_current(&self, result: &QueryResult) -> bool {
        !self.closed
            && result.owner == self.owner
            && Some(result.project_version) == self.version
            && !result.cancellation.is_cancelled()
    }
    /// Call on the serialized editor lane immediately before publishing or applying an edit.
    pub fn accept<'a>(&self, result: &'a QueryResult) -> Result<&'a str, Failure> {
        self.open()?;
        if result.cancellation.is_cancelled() {
            return Err(Failure::Cancelled);
        }
        if !self.is_current(result) {
            return Err(Failure::Stale);
        }
        Ok(&result.value)
    }
    pub fn stats(&self) -> Stats {
        self.stats
    }
    pub fn retained_inputs(&self) -> usize {
        self.inputs.len()
    }
    pub fn close(&mut self) {
        self.inputs.clear();
        self.executors.clear();
        self.cache.clear();
        self.order.clear();
        self.stats.entries = 0;
        self.stats.bytes = 0;
        self.closed = true;
    }
}
struct Evaluation<'a> {
    db: &'a mut SemanticQueries,
    cancellation: Cancellation,
    staged: BTreeMap<Key, Cached>,
    order: Vec<Key>,
    active: BTreeSet<Key>,
    steps: usize,
    hits: u64,
    evaluated: u64,
    broken: Option<Failure>,
}
impl Evaluation<'_> {
    fn checkpoint(&self) -> Result<(), Failure> {
        if self.cancellation.is_cancelled() {
            Err(Failure::Cancelled)
        } else {
            self.broken.map_or(Ok(()), Err)
        }
    }
    fn stage(&mut self, key: Key, value: Cached) {
        self.order.retain(|k| k != &key);
        self.order.push(key.clone());
        self.staged.insert(key, value);
    }
    fn visit(&mut self, key: &Key) -> Result<Cached, Failure> {
        let result = self.visit_inner(key);
        if let Err(error) = result {
            self.broken = Some(error);
        }
        result
    }
    fn visit_inner(&mut self, key: &Key) -> Result<Cached, Failure> {
        self.checkpoint()?;
        if key.kind.is_empty() || key.identity.is_empty() {
            return Err(Failure::Invalid);
        }
        self.steps += 1;
        if self.steps > self.db.limits.steps || self.active.len() >= 128 {
            return Err(Failure::Limit);
        }
        if self.active.contains(key) {
            return Err(Failure::Cycle);
        }
        if let Some(cached) = self
            .staged
            .get(key)
            .or_else(|| self.db.cache.get(key))
            .cloned()
        {
            self.hits += 1;
            self.stage(key.clone(), cached.clone());
            return Ok(cached);
        }
        let executor = self
            .db
            .executors
            .get(&key.kind)
            .cloned()
            .ok_or(Failure::Unsupported)?;
        self.active.insert(key.clone());
        let result = (|| {
            let mut context = Frame {
                evaluation: self,
                reads: BTreeMap::new(),
                children: vec![],
            };
            let value = executor.execute(key, &mut context)?;
            context.checkpoint()?;
            let bytes = 64
                + key.weight()
                + value.len()
                + context.reads.keys().map(|k| 16 + k.len()).sum::<usize>()
                + context
                    .children
                    .iter()
                    .map(|d| 16 + d.key.weight())
                    .sum::<usize>();
            let inputs = context.reads;
            let queries = context.children;
            let cached = Cached {
                value,
                revision: self.db.next_revision()?,
                inputs,
                queries,
                bytes,
            };
            self.stage(key.clone(), cached.clone());
            self.evaluated += 1;
            Ok(cached)
        })();
        self.active.remove(key);
        result
    }
}
struct Frame<'a, 'b> {
    evaluation: &'a mut Evaluation<'b>,
    reads: BTreeMap<String, u64>,
    children: Vec<Dependency>,
}
impl Frame<'_, '_> {
    fn bounded(&mut self) -> Result<(), Failure> {
        if self.reads.len() + self.children.len() > self.evaluation.db.limits.dependencies {
            self.evaluation.broken = Some(Failure::Limit);
            Err(Failure::Limit)
        } else {
            Ok(())
        }
    }
}
impl Context for Frame<'_, '_> {
    fn input(&mut self, key: &str) -> Result<Option<Input>, Failure> {
        self.checkpoint()?;
        if key.is_empty() {
            return Err(Failure::Invalid);
        }
        self.reads
            .insert(key.into(), self.evaluation.db.input_revision(key));
        self.bounded()?;
        Ok(self
            .evaluation
            .db
            .inputs
            .get(key)
            .map(|input| input.input.clone()))
    }
    fn query(&mut self, key: Key) -> Result<String, Failure> {
        let cached = self.evaluation.visit(&key)?;
        self.reads.extend(cached.inputs);
        self.children.push(Dependency {
            key,
            revision: cached.revision,
        });
        self.bounded()?;
        Ok(cached.value)
    }
    fn checkpoint(&self) -> Result<(), Failure> {
        self.evaluation.checkpoint()
    }
}
