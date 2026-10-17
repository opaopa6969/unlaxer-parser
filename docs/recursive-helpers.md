# Recursive transparent helpers

Java and both Rust generation hosts project values through consuming recursive
rules without requiring an AST record for every recursive step (#582).

```ubnf
@root @mapping(Container,params=[value]) Root ::= H @value;
H ::= Leaf [ ',' H ];
@mapping(Leaf,params=[text]) Leaf ::= '😀' @text;
```

`Container.value` holds both leaves for `😀,😀`, in source order. Java generates a
list and Rust a `Vec<Ast>` with borrowed slice semantics. A parenthesized alias
`H ::= '(' H ')' | Leaf` still produces a single leaf. Optional nodes, enum
aliases/lists, mixed text/node/enum values and numeric node fields use their
existing field types. Numeric-looking unmapped text aliases stay text.

The shape analysis computes a finite fixed point over rule references. Unknown
finite derivations and an empty optional derivation are distinct: using text for
either would incorrectly widen node/enum aliases or lose a list's values. Mapped
nodes and enums are terminals for this analysis. Only recursive reference paths
use the new solver; existing flat captures retain their API.

Captures inside an unmapped helper stay local CST metadata. A caller's capture
collects the helper's projected semantic values, without importing the helper's
capture names. This also applies when caller and helper use the same name.
Both Rust hosts now permit these private helper captures, as Java already does.
A mapping's public parameter/capture checks, scope bindings and typeof validation
remain enforced.

Text-only helpers preserve the entire captured text, including delimiters.
Existing mixed-value boundaries also keep the whole helper substring when its
selected value is text: `H ::= '(' H ')' | Node | 'x'` produces `"(x)"` for `(x)`.
Mapped nodes keep their own spans instead. Nullable **text** separators are
allowed when the repeated item consumes input; mapped semantic separators remain
unsupported. These rules match the pre-existing nonrecursive projection contract.

The solver caches each completed result per generation and distinguishes enum
projection from root-node projection. It limits expression depth to 256 and work
to 1,000,000 visits; it cannot expand a recursive rule indefinitely. Ordinary
runtime parse limits remain in force. Java reports `ParseLimitException` and
requires closing that context; Rust reports its sticky resource failure and rolls
back owned state. Thresholds count each runtime's parser frames, so the maximum
accepted nesting depth is not claimed to be identical. A finite input with no
terminating branch is a normal syntax failure, not a successful empty value.

The independent corpus in `spec-corpus/recursive-helpers` compares concrete
fields, order/duplicates, CP spans, syntax failure offsets, mapping and evaluator
execution under memo off/on and all three diagnostics policies. It also checks
2,000 nested delimiters hit controlled resource limits, state rollback/reuse rules,
and an independent nullable-separator review regression. Deferred Java diagnostics
are explicitly replayed in detailed mode after failure, following that API's
contract. Both Rust hosts must emit all five files byte-for-byte identically.

Run with Java21 and Rust1.85:

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RecursiveHelperConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Nested optional/list **structure** preservation remains #583; this change keeps
flat collection semantics and does not close parent #568. Untreated left-recursive
cycle generation differs between Java warnings and Rust rejection and is tracked
separately in #588. Supported associative lowering is not changed here.
