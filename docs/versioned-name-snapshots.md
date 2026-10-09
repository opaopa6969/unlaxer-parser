# 固定 version の名前 snapshot（runtime 段階）

`NameSnapshot` / `names::Snapshot` は `id`、exact opaque `version`、解決済み名前から `TYPE` / `VALUE` への分類を持つ immutable 値である。runtime は provider、外部 I/O、暗黙 registry、型推論を実行しない。snapshot は context の作成前に検証し、既存の immutable parse bindings へ所有コピーする。

Java は `ParseContext.withNameSnapshots(source, snapshots, options)`、Rust は `ParseContext::with_name_snapshots(input, snapshots, options)` を使う。Java の `NameResolutionScope` / `NamePredicateParser` と Rust の `Expr::NameResolutionScope` / `Expr::NamePredicate` は、構文を解析した後に名前分類を読む。CST の syntax node、source、capture は通常の構文解析のまま保持する。capture は1つだけ必須で、既存 scope capture と同じ U+0000..U+0020 の前後 trim を行い、診断 span は trim 後の Unicode code point 位置を指す。

既知の別種は通常の候補失敗であり、次の ordered candidate を試せる。snapshot に名前がない場合は UNKNOWN とし、VALUE と推定しない。UNKNOWN、snapshot不在、不正bindings、version不一致、空/複数captureは entry の明示失敗となる。名前失敗の observer は transactional syntax state とは別に保持し、捨てた候補や syntax recovery の成功でも消えない。全体を `NameResolutionScope` で囲むことで、その entry は cursor/CST/capture/state を rollback して失敗する。nested entry の失敗は active parent entry にも伝播する。次の root name entry は observer/cache を初期化する。

Java の name entry は context の残り期間の memo/FIRST 最適化を保守的に無効化する。Rust は active name entry の memo/FIRST を無効化し、name expression とそれを参照する rules は memo unsafe として扱う。snapshot は context 中で変更できない。別 version の解析は別 context を作る。

資源上限は snapshot 64個、合計4096 names、name 256 code points。id は先頭 ASCII letter、残り `[A-Za-z0-9_./-]` で128 ASCII bytesまで、version は printable ASCII（空白を除く）1..128 bytes、name は空文字・U+0000..U+0020・U+007F..U+009Fを禁止する。Java は不正 surrogate も禁止する。lower-level bindings の重複version/重複name/TYPEとVALUEの重複も拒否する。

固定 C++23 [WG21 N4950](https://www.open-std.org/jtc1/sc22/wg21/docs/papers/2023/n4950.pdf) の最小 statement 形を共通runtime fixtureに使う。`T(a);` は既知TYPEなら宣言候補、既知VALUEなら式候補、UNKNOWNなら明示失敗。`T(a)++;` はTYPEでも式専用構文で成功する。このfixtureは runtime の分類契約を検証し、完全な C++ grammar や型推論への対応を主張しない。

対応表:

|範囲|Java|Rust|追跡|
|---|---|---|---|
|typed immutable snapshot / binding transport|対応|対応|#427|
|entry failure / rollback / Unicode diagnostic span|対応|対応|#427|
|runtime の限定 TYPE/VALUE/resolved gate|対応|対応|#427|
|UBNF `@namePredicate`、frontend/IR/生成器接続|後続|後続|#425|
|生成 AST/source map の型名依存比較|後続|後続|#425|
|一般左再帰、完全 C++ templates/type inference|未対応|未対応|#379|

snapshot を要求する entry 全体を scope で囲むことが必須であり、scope 外の直接 predicate は明示拒否する。#427 はruntime基盤だけを閉じ、#425 / #379 は生成接続等が完了するまで閉じない。
