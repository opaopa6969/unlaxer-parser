# 型モデルの共通 fixture

`corpus.json` は共通モデルを1つ持ち、各 case の `overrides` でトップレベルの項目を置き換える。`queries.expected` と `error` は実装から生成しない独立期待値。Java / Rust の実装・位置・失敗条件を比較する。

`model.ubnf` は実ソースからモデルを作る接続例の文法。入力と両 adapter は [examples/semantic-model](../../examples/semantic-model/README.md)。`probe-support.rs` はテスト結果の JSON 表示だけを担当し、型判定・scope・補完を再実装しない。

構文 parser 自体の全機能や Java / TS / Rust の型仕様を網羅した corpus ではない。初版の対応範囲は [semantic-model.md](../../docs/semantic-model.md) に記載する。
