# Unicode XID identifier

`XID_IDENTIFIER` recognizes exactly `XID_Start XID_Continue*` from Unicode 17.0.0.
It preserves original text, greedily consumes the prefix, and never normalizes or folds case.
An underscore may continue a name but cannot start one. This differs from the legacy ASCII identifier.
Both token forms require the existing `@ubnf: v2` setting:

```ubnf
token NAME = XID_IDENTIFIER
token NESTED ::= XID_IDENTIFIER;
```

The independent runtime cases live in `../declarative-tokens/runtime.json`: `xid-builtin` and
`xid-nested-unicode-position` compare both cursors, normal AST values/spans, rejection, CRLF,
combining text and supplementary/new Unicode 17 characters. The authoring catalog also runs
Unicode names through generated Java, native Rust, and browser WASM. `UnicodeXidTest` and
`xid_identifier.rs` independently build bitset oracles from the official property input and
compare every range boundary and its neighbors for start/continue matching.

`unicode-17.0.0.txt` contains only the two official property sets. Its header records the
[original source](https://www.unicode.org/Public/17.0.0/ucd/DerivedCoreProperties.txt), fixed version,
SHA-256 of the original full input, and copyright notice. `UNICODE-LICENSE.txt` retains the
[official redistribution permission](https://www.unicode.org/license.txt).
The data does not depend on JDK, Rust, Python or ICU's installed Unicode versions.

Regenerate or verify the Java/Rust tables offline:

```sh
python3 scripts/generate-xid-tables.py
python3 scripts/generate-xid-tables.py --check
mvn -pl unlaxer-common,unlaxer-dsl -am test -Dtest=UnicodeXidTest,DeclarativeTokenTest,DeclarativeTokenConformanceTest,RustUbnfFrontendConformanceTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --workspace --locked --manifest-path rust/Cargo.toml
```

The generators copy both the runtime table and `UNICODE-LICENSE.txt` into every portable
Playground/WASM project; the Java artifact retains that notice as a resource. Native binary
release distributions must retain the notice in their bundled license documentation.
`tinyexpression-migration.ubnf` keeps the old ASCII/underscore names and explicitly adds XID names.
