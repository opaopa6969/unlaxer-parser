# Generated CI conformance oracle

generated-cases.json fixes 17 grammars / 45 independent inputs for generated Java
and both Rust generation paths. The expected acceptance, prefix cursors, original
capture text, CP spans, full-input diagnostics and CP/UTF16/UTF8 endpoints are
committed data. They are not copied from either parser's output.

The test executes each row under memo OFF/SAFE_FAILURES and diagnostics
AUTO/DETAILED/DETAILED_ON_FAILURE. A failed root must retain no tokens/nodes, both
cursors return to zero, and retry in the same context must fail identically.
An ordered-choice fallback proves a failed CI branch cannot retain its tokens.
Optional and repeated CI include both missing/mismatching atoms and consumed prefixes
followed by failure. The predictive/longest fixtures use Kelvin-sign versus ASCII casing, including
different byte widths, to catch an incorrect literal FIRST predictor or an end
offset computed from the pattern's encoded width.

Coverage includes dotted/dotless I, final sigma, sharp S without full-fold
expansion, supplementary Deseret, family ZWJ text with a combining mark, no
normalization, nonzero Unicode prefix offsets, atomic failure and EOF. New
Unicode17 pairs include U+A7CB/U+0264, U+1C89/U+1C8A, U+A7DC/U+019B and
U+10D50/U+10D70. The two aggregate literals cover every legacy fold-value delta
listed in the following TSVs, not just those representative pairs.

CI('') remains syntactically recognizable but validation/generation rejects it,
even when its declaration is unused (E-CI-EMPTY / empty CI literal).
The TrimProjection row keeps raw token text and the full CP span but preserves the existing mapper trim projection for its AST string field.
REGEX dialect compatibility is outside this corpus.

## Legacy JDK observations, separately from the new Unicode17 contract

Each jdk*-legacy-delta.tsv row is:

    scalar_hex  legacy_lower_upper_hex  unicode17_lower_upper_hex  Unicode_name

The fold is simple scalar lower(upper(cp)), not full Unicode case folding,
locale-specific casing, or normalization. There are 95 differing scalar fold
values for the measured JDK17 host and 55 for JDK21. These are not counts of
accepted pairs or equivalence classes. The direct legacy IgnoreCaseWordParser
keeps its JDK-dependent behavior; generated CI uses the pinned new contract.

Measured hosts: OpenJDK 17.0.20.1, Oracle JDK 21.0.9. The corresponding
[Java17 Character documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Character.html)
and [Java21 Character documentation](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Character.html)
describe Unicode13 and Unicode15 respectively. The mapping source is the official
[UnicodeData 17.0.0](https://www.unicode.org/Public/17.0.0/ucd/UnicodeData.txt),
SHA256 2e1efc1dcb59c575eedf5ccae60f95229f706ee6d031835247d843c11d96470c.

Reproduce without changing the fixture (replace JDK/data paths as appropriate):

    /path/to/jdk17/bin/java spec-corpus/case-insensitive/DumpLegacyCase.java > /tmp/jdk17-case.tsv
    python3 spec-corpus/case-insensitive/audit_legacy_delta.py spec-corpus/case-insensitive/unicode-17.0.0/UnicodeData.txt /tmp/jdk17-case.tsv > /tmp/jdk17-delta.tsv
    diff -u spec-corpus/case-insensitive/jdk17-legacy-delta.tsv /tmp/jdk17-delta.tsv

Repeat with JDK21 and its corresponding TSV. CaseInsensitiveConformanceTest
checks each recorded legacy value on JDK17/21; newer JDKs are not claimed to have
those old tables. The generated tests consume the fixed Unicode17 expected text
on every JDK, including all 95/55 changed fold values.

Reports: unlaxer-dsl/target/case-insensitive-java.tsv and
unlaxer-dsl/target/case-insensitive-conformance.tsv.
