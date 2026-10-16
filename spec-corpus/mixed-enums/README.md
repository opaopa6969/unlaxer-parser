# Mixed enum oracle

`cases.json`, `shared-cases.json` and `root-cases.json` fix values, enum identity,
order, CP spans, literal text and rejection independently of generated code.
`manifest.json` selects separate enum/Text, enum/Node, enum/enum and fully mixed
choices. The test also exercises scalar fields, shared records in both declaration
orders, root choices and the same enum used as a root and a captured value.
Empty and absent captures, repeated identical values, invalid/trailing input,
CRLF, supplementary characters and a ZWJ family sequence are included.

Nine grammar shapes contain 116 inputs. Java and Rust each execute every input
with two memo modes and three diagnostic modes (696 observations). Java retains
old maps after later successful/failed parses and runs parallel mapping calls.
Both hosts generate the same Rust modules, including DAP; actual generated
Java/Rust evaluators and DAP AST traversal run against the same expected values
and node label/span oracle. Only mapped nodes are DAP AST steps, while enum
occurrence values retain their own source-map spans.

`MixedEnumConformanceTest -DrustConformance=true` requires JDK21 and Rust1.85.
CI requires `target/mixed-enum-java.tsv` and `target/mixed-enum-rust.tsv`.
The Java-only `@typeof` composition regression preserves underlying enum identity;
Rust constraints remain an explicit #542 follow-up rather than claimed parity.
