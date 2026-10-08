# 固定 version の名前 snapshot

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
|UBNF `@namePredicate`、frontend/IR/生成器接続|対応|対応|#425|
|生成 AST/source map の型名依存比較|対応|対応|#425|
|一般左再帰、完全 C++ templates/type inference|未対応|未対応|#379|

snapshot を要求する entry 全体を scope で囲むことが必須であり、scope 外の直接 predicate は明示拒否する。生成器は全 rule の requirement を root scope に集める。未到達 rule に宣言した snapshot も要求する最小の保守的な契約であり、暗黙の探索や grammar 生成中の名前照会は行わない。#427 は runtime 基盤、#425 は生成接続の単位であり、親 #379 の残り corpus / 左再帰 / 資源境界は独立に監査する。

## UBNF と生成 API

```ubnf
@namePredicate(snapshot='cxx23', version='v1', name='name', kind='type')
@mapping(Declaration, params=[name,argument])
Declaration ::= ID @name '(' ID @argument ')' ';';
```

引数はこの順序の single-quoted 値で固定する。`kind` は `type` / `value` / `resolved` のいずれかで、大小文字を区別する。capture は TEXT が必ず1つ選ばれる形を要求し、欠落・optional・複数・AST node は生成前に `E-NAME-PREDICATE-CAPTURE` で拒否する。空の実captureは runtime の `name_capture` となる。重複 annotation、id/version、同じidのversion競合、64を越える requirement、同一 rule の associativity/recovery annotation、一般の左再帰も生成前に拒否する。root の syntax recovery は別 rule の name predicate と組み合わせられるが、UNKNOWN は entry observer に残り recovery 成功では消えない。既知名の syntax recovery は通常の `recovery` 診断となる。

Java mapper は `diagnoseWithNameSnapshots` / `parseWithNameSnapshots` / `parseWithNameSnapshotsAndSourceMap` を生成する。Rust parser は `parse_tree_detailed_with_name_snapshots` を生成し、既存 mapper で CST から所有 AST に変換する。Java frontend と native Rust frontend から生成した5ファイルは同一である。通常の名前なし API で新 profile を解析すると、snapshot 不在を明示診断する。immutable snapshot は各呼出しで所有コピーし、deferred diagnostics の再試行にも同じ値を渡す。先の Java source map は次の snapshot / context の解析で変更されない。

`unlaxer-dsl/src/test/resources/name-predicate/{corpus,invalid}.json` は固定 C++23 の最小 statement、Unicode、CRLF、UNKNOWN、version、trailing input、unguarded fallback、root recovery を独立 oracle に固定する。Java 生成 AST の全field/node span と Rust 生成 AST、raw CST、prefix cursor、名前失敗 span、full-input 診断、memo ON/OFF、deferred retry を照合する。子entry・registered state/scope rollback・空/複数capture・資源上限・既定stack256再帰guardは `spec-corpus/name-snapshots/runtime.json` と両 runtime の試験で検証する。CI は `rust-name-predicate.tsv` を必須保存する。
