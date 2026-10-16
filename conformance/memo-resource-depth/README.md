# Memoized results and resource depth

`cases.tsv` is read by Java `MemoResourceDepthTest` and Rust `memo_resource_depth`.
Each host evaluates all 12 cases under OFF/SAFE_FAILURES and all three diagnostic
policies (72 observations). No limit is raised and candidate exclusion keeps its
default behavior.

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
