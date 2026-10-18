# Memoized results and resource depth

`cases.tsv` is read by Java `MemoResourceDepthTest` and Rust `memo_resource_depth`.
Each host evaluates all 12 cases under OFF/SAFE_FAILURES and all three diagnostic
policies (72 normal observations per host). Rust additionally executes the same
72 observations through the Classic consumed-token entry. Normal and Classic
same-depth and shallower rows require nonzero actual success and failure hits;
deep retries conservatively miss in both modes. Java uses its consumed-token parser entry for all rows.
No limit is raised and candidate exclusion keeps its default behavior.

The target is 64 optional wrappers around `z`, followed by EOF (success) or a
negative EOF assertion (failure), on empty input. The assertion keeps the target
nullable for conservative FIRST analysis so warming cannot be pruned outright.
The warm transaction is rolled back before the retry. A retry has the specified
number of additional optional wrappers. Resource rows require the sticky original
resource failure and unchanged consumed/matched positions. Ordinary rows check
acceptance and actual target memo hits, including reuse from a shallower call.

A cached result may only replay with at least the resource budget of its original
invocation. A deeper call misses conservatively; same-depth and shallower calls
retain reuse. Resource failures themselves are never cached. Existing parser-state
keys and Java public PositionKey constructors are unchanged. Rust retains its
separate rule-depth key; this guard also covers expression wrappers that do not
increase rule depth.

The baseline accepted `failure_limit` with memo enabled, although OFF hit the
1024-frame guard. Both hosts now return the resource failure. Success rows cover
the equivalent skip path. Tests use an explicit 2 MiB native thread stack to
reach the library guard deterministically, independently of JVM/Rust defaults.

Deeper retries deliberately miss even when a cached result could happen to finish
within the remaining budget. This conservative guard can increase work and
transaction counts; it does not change the public key ABI or successful CST.
The checked-in parse-equality golden records those counter changes separately.

The complementary `recursion_budget` tests exercise rule-depth and public-entry
limits and optional/choice/recovery propagation. `ResourcePublicationTest` and
Rust `resource_publication` assert the Classic resource-abort cursor, capture,
state, CST/metadata rollback and refusal to reuse the same context. These are
separate runtime checks, not claims that the shared memo fixture covers every
resource boundary. Trace-enabled success replay remains outside this contract.
