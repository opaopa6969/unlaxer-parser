# Unicode 17 simple case-insensitive matching

`UnicodeIgnoreCaseWordParser` / `Expr::CaseInsensitive` use the same locale-independent
key: `Simple_Lowercase_Mapping(Simple_Uppercase_Mapping(cp))`, using identity when a
mapping is absent. This is simple one-code-point matching, not Unicode full case
folding (`ß` does not match `ss`), normalization (`é` does not match `e` + U+0301),
or locale tailoring. Input text and CP spans are preserved. Byte lengths of the
input and pattern may differ: `CI('k')` consumes the original U+212A as one CP.
Empty runtime words fail; the UBNF validator rejects empty CI declarations.
Java unpaired UTF-16 surrogates do not compare equal in this matcher; Rust strings
cannot contain them. Java's existing `IgnoreCaseWordParser` stays JDK-dependent.

`runtime.tsv` is the common Java/Rust oracle (hex scalars, `~` = empty).
`ATOM` matches a prefix, `CHAIN` wraps that atom in a sequence, `FAIL` requires a
following `!`, `RETRY` retries the atom after that failed sequence, `AHEAD_CI`
looks ahead before consuming the atom, and `PREFIX` first consumes one CP.
The tests run each row with both memo modes and all three diagnostic modes.
Separate tests compare every Unicode scalar's generated key with the raw UCD
simple mappings. No JDK or operating-system Unicode data participates.

## Reproducible data

- Upstream: <https://www.unicode.org/Public/17.0.0/ucd/UnicodeData.txt>
- Mapping fields: 12 / 13 (zero-based), as specified by Unicode 17 UAX #44:
  <https://www.unicode.org/reports/tr44/tr44-36.html>
- Original bytes and SHA-256: `unicode-17.0.0/UnicodeData.txt`, `SHA256SUMS`.
- License: `UNICODE-LICENSE.txt`, the same Unicode License V3 used by the grapheme data.

Run `python3 scripts/generate-case-tables.py` from the repository root to regenerate
both tables, or add `--check` for offline SHA-256 and generated-source validation.
The generator checks key idempotence and preservation of UTF-16 width across the
entire code-point range. It does not use SpecialCasing.txt or CaseFolding.txt.
Each matcher scans the pattern/input prefix once; table lookup is binary search
in a fixed-size Unicode 17 table. No repeated input-prefix scans or transformed
copies of input are needed.
