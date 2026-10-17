# Tiny code fences in Classic token modes

Java's existing `CodeStartParser`, `CodeEndParser`, and `LongCodeBlockParser`
now have corresponding Rust runtime paths under `parse_classic` and
`parse_with_token_mode`. The ordinary native scanners and existing FQN/adapter
bindings remain available. The baseline is the actual TinyExpression source at
`7d7bd1cbfab1bd9548ba53c73cc50502f0437c67`, compiled on Java 21; tests verify each
loaded class's code source to exclude a same-named test replacement.

`CodeStart` and `CodeEnd` mirror the Java parser graph. They require a line
boundary, then parse the triple backtick, ASCII names and punctuation where
applicable, and CRLF/CR/LF/EOF. No outer grammar trivia is inserted between these
internal parts. The graph preserves child token kinds, both cursors and retained
leaf sources. Inversion fails at the line-boundary assertion; an explicit
inversion stopper can restore normal recognition. Consume and match-only
wrappers retain their existing Classic meaning.

`LongCodeBlock` remains one atomic input token. It recognizes a fence width of
at least four backticks and an exact-width closing line; the body is opaque,
including Unicode and shorter fences. Failure preserves an independently
advanced matched cursor. Success reads from the selected kind's cursor and
updates the consumed or matched cursor according to Java's token-kind contract.
In particular, virtual-consumed uses the matched cursor for its input slice and
advances the consumed cursor by the recognized CP width. Result source and cursor
end therefore need not be identical when the cursors differed at entry.

The matching graph does not execute code blocks or copy Java parser object/tag
identity. Java and Rust retain their existing diagnostic hint spelling; the
shared observations compare the farthest CP and whether an expectation exists.
These are development changes after 3.3.0, tracked by #573 within #545.

## Evidence and reproduction

`conformance/tiny-classic-modes/cases.tsv` fixes acceptance, both CP cursors,
result text and extent independently. Its 744 cases cover four kinds, inversion,
LF/CRLF/CR/EOF, malformed widths/names, consume/invert stoppers, match-only
wrappers, differing prior cursors, both reset policies, supplementary characters,
and family ZWJ text. Each language runs both memo policies and all three
diagnostic policies: 4,464 observations. The Java test also compares actual
retained leaf kind/source/text and diagnostics against the Rust executable.
Rust additionally compares trace on/off and the committed trace with those leaf
sources. Both implementations exercise 1,500 wrappers on a 2 MiB thread and
return a controlled resource failure with cursors restored.

The existing generated FQN and adapter corpus (14 grammars, 86 inputs per route)
is rerun with the same pinned real Java classes, both Rust generator hosts,
actual compilation, values and source positions. Generated Rust consumers also
exercise the explicit Classic entry: prefix cursors and canonical AST values,
including source CP ranges, agree for every fixture, including grammars with
outer whitespace. The #606 sequence delimiter fix collects those trivia sources;
the Classic AST comparison has no outer-whitespace exemption. CI's downstream job supplies
the pinned classes and requires the Java/native observation files.

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=TinyClassicModeConformanceTest,TinyCodeFenceConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtinyexpression.classes=/path/to/pinned/tinyexpression/target/classes
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml -p unlaxer-runtime
```
