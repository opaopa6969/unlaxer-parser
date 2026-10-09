# Bounded analysis phase pipeline

`org.unlaxer.pipeline.AnalysisPipeline` and `unlaxer_runtime::pipeline` provide
matching Java/Rust contracts for coarse analysis phases. This is an incremental
foundation for #380, built on the source snapshot/map contracts from #394.

## Phases and artifacts

A phase has an ID, dependency phase IDs, named snapshot inputs, configuration keys,
and an explicit `executesUserCode` / `executes_user_code` capability declaration.
Definition order does not determine execution order: demand evaluation follows
dependencies, making forward phase references work. Duplicate definitions/keys
and missing dependency definitions are errors. Cycles are accepted as a graph
shape but evaluation returns CYCLE without invoking the cyclic executors.

An executor is explicitly registered for a phase and receives only that phase's
selected snapshots, configuration values and dependency artifacts. Executors are
fixed for the pipeline lifetime and must be deterministic over these inputs.
Replace the pipeline or clear its cache after changing an external tool version,
classpath, environment or executor behavior; preferably model such changes as a
declared snapshot/configuration input. Provider/project/version fingerprints may
be represented by configuration values.

Artifacts retain state, an opaque string payload, original source locations and
mapped diagnostics (including the exact/inexact flag). Multiple original files
and expansion/call-site anchors remain distinct locations. Source-map construction
and edit reversal remain the responsibility of the existing source-map layer;
the scheduler never invents positions or strips provenance. The payload is a
small common interchange slot, not a serialized Java/Rust AST protocol.

| State | Meaning | Cached? |
|---|---|---|
| COMPLETE | Phase produced its complete artifact | Yes |
| PARTIAL | Usable partial/recovered artifact | Yes |
| FAILED | Finished with an explicit analysis failure | Yes |
| INACTIVE | Excluded by conditional configuration | Yes |
| DEFERRED | Declared input unavailable or executor cannot yet finish | No |
| CYCLE | Demand evaluation encountered an active dependency | No |
| LIMIT | Demand traversal exhausted its phase budget | No |
| UNSUPPORTED | Executor unavailable or user-code execution not permitted | No |

COMPLETE/PARTIAL/FAILED/INACTIVE artifacts are passed to downstream executors.
A downstream executor explicitly chooses to propagate, reject, skip, or repair
those results. For example a repair phase may intentionally turn PARTIAL into
COMPLETE while retaining original diagnostics. The scheduler does not call a
failure a success on its own. Deferred/cycle/limit/unsupported states stop the
current dependency chain and never reuse an old successful dependent artifact.
Executor exceptions/returned errors propagate to the caller; they do not create
cache entries or turn into an empty success.

## Bounds, execution capability and caching

Each evaluation chooses a traversal budget from 0 to 256 distinct demanded
phases. Cached phases also consume that budget, and shared completed dependencies
are visited once per evaluation. The hard bound keeps recursive traversal finite;
parser recursion/backtracking belongs inside the parser, not this phase graph.
No fixed-point solver for recursive types/macros is implied by cycle detection.

A user-code phase requires both its capability declaration and evaluation's
`allowUserCode` setting to be true before its executor can run or return cached
results. Missing providers remain UNSUPPORTED. This is a trusted adapter contract,
not a process sandbox: an incorrectly declared executor can still execute code.
There is no automatic process launch, macro execution, or runtime deadline.

Cache signatures include complete selected snapshot identities (URI, version,
text), presence/value of selected configuration keys, and dependency artifact
revisions. Include edits, main-document edits, and relevant configuration changes
invalidate their dependent artifacts. Unrelated settings do not. An INACTIVE
artifact is separate from FAILED. Cache size is bounded by the phase definitions;
`clearCache` / `clear_cache` releases cached artifacts without resetting revision
numbers. A single pipeline instance is used by one caller at a time.

Every completed/cached result exposes an optional artifact revision. Revisions
increase when a phase is evaluated and remain stable on cache reuse; unfinished
results have no revision. The number is scoped to that pipeline instance, so a
semantic-query key should include the pipeline instance/project identity plus
phase ID and revision. This layer does not implement completion-query caching,
cancellation, project disposal, or source-to-symbol indexing.

## Scope and parity

| Capability | Java | Rust |
|---|---|---|
| Phase identity, snapshots, dependencies and artifacts | Implemented | Implemented |
| Demand ordering, forward phase references, shared dependencies | Implemented | Implemented |
| Cycle/deferred/budget/capability states | Implemented | Implemented |
| Include/config invalidation and stable artifact revision | Implemented | Implemented |
| Source origins, generated-wrapper diagnostic transport, explicit repair | Implemented | Implemented |
| Language-level forward names, recursive type resolution, preprocessing syntax | Pending #380/#374/#375 | Pending #380/#374/#375 |
| Real macro/compiler executors and deadlines | Pending #378/#380 | Pending #378/#380 |

The common `docs/fixtures/pipeline/evaluations.tsv` specifies nine independent
expected state/payload/evaluated/reused outcomes. Both implementations consume it;
additional matching tests cover cycles, absent providers, permissions, stale
success prevention, budgets, repair, and generated Unicode diagnostic origins.

```sh
mvn -B -pl unlaxer-common -am test -Dtest=AnalysisPipelineTest -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test pipeline
```

The dedicated `analysis-pipeline` CI runs both commands. These APIs do not alter
UBNF frontend acceptance, mapper generation, or grammar import behavior.
