# Unicode 17 extended grapheme corpus

契約・API・再現手順は[書記素座標](../../docs/grapheme-coordinates.md)。

`unicode-17.0.0/`はUnicode Consortiumの次の固定URLから取得した原本で、
`SHA256SUMS`にbyte単位のchecksumを保存する。再生成はofflineで実行できる。
原本のコメント末尾空白も保存し、同directoryの`.gitattributes`で改行変換と空白検査を無効化する。

- https://www.unicode.org/Public/17.0.0/ucd/auxiliary/GraphemeBreakProperty.txt
- https://www.unicode.org/Public/17.0.0/ucd/auxiliary/GraphemeBreakTest.txt
- https://www.unicode.org/Public/17.0.0/ucd/emoji/emoji-data.txt
- https://www.unicode.org/Public/17.0.0/ucd/DerivedCoreProperties.txt

Unicodeデータと派生表は同梱`UNICODE-LICENSE.txt`に従う。既存XID表と同じUnicode License V3。
Java binary配布にはcommon jarの`META-INF/UNICODE-LICENSE.txt`、Rust playground配布には
既存runtimeの`UNICODE-LICENSE.txt`も同梱する。

`boundaries.tsv`と`parser.tsv`は実装から生成していない独立期待値。
前者はG境界のCP/UTF16/UTF8、後者は受理/両cursor/source spanを固定する。
Java MatchOnlyとRust aheadのmatched/result公開の差は別列で明記する。
