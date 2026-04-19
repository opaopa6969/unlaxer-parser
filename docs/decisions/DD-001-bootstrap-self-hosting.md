# DD-001: Bootstrap and Self-Hosting Strategy

**Status**: Accepted  
**Date**: 2026-04-12  
**Issue**: opaopa6969/unlaxer-parser#4  

---

## Context

unlaxer-dsl reads `.ubnf` grammar files and generates Java source code. The core UBNF parsing logic (`UBNFParsers`, `UBNFAST`, `UBNFMapper`) was hand-written in Java. This created an asymmetry: the tool generates code from grammars, but its own grammar was not expressed in UBNF.

## Decision

Express the UBNF grammar in UBNF (`grammar/ubnf.ubnf`) and use unlaxer-dsl to generate the bootstrap files into `org.unlaxer.dsl.bootstrap.generated`. The hand-written bootstrap files (`org.unlaxer.dsl.bootstrap`) are retained as a frozen reference.

The self-hosting is validated by `SelfHostingTest`, which parses `ubnf.ubnf` with both the hand-written and generated parsers and asserts the outputs match.

## Consequences

**Positive**:
- Every UBNF language feature is now exercised by the self-hosted parse of `ubnf.ubnf`.
- Grammar regressions are caught immediately.
- `ubnf.ubnf` becomes the canonical machine-readable specification of UBNF syntax.
- Dogfooding: any bug that makes `ubnf.ubnf` unparseable is a P0 bug.

**Negative**:
- The bootstrap cycle requires careful ordering in the Maven build.
- Two copies of the bootstrap files must stay in sync during transitions.
- Developers who modify `UBNFParsers` by hand must also update `ubnf.ubnf`.

## Rejected Alternatives

- **Keep hand-written parsers only**: Does not provide the completeness guarantee. Any feature added to UBNF that was not in the hand-written parser would be silently undetectable.
- **Replace hand-written parsers entirely**: Risky. The generated parsers depend on the hand-written ones for the first generation cycle. The two-track approach allows graceful transition.
