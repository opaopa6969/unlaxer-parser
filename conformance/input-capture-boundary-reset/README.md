# Input AST boundary ordinary-failure reset (#642)

The shared eight rows cover four TokenKinds and both initial reset policies on
input `?`, with `InputCaptureBoundary(Value, literal a)`. Java `CaptureBoundaryTest`
and Rust `capture_boundaries` expand each row over two memo options and three named
diagnostics: 48 observations per language. Both tests save every actual row before
comparing the independent fixture, so a first failure does not hide later cases.

An ordinary syntax failure leaves both cursors at0, no new CST tokens/nodes, and
reset=false as required by Java Chain. A forced outer rollback restores the initial
reset and empty CST. Java also checks the remaining owner frame. This exercises
memo configurations, not a positive memo-hit claim. Resource failures retain their
separate full rollback contract. Two input-boundary rows extend the existing resource-abort oracle (manual owner/no manual owner ×six modes =12 new observations per language), retaining the original72 observations, metadata/state/capture/cursor rollback and sticky retry checks. Resource-depth tests remain unchanged.

The existing three-variant `nullable-separation/chain-reset.tsv` corpus remains
unchanged (24rows /144 observations per language). Input AST provenance, generated
or projected-source rejection, shape/depth, retained snapshots and parser reuse are
covered separately by typed/owned/nested generated conformance suites.
