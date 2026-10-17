# NonOrdered の独立共通 oracle

期待値は実装出力から生成せず、[公開契約](../../docs/nonordered-runtime.md) の
宣言順 greedy 選択とコードポイント位置から手で定めた。
Java と Rust は同じ TSV を読み、memo OFF / SAFE_FAILURES と診断 3 モードで実行する。

`cases.tsv` の列:

1. case ID
2. 入力（Unicode scalar の空白区切り16進表記、`~` は空）
3. child の `/` 区切り宣言。通常は入力と同じ scalar 表記。`~` は child 数 0、
   `N` は空 NonOrdered、`O` に続く scalar は Java 互換 Optional、`E` は EOF、
   `Z` は EmptyParser / JavaEmpty。`C` は空 Chain / Sequence、`CZ` / `CN` はその単一子版、
   `ON` / `OC` は N / C の Java 互換 Optional。`D` / `I` / `S` は Number / Identifier /
   SingleQuoted、`Uy` は Java 互換 UNTIL("y")。
4. 外側の構成: `N` はそのまま、`FAIL` は末尾 `"!"` を要求、`RETRY` は
   `Choice(Chain(N,"!"),N)`、`PREFIX` は最初に Any 1 CP、`NESTED` は `NonOrdered(N,"!")`。
   `CHAIN` は N を Chain / Sequence で包む。`PRIOR` / `PRIOR_CHAIN` は先に EmptyParser /
   JavaEmpty を同じ context で直接実行してから N / Chain(N) を評価する。
5. 成否
6. consumed CP
7. matched CP
8. 成功時 CST の `child宣言index:start:end` を実入力順で列挙。`~` はなし。
9. 失敗時の最遠構文失敗 CP。成功時は `-1`。直接 deferred mode の未収集診断は比較しない。

`state.tsv` の列は操作、入力、成否、consumed CP、user state 値、declaration 数、
reference 数、semantic diagnostic 数、recovery span 列、capture span 列。
`~` は空の列である。
`STATE` は最初の候補が state / scope / reference / diagnostic / recovery / capture を変更して
失敗する入力 `"b;a"`。次候補の `"b;"` は元位置から成功し、最初の候補を CP 2 で再試行すると
選択された state と capture だけを残して `"a"` に成功する。
`RECOVER` は `NonOrdered(sync("ok", ";", "bad"), "a")` に `"bad;a"` を与える。
`_FAIL` / `_RETRY` は cases と同じ外側の末尾失敗・Choice 再試行である。

Java の capture-site wrapper と Rust の named capture は内部形式が異なるため、
raw text と CP span を共通観測値にする。Java `getOrdered`、Rust node 順序 / trace、
両 runtime の再帰上限と memo hit は追加のホスト別テストで検証する。

matched cursor の期待値は、直接 NonOrdered と Chain / Sequence 配下を区別する。
Java の観測用 Chain は方針自体を変えるため使用せず、実 child の parser identity で
元の CST を照合する。CST と入力 span の対応を保ったまま Empty / nullable / lexical child を
組み合わせ、成功した Chain の方針伝播と外側の Choice 再試行も検査する。
