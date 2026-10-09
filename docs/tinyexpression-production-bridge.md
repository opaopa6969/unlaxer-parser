# TinyExpression production bridge

`examples/tinyexpression-production` is an explicit native integration of the **real**
TinyExpression FormulaInfo and P4 parsers with `LanguageRegions` / `LanguageQueries`.
The runtime has no TinyExpression dependency. The example links a pinned Tiny checkout;
no source file, grammar or package can launch an analysis command implicitly.

The source pin is `f86ce8a5ab0ab7d23fdba2cd477187df8aa7607c` from
[opaopa6969/tinyexpression](https://github.com/opaopa6969/tinyexpression/tree/f86ce8a5ab0ab7d23fdba2cd477187df8aa7607c).
Its Java POM is 2.0.1 and Rust package is 2.0.0; the bridge uses the **commit identity**
for both Tiny language identities. `original.txt` is the byte-identical MIT-licensed
`src/test/resources/formulaInfo-test/69/formulaInfo.txt` at that pin. **69 is the fixture
number, not the number of examples.** It contains ten formulas, two with embedded Java.
The two `tiny69-*` entries in the language-profile corpus cover only those Java bodies.

| Layer | Java host | Rust host | Boundary |
| --- | --- | --- | --- |
| FormulaInfo | Actual `FormulaInfoBlocksParser` and value tokens | Actual `tinyexpression_rs::formula_info::parse_document` | Full document consumption, all formula fields; no calculator construction |
| P4 | Actual vendored ubnfc parser with `P4Scanners.ALL` | Actual vendored ubnfc parser with `scanners::registry()` | Full Formula entry, adopted lexical occurrences; no handwritten P4 recognizer |
| Java region | Actual P4 fence token boundaries | Actual P4 fence token boundaries | Original text between opening and closing fences, including leading Java comments |
| Java analysis | Explicit `ProviderProcess` running javac 21.0.9 | Same real compiler via native process transport | Parse/type analysis, completion, hover, definition; no code generation/evaluation |
| Classic compatibility | Actual `P4PreferredAstMapper` under CLASSIC and UBNFC | Rust native P4 path above | Ten real formulas per Java engine; this does not turn Classic into ubnfc or port full P4 to Classic Rust |

The Java leaf has the public profile identity
`java / lang/java / 0.1.0 / Java21 / CompilationUnit`; both probes check it against the
actual profile file. External javac is explicitly registered for this entry. That
registration does not enlarge the local UBNF Java21 subset. The existing profile
corpus separately checks the two production Java bodies against the local grammar.

## Binding a document

Compile the example against the pinned Tiny library and the current common runtime.
The Java call sequence is:

```java
var host = new DocumentSnapshot(uri, version, completeFormulaInfoText);
var binding = TinyProductionBridge.parse(host);
var project = new LanguageQueries.Project("project", projectVersion,
    Map.of(uri, host), Map.of("classpath", explicitCalculationContextClasspath));
var queries = binding.queries(project, registeredJavacProvider);
var diagnostics = queries.diagnosticsAll(host, project, Map.of());
var completion = queries.query(host, project, originalCodePointCursor,
    LanguageRegions.Operation.COMPLETION, Map.of("prefix", "tar"));
var edited = binding.tree().apply(host, nextVersion, completion.items().get(0).edits());
// Publish the new project snapshot and build a new binding before the next query.
```

Rust uses `bridge::parse(&host)`, `binding.queries(project, Box::new(provider))`,
`diagnostics_all`, `query` and `binding.tree()?.apply(...)` with the same contracts.
There is no manifest exported by one host and trusted by the other: both run their
own production FormulaInfo/P4 implementations. The provider decorator adds the
correct per-region Java basename from the parsed `java:qualified.Class` header;
conflicting caller filenames are rejected. Package declarations remain in the Java
source. The test uses the **real production CalculationContext classpath**, not a stub.

Build a new binding for every edited source/version; publish matching Project contents
and configuration together. Query contexts reject changed text even at the same version,
changed document/project versions, and changed configuration. Region IDs are positional
identifiers within a snapshot, not persistent symbol IDs. Reusing a prior query object
with a moved/deleted block is therefore rejected. Changes use exact COPY maps into the
host and atomic source edits; CRLF, Japanese text, emoji, metadata, comments and all
unmodified blocks are preserved. Diagnostic positions are host CP offsets and are also
checked as UTF-16 LSP positions. Closing fences belong to the Tiny parent, not Java.

FormulaInfo execution normalization drops blank/comment lines. This editor bridge instead
keeps original source slices and masks FormulaInfo `#` comment lines only for P4 recognition,
with one space per code point. Java is analyzed from its original slice. No semantic facts
from the normalized execution source are reattached using a constant line offset.
The production Java and Rust scanners differ in whether fence spans include line endings;
the bridge explicitly normalizes that boundary and verifies the resulting exact slices.

## Failure and support limits

* Invalid outer FormulaInfo, no formula, duplicate formula fields, or empty terminal values
  are rejected. This is a source view; calculator names, execution backends, dependency
  graphs and execution semantics are not loaded or validated. The lexical bridge always uses
  the pinned ubnfc parser; `p4Engine` execution metadata does not switch this extraction route.
  Classic is checked through its public facade as a separate compatibility observation.
* An unsuccessful P4 formula is FAILED and contributes **no speculative Java children**.
  Complete sibling formulas remain. An unclosed Java fence is consequently visible as a
  failed Tiny formula, not a successful or editable Java body. Retaining completion inside
  that unfinished production fence requires an additional production recovery contract.
* The native example limits input to 1,048,576 code points, 256 formulas and P4 depth 512.
  The provider's bounded request/response and 30-second process timeout still apply.
  Full-document reparsing is used; this is not an incremental parser or performance claim.
* Only a caller-supplied Java provider is registered. FormulaInfo/Tiny semantic providers,
  cross-language type sharing, formatter, rename, code actions, evaluation, arbitrary
  language schemes and browser process execution are not implemented here. Missing
  providers and unsupported operations remain explicit query statuses.
* The old Tiny `DocumentFilter`/`TinyExpressionP4LanguageServerExt` still select the first
  formula and use their legacy line-offset route. Their behavior is regression-tested,
  not silently replaced. The new binding is the opt-in source/query adapter to attach to
  generated consumer hooks; installing that adapter in the shipped Tiny extension and
  supplying partial production fence recovery remain parent #369 work. This slice does
  not close #369/#370/#383 by itself.

## Reproduce and independently check

Install JDK 21.0.9 and Rust 1.85.0, then check out the exact Tiny pin into a clean directory:

```sh
python3 scripts/check-tiny-production.py /absolute/path/to/pinned-tinyexpression
```

The command builds both current host modules, the real Tiny library, and the existing
Tiny FormulaInfo/P4/source-preserving and LSP/DAP regression tests. It then independently
compiles the Rust production library and runtime with `rustc` (no Cargo dependency added),
and runs both bridges against committed sources and `expected.tsv`. `--maven-repo` chooses
an isolated Maven cache; `--skip-build` is only for repeating bridge probes after those
builds/tests have passed. The dedicated CI runs the full command, checks skipped-test
counts, and uploads both reports. No assumption or opt-in JUnit skip can satisfy this gate.

Fixtures include all ten original formulas, Unicode+CRLF and positioned compiler errors,
completion with an exact whole-document edited oracle, actual hover/definition locations,
block insertion/move/deletion, unclosed sibling preservation, masked comments and rejected
outer documents. Expectations are committed CP spans, compiler codes, item types, UTF-16
positions and replacement text; one backend's output is never the other's expected result.
`target/tiny-production/{java,rust,compatibility}.tsv` records the observations.
