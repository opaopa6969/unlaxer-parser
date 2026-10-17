# Controlled resource aborts and published parser state

A controlled resource-limit error invalidates the active parse session. It cannot
be converted into successful repetition, a negative assertion, or a successful
custom callback result. Use a fresh context to parse again. The first Java
`ParseLimitException` object is rethrown on later framework parser and transaction
entries; Rust preserves the first controlled `ParseError` value.

Before the error escapes a framework parser, owned publication is restored to the
outermost active parse boundary: both code-point cursors, committed CST, selection
and reset policy saved at that boundary, registered transactional state, portable
metadata and general owned captures. Previously committed results remain intact.
Java captures this boundary at the outermost parser notification, using the same
rollback owner as fatal observer errors. Ordinary syntax failure and ordinary
child callback exceptions retain their existing separate contracts.

A caller's already active manual transaction remains its owner. The aborted
nested parse restores that transaction's entry state, without erasing successful
work completed in the manual transaction before the nested parse. Java `close()`
then unwinds the abandoned outer owner safely. Rust's enclosing `transaction`
closure can observe the restored nested entry and rolls its own changes back when
it returns the controlled error. Neither language requires publishing synthetic
success or rollback callbacks for abandoned parser frames.

Java MatchOnly, PEG assertions, named capture and capture scope skip local cleanup
when the fatal boundary has already removed those frames. A commit's late cleanup
also recognizes frames already unwound by the abort. An observer that throws or
swallows a new controlled resource failure still invalidates its observer boundary;
subsequent Java calls report that observer failure with the original error as its
cause. Existing fatal-observer precedence is preserved. A resource-only close
retains the existing close notification, after owned state has been restored.

Only explicitly registered state and owned metadata participate. External I/O and
unregistered host data remain the callback's responsibility. Host checkpoint and
restore actions must obey their no-throw contract. Rust host panics require
context disposal and are not treated as recoverable controlled errors.

The Java implementation reuses the existing outer parse snapshot. Successful
parsing adds no new checkpoint, state clone or publication allocation for this
resource-abort behavior. Expensive restoration occurs only on the fatal path.

`conformance/resource-abort/cases.tsv` is the independent Java/Rust oracle for
nested Chain, MatchOnly, PEG, capture, scope and repeat boundaries, with and without
an outer manual transaction. `ResourcePublicationTest` and Rust
`tests/resource_publication.rs` cover all memo and diagnostic modes, cursor/CST
retention, state and capture shadowing, portable metadata rollback and stale node
IDs. Both also check that a callback cannot publish success after swallowing a
controlled failure. Java's existing observer/resource tests plus the new swallowed
observer case check first-cause retention, immediate frame cleanup and safe close.
