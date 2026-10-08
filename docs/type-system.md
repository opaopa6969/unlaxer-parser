# 型表現・型関係・呼出し推論

Java の `org.unlaxer.dsl.semantic.TypeSystem` / `CallInference` と、Rust の
`unlaxer_runtime::type_system` / `call_inference` は、provider が供給した型情報を使う
追加 API である。既存 [SemanticModel](semantic-model.md) と
[ProjectSymbolIndex](project-symbol-index.md) の入力・結果・位置は変更しない。
この API 自体が Java、TypeScript、Rust の完全な型検査器を名乗ることはない。

## TypeRef と代入

`TypeRef(kind, name, arguments)` は両言語で同じ不変木を表す。

| kind | 表現 |
|---|---|
| NAMED | provider の正規化済み型名と型引数 |
| VARIABLE | 一意な型変数 ID。未束縛のままなら UNKNOWN |
| UNKNOWN | 不完全なソースなどで型が不明。無効な型名の代用にしない |
| UNION / INTERSECTION | 非空の型リスト |
| NULLABLE | 1つの内部型と null |
| FUNCTION | 引数型の後に戻り値型を置く。最後の1要素だけなら引数なし |
| ALIAS | alias 名と型引数。alias 定義はパラメータと本体を持つ |
| NULL | null の値の型 |

NAMED / VARIABLE / ALIAS のみ非空 name を持つ。型変数 ID は宣言側で名前空間を付ける。
別の binder が同じ ID を再使用すると意図しない代入になるため、例えば `Box.T` と
`function.id.T` のように区別する。fixture の単一 binder では短い `T` を使う。

`substitute` は map の束縛を再帰的に解決する。未束縛変数は VARIABLE のまま残し、
`T → T` は恒等変換、`T → U → T` と `T → Box<T>` は循環として拒否する。
これは循環のない解を閉じる操作であり、循環した解を無限木として表さない。
alias 自身のパラメータは適用時に束縛する。有限の `Id<Id<Int>>` は循環ではない。

## Provider と適合判定

Provider は安定した capability 集合、名前付き型の検証・適合判定、推論に使う
型パラメータの variance を供給する。比較 callback を使えば共通の予算・循環検出に
参加できる。provider の実装・宣言・capability は同じ解析世代で変更しない。
provider が任意コード内で独自に再帰する場合、その処理の終了性は provider が管理する。

同梱 `DeclaredProvider` は宣言データを読む小さな参照実装で、policy を明示する。

| policy | 名前付き型の判定 |
|---|---|
| NOMINAL | 同名の型引数を variance に従って比較し、明示された parent を辿る |
| STRUCTURAL | structural と指定された対象型について、必要な読み取り専用 field の存在と型を比較する。それ以外は nominal |
| TRAIT | provider が登録した明示的な適合 edge を辿る。Rust の impl 探索・ownership 検証を実装したとは扱わない |

invariant は両方向、covariant は値→期待型、contravariant は逆方向に比較する。
関数の引数は contravariant、戻り値は covariant。union の値は全要素が期待型へ
適合する必要があり、union の期待型は少なくとも1要素へ適合すればよい。
intersection の期待型は全要素への適合を要求し、intersection の値は少なくとも1要素で
証明できる適合を扱う。intersection の field を集約する完全な構造的 solver ではない。

Java 向け provider は nominal・invariant container を基礎に、boxing、数値変換、
wildcard、最適 overload 選択等を追加できる。TypeScript 向け provider は structural
policy を出発点にし、mutable field、conditional/mapped type、制御フローの narrowing
等を担当する。Rust 向け provider は trait 適合の供給に加えて associated type、
lifetime、ownership 等を担当する。これら言語固有の全規則は #378 の外部 adapter 側で扱う。

## 判定結果と制限

比較は `Decision(status, rule, evidence)` を返す。rule と子の evidence が根拠の木に
なる。YES / NO / UNKNOWN に加えて UNSUPPORTED / CYCLE / LIMIT / INVALID を区別する。
未宣言型・arity 不整合は INVALID、capability 不足は UNSUPPORTED。未対応の分岐を
別の成功分岐で隠さず、solver failure を UNKNOWN や成功へ変えない。

