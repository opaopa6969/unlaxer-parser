# `@skip` の写像契約

`corpus.json` は Java と Rust の生成コードで共有する独立した期待値を記録する。
位置は Unicode スカラ単位。`@skip` のルールは構文解析と CST に残り、scope の効果も残る。
AST 写像はそのルールで停止する。ルール自身の `@mapping` と子孫の mapped node は漏れない。
親が明示的に capture した場合だけ、ルール全体のテキストが親の field に入る。
root が `@skip` の場合、構文解析は成功しても AST 写像は明示的に失敗する。

各ケースに prefix cursor、入力全体の構文受理、写像結果、AST field と node span、
CST のルール出現数、宣言・参照の数を記す。conformance test は生成 Java / Rust を
コンパイル・実行し、両 UBNF frontend が生成した Rust ファイルも byte 単位で比較する。

14 文法・24 入力で、直接・alias/group・optional/list・混在 Text/Node、shared mapping、
再帰的な右結合規則、AST を持たない root、Unicode・CRLF/trivia・単一引用符と空文字列を検査する。
scope の作用は skipped 規則の内部と規則自身の両方で検査し、分岐および全体失敗の rollback も固定する。
AST が文字列へ正規化されても元の capture span が失われないことを独立に確認する。
透明な root choice では、skipped 分岐だけが写像に失敗し、通常の mapped 分岐は AST を返すことも検査する。
skipped choice 自身の両分岐も text 投影と CST 境界を検査する。Java の CST は context の committed token から取得し、
選択先だけを返す `Choice` の `Parsed` と区別する。

これは有限の受け入れ corpus であり、全 Java annotation / 全 CST 形状の同値性証明ではない。
Java の汎用 reducer と生成 typed mapper の違いは [契約](../../docs/skip-ast-projection.md) を参照。
