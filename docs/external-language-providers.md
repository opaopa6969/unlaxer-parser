# External analysis providers

`ProviderProtocol` / `provider_protocol` and `ProviderProcess` / `provider_process`
connect an explicitly registered analysis command to the same snapshot, region and
query contracts used by generated embedded grammars. Registration does not discover
or launch commands from a grammar, dependency package, project file or source text.
Java and Rust hosts use identical requests and independently specified observations.

## Shipped profiles

| Identity / fixed version | Entry | Operations and analysis | Deliberate limits |
| --- | --- | --- | --- |
| `javac` / `21.0.9` | Java `CompilationUnit` | `PARSE`, `VALIDATE`, `HOVER`, `DEFINITION`, `COMPLETION`; actual compiler types and explicit dependency sources | No bytecode generation, annotation processing, compiler plugins or program execution. Java source levels 8/11/17/21. |
| `typescript` / `5.9.3` | TypeScript `SourceFile` | Same operations; language-service types, flow narrowing and completion edits | Explicit virtual project sources and the pinned compiler's standard library only. No tsconfig discovery, plugins, ambient workspace files, package download or program execution. Target ES2022; strict defaults true. |
| `rustc` / `1.85.0` | Rust `Crate` | `VALIDATE`; types, ownership, lifetimes, match exhaustiveness, macro diagnostic origin chains | Direct rustc metadata compilation only. No parser-only entry, hover/completion, rust-analyzer, expanded source, Cargo, build scripts, external crates or procedural macro loading. Editions 2018/2021/2024. |

Capabilities are returned explicitly. `PARSE` does not promise type checking; the
same Java type-invalid fixture succeeds under PARSE and returns diagnostics under
VALIDATE. Rust's profile does not advertise PARSE. Unsupported entries/options return
UNSUPPORTED. Missing or mismatched compiler versions return UNAVAILABLE, not success.
TypeScript's unresolved-name diagnostic can accompany useful completion items.

Java uses `-proc:none`, an empty source path, and an empty classpath unless the caller
explicitly supplies `classpath`. It parses/analyzes without generating classes. A
real service-discovered annotation processor sentinel is first verified with explicit
processing enabled; neither host executes it during provider analysis.

Rust copies explicitly supplied `.rs` snapshots into a temporary directory and
invokes a fixed compiler executable with `--emit=metadata --error-format=json`.
The profile conservatively refuses the words `include`, `include_str`,
`include_bytes`, `env`, `option_env` and `path` anywhere in source, including comments
and strings. This restriction keeps compiler input within the supplied snapshots;
it can reject harmless source and is not a general Rust sandbox. Declarative macro
expansion is compiler analysis; diagnostics retain both primary and expansion-call
locations. Compensating for incomplete macro information by inventing source edits
is not supported.

All three profiles reject `executeUserCode=true`. This flag describes user code,
not the trusted compiler/adapter process itself. Adapters are trusted executables
registered by the caller, not a security boundary for arbitrary third-party commands.
The Java host terminates descendants on cleanup; the std-only Rust host terminates
and waits for its direct child. Shipped Python adapters impose their own compiler
subprocess deadline (default 10s), so register them with a longer host deadline (the
fixture uses 30s). Descendant-tree cancellation for arbitrary Rust host commands is
not promised by this portable transport.

## Protocol version 1

A request is an ASCII, newline-terminated frame. Tab-separated string fields use
UTF-8 hex; integer positions are Unicode code-point offsets and half-open ranges.
Snapshot and project versions are in `0..=9223372036854775807` in both languages.

```
UNLAXER-PROVIDER<TAB>1
request<TAB>idHex<TAB>providerHex<TAB>versionHex<TAB>OPERATION<TAB>cursorCP<TAB>false
project<TAB>idHex<TAB>version
language<TAB>idHex<TAB>packageHex<TAB>versionHex<TAB>grammarHex<TAB>entryHex<TAB>regionHex
snapshot<TAB>uriHex<TAB>version<TAB>textHex
document<TAB>uriHex<TAB>version<TAB>textHex
config<TAB>keyHex<TAB>valueHex
parameter<TAB>keyHex<TAB>valueHex
end
```

Document/config/parameter rows are optional and key-sorted by the canonical encoder.
The response header echoes request ID, provider identity/version, project identity/
version and the complete pre-`end` request frame encoded in hex. `Frame.fingerprint`
is this exact echo, **not a cryptographic hash**. Bounded complete equality avoids
hash collisions and an additional runtime dependency while binding source contents,
cursor, region, entry and all configuration to a response.

Response rows are `capability`, `diagnostic`, `origin`, `item`, `location` and `edit`; additional origin rows retain one diagnostic identity across macro expansion locations, and item
references must point to an earlier item. See the committed `protocol/*.wire` fixtures
for exact layouts. Unknown fields, duplicate singleton keys/capabilities, malformed
UTF-8, stale identities/snapshots, unknown documents, invalid ranges and inconsistent
status/payload are rejected. Request bodies are limited to 512 KiB; response and
stderr streams to 4 MiB each. A malformed response is a protocol error; it is never
silently converted to successful empty results.

