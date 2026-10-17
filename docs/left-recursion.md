# Left recursion at the generation boundary

Java and both Rust source-generation hosts reject untransformed recursion that
can return to the same rule without consuming input. The error contains
`E-LEFT-RECURSION`. This applies to direct and mutual cycles, including cycles
behind nullable helpers, groups, optional/repeated bodies and zero-width tokens.
A grammar such as `Root ::= Root '+' Term | Term` must be rewritten using a
consuming base and repetition, for example `Root ::= Term { '+' Term }`.

Java previously reported an advisory warning but could still emit a parser that
reached a runtime recursion limit. Java's parser/AST/mapper/evaluator generators
now refuse these cycles before emission, matching the existing Rust boundary.
`@leftAssoc` and `@rightAssoc` describe supported mapping/rewriting patterns; they
do not turn arbitrary left-recursive grammars into a supported parser. Existing
consuming associative grammars and input-consuming right recursion are preserved.

The source analysis computes nullable rule references to a finite fixed point,
then follows edges reachable before input consumption. `ERROR` stops that path.
Unknown imported/custom tokens are conservatively considered possibly nullable;
no Java class is instantiated to prove progress. The analysis has a depth ceiling
of 256 and a work budget of 1,000,000 visits. Exceeding them produces
`E-LEFT-RECURSION-LIMIT`, not a claim that a cycle exists. Other Rust analyses have
their own documented limits and may reject a grammar before this phase.

The legacy `validateWithWarnings`/`detectLeftRecursionIssues` API remains advisory
and nonthrowing. It now uses the same bounded nullable analysis, including
nullable-helper cycles previously missed, and reports `W-LEFT-RECURSION-LIMIT`
when it cannot finish within its bounds. Generation and strict validation still
reject the corresponding error. Cycle reports follow deterministic declaration
order; they do not enumerate all possible paths through a strongly connected graph.

An ordinary `{0}` does not execute its body, so its leading references are
excluded from cycle analysis. `Root ::= Root{0} 'x'` therefore parses `x` without
recursing. Reachable successors are still checked: `Root ::= Root{0} Root | 'x'`
is rejected. Explicit historical `Zero`/`LazyZero` and EOF checks retain their
separate one-probe contract; see [zero-repetition.md](zero-repetition.md).

`spec-corpus/left-recursion/cases.json` fixes common accept/reject decisions.
`LeftRecursionConformanceTest` checks Java's generators, both Rust source hosts,
actual generated Java/Rust compilation and parsing, Unicode CP consumption,
memo/diagnostic configurations, and the small-stack resource boundary. The
existing associative and recursive-helper corpora remain separate regressions.
No automatic left-recursion transformation or left-recursive packrat algorithm is
introduced. This development change is not in the published 3.3.0 artifacts.

Javaのsource解析ではSequenceBodyからatomへの表現上のwrapperを追加の深さとして数えません（#618）。既存の64/96重groupを両hostで受理し、実生成parser/mapperとCP位置を共通corpusで確認します。depth/work上限と、真の非消費cycle・過大なrule chainの拒否は維持します。
