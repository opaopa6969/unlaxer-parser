# REGEX core oracle

`cases.tsv` contains 127 handwritten prefix expectations, including scalar text,
empty matches, EOF, CRLF, family emoji/ZWJ, modifiers, combining characters and
source-coordinate ends in CP/UTF16/UTF8. Hex strings are UTF-8; `~` denotes an empty
string and `-` means no prefix match. Last columns give Java adapter token progress
in normal/inverted mode; zero means no token.

`invalid.tsv` contains 47 compile rejections. `java_accepts=true` means the construct
is valid in Java 21 and currently receives Rust `Unsupported`; `false` is invalid
Java syntax and gets Rust `Syntax`. These are not silently interpreted through a
different regex dialect. `capabilities.tsv` records the implementation scope and
links every known gap to an open issue.

The Java test checks the handwritten expectations against real `Pattern` and
`RegexTokenParser`, then compiles `probe.rs.txt` against a standalone `rustc` build
of the runtime. It also compares systematic short patterns/inputs against Java as
a second independent oracle. The Rust integration test reads the same TSV files
and separately checks resource bounds and execution-state isolation. Expectations
are not regenerated from Rust output.

See [the contract](../../docs/regex-core.md). Parent #544 remains open; core support
and an explicit unsupported diagnosis are not evidence of complete Pattern parity.