Statuses are OK, DIAGNOSTICS, UNAVAILABLE, UNSUPPORTED, TIMEOUT and FAILED. Spawn
failure is UNAVAILABLE; nonzero exit or output-limit failure is FAILED. DIAGNOSTICS
may carry query items; other unsuccessful states cannot carry edits/items.

## Source ownership and generated grammars

`process.grammar(language, project, parameters)` (Java) and `GrammarAdapter` (Rust)
implement the generated embedding registry's leaf grammar contract. The shared
FormulaInfo and TinyExpression UBNF fixtures call real javac at CompilationUnit,
including package/import/class source rather than a block-only entry.

`mapDiagnostics` / `map_diagnostics` maps only locations belonging to the exact
virtual snapshot. A diagnostic from another explicit project document retains that
document's coordinates. COPY segments produce exact original locations; transformed
or generated segments produce non-exact anchors. Inverse edits through these
segments remain prohibited by `SegmentSourceMap` / `SourceMap`.

`ProviderProcess` also implements the `LanguageQueries` provider interface. The
three-layer test forwards a Java definition query to `Dep.java` without applying
its host map to the foreign definition. A real TypeScript completion retains its
PARTIAL state while its replacement range is translated into the host document.
Stale query contexts are rejected before invoking the provider.

## Reproduce

Install JDK 21.0.9 and Node 20.20.0, then run:

```sh
rustup toolchain install 1.85.0 --profile minimal
npm ci --prefix unlaxer-dsl/ubnf-vscode --ignore-scripts --no-audit --no-fund
mvn -pl unlaxer-common,unlaxer-dsl -am test -Dtest=ProviderProtocolTest,ExternalProviderConformanceTest,PlaygroundCommandTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test provider_protocol
```

`ExternalProviderConformanceTest` explicitly skips unless `-DrustConformance=true`;
the dedicated `external-language-providers` workflow supplies pinned tools and runs
it without skips. `cases.json` supplies 22 independent source/position/result cases;
a generated annotation-processor sentinel adds the 23rd. Both hosts invoke the same
three actual adapters, compare full observations and write the report to
`unlaxer-dsl/target/external-provider-conformance.tsv`. Protocol fixtures contain 31
accepted/rejected frames; transport fixtures exercise four distinct failure states.

Commands are argument arrays, with no shell:

* Java: `<java> -cp <application-classpath> org.unlaxer.dsl.provider.JavacProvider`
* TypeScript: `python3 scripts/language-providers/provider.py typescript <absolute-typescript.js>`
* Rust: `python3 scripts/language-providers/provider.py rust <absolute-rustc-1.85.0>`

The TypeScript installation is already locked by `unlaxer-dsl/ubnf-vscode/package-lock.json`.
No production dependency is added to the Rust runtime. Process invocation is a native
host capability; a browser/WASM editor requires an explicit native bridge and cannot
claim that the compiler is installed merely because these modules are bundled.

## Consumer integration and limits

| Consumer or contract | Current support |
|---|---|
| Generated Java LSP / Classic Rust LSP | Explicit provider registration, profile capability intersection, typed diagnostics with exact snapshot versions; completion/hover/definition hooks |
| Generated Java/native Rust Playground | Registered capability display and source-mapped query/edit dispatch; native compiler processes require an explicit bridge outside the browser |
| Language profiles and packages | Explicit profile selection and locked package entry loading; no automatic provider discovery |
| Partial embedded regions | Delimiter state, empty-body anchors, updates and EOF ownership are implemented; strict parsing remains distinct from editor recovery |
| TinyExpression | Shared Java/Rust bridge corpus uses pinned `f86ce8a5`; the existing Java LSP consumer additionally covers `0d84f0dc` long fences and EOF completion. See the [production bridge](tinyexpression-production-bridge.md) for exact versions and limits |

`LSPDiagnosticConformanceTest` exercises real javac diagnostics through both LSP
consumers. `SemanticDiagnosticConformanceTest` exercises the same typed diagnostic
contract with generated parsers and declarative semantic rules. The explicit
compiler profiles above remain limited: no automatic discovery, Cargo/build.rs,
procedural-macro execution or full rust-analyzer language service is implied.
Shared-input grammar calls are a separate parser API from bounded region dispatch.

Implementation references: [JavaCompiler API](https://docs.oracle.com/en/java/javase/21/docs/api/java.compiler/javax/tools/JavaCompiler.html),
[TypeScript Language Service API](https://github.com/Microsoft/TypeScript/wiki/Using-the-Language-Service-API),
[rustc JSON diagnostics](https://doc.rust-lang.org/rustc/json.html).