通常の型計算予算は呼び出し側が 1〜4096 step で設定する。再帰深さは128未満。
入力木の検証にも4096 nodeの上限がある。名前付き宣言内の代入には256 stepを使う。
同じ適合対・同じ側での同じ alias 適用の再訪は CYCLE、引数が増え続ける展開などは
LIMIT。再帰的な record は表現できるが、構造的な循環比較の自動的な coinduction は
行わない。型の単純な同名一致は parent の探索を要しない。

## 制約と呼出し推論

`Signature` は型パラメータ・上限制約、引数型、戻り値型、varargs、宣言の
URI / version / CP span を持つ。`Call` は呼出し文書の URI / version / span、
入力済み引数と位置、期待する戻り値型を持つ。未知の期待戻り値は UNKNOWN とする。
可変長引数は最後の引数型を繰り返し使い、引数なしの varargs signature は拒否する。

推論は引数と期待戻り値から型変数への下限・上限を集め、variance と関数型の方向を
反映する。同じ型構築子の内部にある変数を扱い、選んだ解を引数・戻り値・宣言された
上限制約へ代入して全て再検証する。共通の上限・下限が直接比較で選べなければ、
provider が対応する union / intersection を作る。capability がなければ
UNSUPPORTED とし、言語固有の最小上界を勝手に選ばない。

この参照推論は一般の higher-kinded 型、alias 内部からの逆推論、異なる型構築子間の
逆推論を行わない。解けない変数は未束縛のまま残り、UNKNOWN として観測できる。
alias の適合判定・代入自体は TypeSystem で使える。ラムダの未知の引数型は UNKNOWN
を保持し、期待される関数型を返す。ラムダ AST を確定した型付きノードへ書き換えない。

各 Candidate は代入、代入後の引数・戻り値、全 Constraint とその Decision を返す。
引数・戻り値の制約は呼出し文書の位置、型変数の bound 制約は宣言文書の位置を保つ。
Result の URI だけで別文書の宣言 span を解釈してはいけない。

結果は RESOLVED / AMBIGUOUS / UNKNOWN / INCOMPATIBLE と solver failure を区別する。
適合する overload が複数なら AMBIGUOUS として全候補を残す。宣言順で1つを選ばない。
言語固有の「最も具体的な候補」の選択は provider / adapter の後段に属する。
推論全体にも 1〜4096 step の予算を設け、打切り時は途中の候補を成功と公開しない。

## 補完と診断

`expectedArgument` は編集中の引数を UNKNOWN にして、他の引数と期待戻り値から
候補を絞る。`expectedTypes` は残った候補からその位置の型を返す。
`assess` は候補値と期待型を比較する共通の Decision を返し、`complete` は同じ判定を
使って YES / UNKNOWN の値をこの順で並べる。同順位は名前の code point 順。
不適合・未対応の理由を UI に出す場合も `assess` の根拠を使う。
値が持つ定義 URI / version / span は保持する。

```java
var inference = new CallInference(typeSystem, 256);
var resolution = inference.expectedArgument(signatures, call, 1);
var items = inference.complete(visibleValues, resolution, 1);
```

```rust
let inference = CallInference::new(&type_system, 256)?;
let resolution = inference.expected_argument(&signatures, &call, 1)?;
let items = inference.complete(&visible_values, &resolution, 1);
```

可視シンボル・候補 signature の収集は project / language adapter が行う。
この API は runtime object を実行して探さず、ネットワークを使用しない。
古い非同期結果の破棄や edit 適用前の version 再確認は query 層 #376 へ接続する。

## 共通の検証

[型関係 corpus](../spec-corpus/type-relations/corpus.json) の51ケースと
[呼出し corpus](../spec-corpus/call-inference/corpus.json) の24ケースを両実装へ適用し、
独立期待値・Java・Rust の3者を比較する。型関係の根拠、代入、全候補、制約の状態と
元文書の位置、補完と診断の一致、varargs、期待戻り値・ラムダ型、capability 不足、
循環、予算打切りを含む。既存 SemanticModel / ProjectSymbolIndex の期待値は変更しない。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=TypeSystemConformanceTest,CallInferenceConformanceTest,SemanticModelConformanceTest,SemanticModelUbnfTest,ProjectSymbolIndexConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 clippy --locked --manifest-path rust/Cargo.toml \
  -p unlaxer-runtime --all-targets -- -D warnings
```

CI は `rust-type-relations.tsv` / `rust-call-inference.tsv` を必須 artifact として保存する。
