# Migration Guide: unlaxer-parser 2.x → 3.x

[日本語版](./migration-2.x-to-3.x-ja.md)

This guide covers everything a downstream project needs to move from unlaxer-parser 2.x (2.6.0 – 2.8.0) to the 3.x line. It consolidates the breaking changes reported by downstream migrations ([#27](https://github.com/opaopa6969/unlaxer-parser/issues/27), [#28](https://github.com/opaopa6969/unlaxer-parser/issues/28)).

## TL;DR checklist

- [ ] Bump `unlaxer-common` / `unlaxer-dsl` to the latest 3.x in your `pom.xml`
- [ ] Replace `new StringSource(...)` with `StringSource.createRootSource(...)`
- [ ] Replace removed string-handling classes (`StringBase`, `StringSource2`, `StringIndexAccessor*`) — see table below
- [ ] Fix the typo class `WildCardStringTerninatorParser` → `WildCardStringTerminatorParser`
- [ ] If you run codegen: switch the CLI entry point `UbnfCodeGenerator` → `CodegenMain`
- [ ] If you `instanceof`-check `UBNFAST.TokenDecl`: update for the sealed interface variants
- [ ] If you use `@mapping(..., params=[...])`: ensure params are in positional order
- [ ] Run `GrammarValidator.validateWithWarnings(grammar)` (or just run `CodegenMain`, which reports validation warnings on stderr) and resolve `W-LEFT-RECURSION` / `W-TOKEN-UNRESOLVED`
- [ ] `mvn clean test`

## Breaking changes in unlaxer-common

| Removed / changed (2.x) | Replacement (3.x) | Notes |
|---|---|---|
| `new StringSource(String)` | `StringSource.createRootSource(String)` | Removed in the StringSource/StringSource2 unification |
| `StringSource2` | `StringSource` | Renamed/unified |
| `StringBase` (1,042 lines) | Java 21 standard `String` / `Character` APIs | See `CodePointAccessor` for code point indexed access |
| `StringIndexAccessor`, `StringIndexAccessorImpl` | `CodePointAccessor` | |
| `WildCardStringTerninatorParser` (typo) | `WildCardStringTerminatorParser` | Constructor: `WildCardStringTerminatorParser(boolean, Parser)` |

APIs that did **not** change (verified by onigiri-parser's migration, #27): `CodePointIndex`, `Range`, `RangesRelation`, `Specifier`, `FactoryBoundCache`, `Singletons`, `org.unlaxer.util.collection.*`.

## Breaking changes in unlaxer-dsl (codegen users only)

| Changed (2.x) | 3.x | Notes |
|---|---|---|
| `org.unlaxer.dsl.UbnfCodeGenerator` (CLI main class) | `org.unlaxer.dsl.CodegenMain` | Update `exec-maven-plugin` `<mainClass>` |
| `UBNFAST.TokenDecl` (class) | sealed interface: `Simple` / `Until` / `Negation` / `Lookahead` / `NegativeLookahead` | Update `instanceof` checks |
| `@mapping` params order | strictly positional, validated at codegen time | Out-of-order `params=` produces a build warning and may generate incorrect mappers |

## Pre-flight validation

Before committing to the upgrade, validate your grammar against the 3.x validator:

```java
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.GrammarValidator;

var file = UBNFMapper.parse(Files.readString(Path.of("your.ubnf")));
for (var grammar : file.grammars()) {
    GrammarValidator.validate(grammar).forEach(System.err::println);          // errors + warnings
    GrammarValidator.validateWithWarnings(grammar)                            // left-recursion cycles
        .ifPresent(warnings -> warnings.forEach(System.err::println));
}
```

Or simply run the CLI, which reports all validation warnings on stderr and supports `--strict` / `--fail-on warning` to escalate them:

```bash
java -cp ... org.unlaxer.dsl.CodegenMain --grammar your.ubnf --output out --validate-only
```

Common warnings and what to do:

- `W-TOKEN-UNRESOLVED` — a token declaration uses an unqualified parser class name. Use the fully qualified name; the hint lists candidates (e.g. `Did you mean 'org.unlaxer.parser.elementary.NumberParser'?`).
- `W-LEFT-RECURSION` — a left-recursive cycle was detected. Rewrite using repetition (`A ::= B { Op B }`) or right recursion. Warning only; reported because left recursion does not terminate in a combinator parser at parse time.

## Deprecation policy (3.x onward)

Public API removals and renames go through at least one minor version with an `@Deprecated` bridge before removal. The removals listed above predate this policy (they shipped directly in 3.0.0); the policy exists so it does not happen again. See README "API Deprecation Policy".
