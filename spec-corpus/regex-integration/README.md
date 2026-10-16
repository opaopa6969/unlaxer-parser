# Generated and Classic REGEX oracle

`modes.tsv` contains independent handwritten token results: UTF8 hex strings,
`~` empty, CP cursors/positions, and `kind@sourceCP:CPlength:textHex` signatures.
It separately observes returned original tokens, committed roots and retained
leaves. VirtualConsumed peeks the matched cursor while advancing the consumed
cursor by token length; its source offset need not equal the consumed start.

`generated-cases.json` fixes whole generated-parser prefix and acceptance, source
coordinates, AST string values and diagnostics. Its expected values come from the
explicit grammar and contract, not snapshots of implementation output. The Java
and Rust probes execute generated evaluators whose Value callback returns the
captured text. Repetition and optional cases retain only the final delimiter as
the mapped field; the root span still covers the entire input. Whitespace captures
retain original CST text while the existing mapper's trim projection is preserved.

`validation-probe.rs.txt` compares Rust compile outcomes and CP rejection sites
with the Java-host bounded validator, using the separate core success/reject corpus
plus compile resource cases. See [the contract](../../docs/regex-integration.md).
