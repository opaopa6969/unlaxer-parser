# Parser observer failures

Java parser and transaction observers are part of the parse's publication boundary.
An unchecked exception or `Error` from `ParserListener.onStart/onEnd`,
`TransactionListener.onBegin/onCommit/onRollback` (including parser-self listeners),
or a transaction's pre/post commit action aborts the outermost active parse.
The original exception propagates; the failure is never converted into a successful parse.

Public `begin/commit/rollback` calls outside parser notifications own a separate transaction
boundary. A parse nested in an existing transaction restores its own pre-parse state if an
observer fails; closing the invalid context then unwinds the enclosing transaction. Both
paths remain protected even when no `startParse` notification is involved.

The context restores its pre-entry consumed and matched cursors, appended CST tokens,
choice/interleave selections, and explicitly registered `TransactionalState` owners.
Portable token metadata follows its registered owner: old values/relations are restored,
and NodeIds allocated during the aborted parse become stale. Parse, terminal, transaction,
and memo frames opened by that parse are discarded without synthetic observer callbacks.
Listeners and commit actions are dispatched from a stable snapshot: registration changes apply
to a later notification/commit, without invalidating the current iteration. Previously committed
tokens retain their identity. An observer sees the existing commit
ordering (published tokens are visible in `onCommit`), but a failed notification retracts
that parse's publication before the exception escapes.

After an observer failure, the Java context is invalid for further parsing. Subsequent
parse/transaction operations reject it with `IllegalStateException` whose cause is the
first observer failure; swallowing a notification exception does not authorize continued
publication. `close()` releases owned frames without invoking more observers. Retry using
a new context. No global parser or listener instance is poisoned.

This contract preserves registered reversible state, not external I/O or arbitrary objects
stored in host maps. Previously committed token objects must not be directly rewritten by
an observer; mutable portable metadata belongs in the registered metadata store. Registered
owners retain the existing no-throw, independent checkpoint/restore contract. An owner that
violates it requires discarding its context.

Construction (`onOpen`) and normal disposal (`onClose`) remain lifecycle callbacks. Their
exceptions propagate; they do not retract an earlier completed parse. An already invalid
context skips close notifications. Ordinary child callback exceptions and resource failures
keep their existing contracts; the MatchOnly/PEG cleanup paths do not double-unwind an
observer abort.

Rust has no Java-style listener registration API. The corresponding supported custom-hook
failure path is `ParseContext::transaction` / `ParseContext::parse` with `Result::Err(ParseError)`.
A hook may run before/after its child and return an error: the surrounding transaction
restores cursors, CST arena, named owned state, portable metadata and semantic scopes.
Controlled errors permit retry in the same context. Resource-limit errors remain sticky and
fatal. A Rust panic is outside this Result rollback contract: discard the context after panic;
do not use catch_unwind to claim that its state is reusable.

The shared oracle in `conformance/observer-failures/cases.tsv` supplies 20 input/stage cases,
including start/begin/commit/rollback/end, pre/post actions, nested and PEG/MatchOnly owners,
different prior cursors, supplementary Unicode and CRLF. Java21 and Rust1.85 execute every
case under both memo policies and all three diagnostics modes. Java tests use real registered
observers; Rust tests use custom hooks around real runtime expressions. Both compare callback
order, retained CST boundary, owned state and metadata. Java additionally checks fatal reuse,
close and nested parser-self listeners; Rust distinguishes recoverable errors from panic.

An ordinary outermost parse keeps cursor/token-count references without allocation when no
owned state is touched. A parse nested in an explicit outer transaction stores an additional
boundary record so the two rollback points remain independent. Each eager registered owner adds one root checkpoint on an actual parse or an outer public transaction;
mutation-aware owners remain lazy. Selection changes retain one additional root undo journal.
Memo hits which execute no parser notifications still replay only their original transaction
checkpoints. The root boundary does not count as a transaction begin/end. The deep-copy metric includes
its actual checkpoint calls. It is not a second observer notification or a replayed failed candidate.
