# Evaluation strategies

The Java and Rust generators recognize `@eval(kind='...', strategy='...')` on a
mapped rule. The default strategy is `default`. `manual`, an unknown kind, and a
rule without `@eval` require handwritten semantics. Recognized kinds with a
non-manual strategy generate overridable defaults. An annotation applies to its
emitted mapping type, including other rules sharing that type; conflicting
annotations on the same mapping are rejected.

| Kind | Required fields | Default behavior |
|---|---|---|
| `literal` | `value` | Convert the captured value; do not evaluate an AST child implicitly. |
| `passthrough` | `value` | Evaluate a scalar AST child; otherwise convert the value. |
| `conditional` | scalar AST `condition`, `thenExpr`, `elseExpr` | Evaluate the condition once, then exactly one branch. |
| `binary_arithmetic` | scalar `left`, text list `op`, list `right` | Evaluate operands left to right and apply each operator in order. Reject different operator/operand counts. |
| `variable_ref` | scalar text `name` | Remove one matching `strip_prefix` and call the variable resolver. |

`@eval` on an unmapped/skipped rule, missing or incorrectly shaped fields,
duplicate annotations, and conflicting shared-mapping annotations fail before
source files are written. An automatic annotation on a sum family belongs on its
concrete variants instead. Enum declarations themselves remain value types;
annotate a mapped record that captures an enum.

## Values and migration

Java evaluators remain generic `Evaluator<T>`. Automatic literal/passthrough
methods now call `protected abstract T convertValue(Object value)` instead of an
unchecked cast to `T`. Implement the conversion explicitly. For example, an
`Evaluator<Object>` may return the value, while an evaluator producing integers
must check/convert supported inputs. Existing automatic users must implement this
new method when regenerating. Missing implementations fail compilation. Manual
methods and the existing binary/variable hooks retain their signatures.

Mixed captures contain Java `String`, AST nodes, or `AST.EnumValue`. The enum
wrapper retains nominal type and occurrence identity. Optional and list values
keep their containers; passthrough does not silently recurse through containers.
No implicit numeric parsing, boolean parsing, normalization, or enum-to-string
conversion is performed by literal/passthrough defaults.

Rust `Semantics::Output` remains application-defined. Auto-generated methods have
default bodies; manual/unknown methods remain required. Value conversion uses
`convert_value(EvalValue<'_>, Span)` with borrowed text/node/enum and explicit
optional/list shapes. Consumers inspect this enum exhaustively. Value conversion
and resolver/arithmetic hooks are required only when used by an automatic kind.

For a conditional, the Rust implementation supplies `is_truthy(&Output)`.
Java's default condition is `Boolean.TRUE`, or a value whose `String.valueOf`
equals `true` ignoring case. Strings are not trimmed by truthiness. Capture
mapping has its existing trimming rules before evaluation. The shared fixture
uses the same predicate in Rust; the application owns its chosen result type.

## Failure and order

Java callback exceptions propagate immediately. Rust conditional/binary
semantics also supply `is_failure(&Output)`. This makes a fallible
`Output = Result<Value, Error>` explicit: return `value.is_err()` and the generated
code propagates that value before invoking any later operand, operator, predicate,
or branch. Infallible outputs return `false`. No panic is needed for ordinary
semantic errors. The oracle uses Java exceptions and Rust `Result` errors with
the same messages and callback sequence.

A user-constructed binary AST with mismatched operator/operand counts violates
its schema: Java throws `IllegalArgumentException`, Rust rejects it with an
assertion. Ordinary generated mapping constructs matching lists. Java's legacy
nullable-left synthetic leaf representation is retained; Rust represents scalar
text operands directly. User arithmetic determines overflow/division policy;
the generator itself does not choose a numeric evaluation domain.

Source positions remain the mapper's code-point spans. Rust passes the node span
to conversion/manual hooks; Java's source map and `DebugStrategy` expose node
positions. No evaluation step changes the AST or its retained source map.

## Evidence

`spec-corpus/evaluation/` supplies source grammars and independent expected values,
callback order, skipped branches, failures, and root spans. Tests execute all six
memoization/diagnostic combinations in Java21 and Rust1.85, compare Java-host and
native-host Rust source byte for byte, and compare complete API schemas.
Manual/unknown methods and conversion hooks are checked by intentionally
compiling incomplete implementations. Generated default methods appear as
optional implementations in the API schema; required helpers remain visible.

Existing grammars without automatic evaluation retain the old evaluator text and
manual semantics obligations. Public parser IR layouts do not change: evaluation
metadata is applied by the UBNF frontend after ordinary structural generation.
