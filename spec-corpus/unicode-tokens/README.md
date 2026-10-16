# Unicode token corpus

`cases.tsv` は原文、CP長、UTF-16長、UTF-8長、先頭scalarの独立した期待値を共有する。
家族ZWJ列 `👨‍👩‍👧‍👦` は7 CP / 11 UTF-16 / 25 UTF-8で、1つの書記素として表示されても、
parserの位置とANYはUnicode scalarを1単位として扱う。正規化やZWJの除去は行わない。

Javaの `RegexTokenParserTest` とRustの `unicode_tokens` integration testで、
literalが原文全体を保持し、ANYがscalar単位で進み、正しいEOFへ到達することを検証する。
Javaでは `REGEX` の一致長と同じ原文・CP消費量も検証する。

| API | 契約 |
|---|---|
| Java `REGEX` | Java `Pattern` を現在のTokenKindのCP位置から始まるsuffixへ適用し、一致のUTF-16長をCP長へ変換して消費する。ゼロ幅一致とEOFは非空tokenを生成しない |
| Java `REGEX` の `invertMatch=true` | パターンが不一致で次のscalarがある場合は1 CPを消費する。正規一致時とEOFは失敗する。可変長パターンの不一致長は推測しない |
| Java `REGEX('(?s).')` / Java・Rust `ANY` | 改行を含む次のscalarを1 CP消費する。通常のJava regex `.` はJava `Pattern` の改行規則に従う |
| Rustの汎用 `REGEX` | 未対応。portability checkは `P-TOKEN-KIND` として拒否する。本corpusのliteral/ANY成功はREGEX対応を意味しない |

Javaの反転tokenは既存の `SingleCharacterParser` に合わせる。
`Not` combinatorや宣言的lexical tokenのゼロ幅否定assertionとは異なるAPIである。
EOFで進まず成功し続ける反転tokenは作らない。
