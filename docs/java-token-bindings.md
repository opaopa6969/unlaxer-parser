# Java token aliases and target-specific validation

Java generation implements a fully qualified `token T = SomeParser` binding or
`ADAPTER(...)` Java binding as a named subclass, such as `TParser extends SomeParser`.
The subclass preserves the existing token alias identity, CST and capture behavior.
Its Java superclass must be extensible and usable by the generated no-argument
constructor. Short Simple names are referenced directly and do not create this
subclass alias.

`E-JAVA-TOKEN-FINAL` identifies a final superclass available to the generator's
class loader before Java source is
returned or CLI artifacts are written. Both the ordinary class-resolving
`GrammarValidator.validate` path and direct `ParserGenerator.generate` apply this
check. Canonical nested Java class names are resolved too; a top-level literal '$'
class is not mistaken for a different nested source name. The probe loads class
metadata without initializing the implementation or invoking its constructor.
Use a public nonfinal Parser with an accessible no-argument constructor for this
Java alias path.

This is a targeted finality check, not proof of Java deployment validity. An absent
class, an unavailable linkage dependency or denied class-loading permission cannot
establish finality. Such bindings keep their prior unresolved-name/actual-javac
boundary. Other Java requirements, including constructors and type compatibility,
still require compilation against the deployment classpath. Previously returned
aliases extending an available final class now fail with the diagnostic instead
of waiting for javac's `cannot inherit from final` error. No delegation or token
identity change is introduced.

`TokenAdapterRegistry` remains pure data and does not load either target's host
implementation. `GrammarValidator.validateWithoutClassLoading`, Rust portability
checks and Java-hosted Rust generation do not run the Java finality probe. Native
Rust cannot inspect JVM class metadata. A valid Rust callback can generate and run
with a final, nonfinal or absent optional Java class; neither Rust host claims that
its Java deployment has been verified. Simple Java-only external classes still
require an explicit supported Rust binding when targeting Rust.

`spec-corpus/java-token-bindings/cases.json` defines the shared availability matrix
and input/AST expectations. `JavaTokenBindingConformanceTest` checks Java validation,
Java CLI preflight, real legal alias compilation/mapping/identity, the unknown-class
compile boundary and no class initialization. Its opt-in test compares all five
Rust files from the Java API, Java CLI and native frontend, and runs the same Rust
consumer oracle in every memo and diagnostic mode.
