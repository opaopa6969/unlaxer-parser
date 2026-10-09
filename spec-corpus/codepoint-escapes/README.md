# v2 scalar escapes

v1 retains its original behavior. `@ubnf: 2` is normalized to `@ubnf: v2` in the frontend.
Only `CHAR_RANGE` and `NEGATION` argument quotes decode scalar escapes, including those in
v2 declarative token expressions. Raw quote decoding avoids expanding escaped backslashes twice.
Supplementary legacy-style ranges lower to the existing declarative RANGE program; BMP ranges
retain the existing public `char` record. Ranges may not span the surrogate interval.

`invalid.json` fixes diagnostic codes, literal CP spans and line/column independently for malformed
hex/length/overflow/surrogate/ranges in both legacy-style and nested declarative arguments after a supplementary comment with CRLF. Runtime cases in
`../declarative-tokens/runtime.json` compare scalar ranges, control character exclusion, supplementary
values/AST spans, rejection and escaped-backslash/literal preservation across both hosts/backends.
The shared frontend positive fixture also checks the numeric version alias and nested lexical arguments.

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test -Dtest=CodePointEscapeConformanceTest,DeclarativeTokenConformanceTest,RustUbnfFrontendConformanceTest,RustUbnfSourceConformanceTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --workspace --locked --manifest-path rust/Cargo.toml
```
