# Recursive helper oracle

`generated-cases.json` supplies independently written values, CP spans, failure
stages and evaluator consumers for Java and Rust. The Rust probe observes
canonical values and explicitly checks context rollback after failure. The Java
observer uses record reflection and identity source maps. All five Rust files
from Java/native hosts are compared before real compilation and execution.

The corpus covers direct/mutual recursion, scalar/text/node/enum/mixed values,
optional/list collections, repeated equal values, inner capture isolation,
nullable text separators, no finite tail, supplementary scalars and combining
sequences. Deep input has an expected controlled `limit` status, not a
platform-specific maximum frame count. See docs/recursive-helpers.md for semantics.
