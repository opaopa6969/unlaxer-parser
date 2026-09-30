# UBNF `ERROR('message')` 共通コーパス

`ERROR('message')` は常に失敗し、入力と capture/state を消費・確定しない。
空白のみではない message は失敗位置の expected 候補になる。空文字・空白のみの message は
明示候補を増やさないが、Java の上位診断に class 名が補われる場合はある（後述）。
失敗位置・span は Unicode code point 単位。構文としての `ERROR('...')` と、通常の
ルール名 `ERROR` およびその参照は区別する。`@recovery` はこの機能に含まれない。

`corpus.json` は受理、prefix cursor、全入力診断 JSON、AST 全 field/span、
宣言・参照・semantic diagnostic の独立期待値を記録する。conformance test は
Java 生成物を `javac` で、両 frontend が生成した Rust 5 ファイルを byte 比較して
`rustc` で実行し、JVM のない環境で native generator を起動する。

全入力診断は `Auto` / `Detailed` / `DetailedOnFailure` と memo `Off` /
`SafeFailures` の6通りで比較する。`diagnostic` が Rust 側の独立期待値で、
Java 側の既存 native 表示が異なる場合のみ `javaDiagnostic` に全文を記録する。
候補を削る・順序を変えるなどの正規化は行わない。既存の差は、Java が
literal を引用符付きで表示すること、`javaStyle` trivia の補助候補、
識別子の補助候補、trailing input の farthest 候補、空・空白 message 時の
`ErrorMessageParser` / `RootParser` class 名 fallback である。後者は
`ERROR` の空 message 自体が expected 候補に追加されたことを意味しない。

このコーパスは12文法19入力で、`@predictiveChoice`、`@longestChoice`、
通常の `ERROR` というルール名、失敗枝の optional/repeat capture と
scope state rollback を含む。Rust backend の既存契約に従い、失敗する
root にも `@mapping(Result)` を指定する。
