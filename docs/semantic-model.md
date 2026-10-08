# 型・scope・期待型の共通モデル v1

状態: 3.3.0-SNAPSHOT の追加 API。#372。Java / Rust の UBNF parser が返す AST / CST に、言語固有の adapter で意味を与える追加 API である。既存の文法、`ScopeStore`、TinyExpression の構文・実行意味は変更しない。

## 対象と分担

初版は名前付きの組み込み型・interface・record 型、明示的な subtype 関係、型付き field による合成、lexical scope、変数・引数・field の参照、callable の引数・戻り値を扱う。例えば `Context` が `IAccessor` 型の field を持ち、`DbAccessor` が `IAccessor` に適合する言語を定義できる。型 ID は言語側が namespace を含めて一意にする。field に自分自身の型を参照する再帰的なデータ構造も許す。

共通モデルは構文認識や runtime の Java / Rust class を型情報として使わない。adapter が AST から型宣言、scope、symbol、callable、呼出しを登録する。interface の method 実装の正当性、アクセス制御、初期化、言語固有の暗黙変換は adapter が検証する。初版の適合規則は名前による同一性と、明示された subtype 関係の推移閉包である。

Java・Rust の API は同じ契約を持つ。Java の generated AST を Rust へ送る必要はなく、それぞれの parser の結果から同じ意味情報を構築する。LSP や埋め込み言語の接続はこの API の consumer である。

## 型

- 型の kind は `BUILTIN` / `INTERFACE` / `RECORD`。すべて名前付きであり、同じ field を持つ別名の record を自動的に同一視しない。
- `supertypes` は登録済みの interface 型を指す。interface の多重継承、record の interface 適合を扱い、継承循環を拒否する。builtin の supertypes は空にする。
- field の型は登録済みの名前付き型を参照する。forward reference を許し、モデル全体の構築時に検証する。
- 特別な型 ID `?` は明示的な unknown。組み込み型として登録できない。未解決の文書・編集中の式を表す。名前の綴り間違いを unknown に変換せず、未登録の型 ID はモデル構築エラーにする。
- 適合は `YES` / `NO` / `UNKNOWN`。どちらかが `?` なら UNKNOWN、同一名または subtype 経路があれば YES、それ以外は NO。
- record の field はデータ型の合成情報であり、構造的型付けや field の getter / setter のコード生成を意味しない。

## 文書・scope・symbol

モデルは1つの文書 URI・snapshot version・ソース文字列を所有する不変 snapshot。公開 span は Unicode code point の半開区間。UTF-16 / UTF-8 は editor / parser の境界で変換する。

scope は ID、親 ID、span を持ち、1つの root が文書全体を覆う。子 scope は親の中にあり、兄弟は重ならない。root 以外の空 scope は拒否する。symbol は ID、名前、型、scope ID、宣言 span、可視開始位置を持つ。同じ scope の同名 symbol は拒否する。名前の shadowing は最も内側の scope を優先し、まだ可視でない内側の宣言は外側を隠さない。symbol の可視性は可視開始位置から所属 scope の末尾まで。hoisting の有無は adapter が可視開始位置で指定する。

型・signature・symbol の ID と source span は結果にも保持する。型が同じ別々の値を1件にまとめない。構築後の元 list の変更はモデルへ影響しない。

## 呼出しと補完

callable signature は ID、名前、引数型の列、戻り値型を持つ。call site は候補 signature ID の列と、入力済みの引数の型・span を持つ。候補の名前解決は adapter が行い、同名のメソッドを全プロジェクトから無条件に集めない。

`expectedTypes(callId, argumentIndex)` は、他の入力済み引数に明らかな不適合がない signature から、その位置の引数型を返す。編集中の対象引数の現在の型は絞り込みに使わない。すべての signature が不適合の場合は空を返す。候補 signature が未解決なら `?` を返す。初版は固定長の引数列であり、varargs・generic inference は外部 adapter の責務とする。

`completeArgument(callId, argumentIndex, cursor, version, prefix)` は同じ snapshot の該当引数の範囲内でのみ動作する。scope に見える symbol を prefix で絞り、期待型へ YES の候補、UNKNOWN の候補の順に返す。NO の候補は返さない。同順位は名前、symbol ID の Unicode code point 順で決定的に並べる。unknown を成功と表示せず、候補は実際の型・期待型・判定結果と宣言位置を保持する。

補完用の引数 span では、カーソルが末尾にある場合も許す。空の引数は start = end として保持できる。これは値の source span の半開区間を変更するものではない。古い version、存在しない call ID、不正な引数 index、引数範囲外の cursor は明示的な query error とする。

`process(context, ...)` の第2引数が `IAccessor` の場合、`DbAccessor db` と `IAccessor cached` は候補となる。`Object obj` は、初期値が DbAccessor であっても初版の型判定では候補にしない。runtime object を生成・実行して候補を探さない。

## 構築の失敗条件

重複 ID / 名前、未登録の型・scope・signature、継承循環、不正な親 scope、範囲外 span、重なった兄弟 scope、引数の順序や範囲の不整合を拒否する。Java は code・span を持つ例外、Rust は同じ code・span の Result error を返す。失敗した構築から部分的に有効なモデルを返さない。

## 今後の拡張

| 機能 | 初版との関係 |
| --- | --- |
| ジェネリクス・union / intersection・nullable・型推論 | TypeRef と provider の追加段階。未知の機能を v1 が対応済みと報告しない |
| Java / TS / Rust 固有の型・呼出し解決 | compiler / language service adapter に接続する。v1 の nominal 関係で全言語を代用しない |
| module / project の symbol index | URI・型 ID・snapshot の契約を拡張して別文書へ接続する |
| 不完全な AST、差分解析、診断キャッシュ | adapter が保持する snapshot と call-site を更新する。古い補完応答を適用しない |
| 埋め込み言語 | #369 の region / source map と組み合わせる |
| 型から constructor / factory 式を合成 | 参照可能な symbol の補完とは別 capability とする |
| TinyExpression のユーザー型構文・評価 | この共通モデルの導入後に別の言語変更として追加する |

初版は最新 Java・TypeScript・Rust の完全な型検査器ではない。各言語のボキャブラリは #370、共有配布は #368 で追跡する。

## API と実行例

Java は `org.unlaxer.dsl.semantic.SemanticModel`、Rust は `unlaxer_runtime::semantic::SemanticModel` を使う。Java は公開 record の list、Rust は `ModelData` の owned value で構築する。型 ID・symbol ID・call ID は adapter が安定して割り当てる。

```java
var expected = model.expectedTypes(callId, 1);
var candidates = model.completeArgument(callId, 1, cursorCodePoint, documentVersion, "");
```

```rust
let expected = model.expected_types(call_id, 1)?;
let candidates = model.complete_argument(call_id, 1, cursor_code_point, document_version, "")?;
```

候補の `reason()` は実際の型・期待型・YES / UNKNOWN を表示する。LSP へ渡す場合は consumer がこの情報を `detail` / `documentation` / `sortText` と、元文書の安全な `textEdit` へ変換する。この初版は LSP server やブラウザへ自動登録される機能ではない。

[小さな型付き言語の実行例](../examples/semantic-model/README.md)は、UBNF から生成した AST と source map を adapter が読み、ユーザーの型宣言を共通モデルへ登録する。Java と Rust の adapter を収録する。これらを宣言形式で共通化する後続仕様は #381。

## 対応と検証

| 観測する機能 | Java | Rust |
| --- | --- | --- |
| 名前付き型・interface の直接／推移的適合 | 対応 | 対応 |
| field 合成・forward reference・再帰 field | 対応 | 対応 |
| scope・shadowing・可視開始位置 | 対応 | 対応 |
| 入力済み引数から signature 候補を絞る・期待型の列挙 | 固定引数型で対応 | 同左 |
| 補完候補・unknown・判定理由・元位置 | 対応 | 対応 |
| 不正モデル・古い文書版・範囲外問い合わせ | code と span の例外 | 同じ code と span の Result |
| 既存 UBNF の生成 AST からの adapter | 実行例あり | Java/native frontend の生成一致と実行例あり |
| Java/TS/Rust の完全な意味規則、LSP 自動接続、TinyExpression の型構文 | 未対応 | 未対応 |

API の list / String / span 等は非 null。Java の負の位置や孤立 surrogate は拒否する。Rust の位置は usize、String は有効な UTF-8 のため、これらは型の時点で表現できない。共通の有効入力とモデル検証の失敗条件は同じ corpus で比較する。空文書の root scope は許す。

`SemanticModelConformanceTest` は [共有 corpus](../spec-corpus/semantic-model/corpus.json) の独立期待値と Java / Rust を比較する。`SemanticModelUbnfTest` は実際の文法生成・javac/rustc・native generator の出力一致・元ソース変更による補完結果の変化を検証する。CI は `rust-semantic-model.tsv` / `rust-semantic-ubnf.tsv` を必須 artifact とする。

複数文書・依存ライブラリの import / export、definition、型付き補完は
[ProjectSymbolIndex](project-symbol-index.md) で、このモデルを不変の
プロジェクトスナップショットへ束ねる。文法 import と対象言語の import は別契約である。

型引数、union / intersection、nullable、関数型、alias、varargs と overload の制約は
[TypeSystem / CallInference](type-system.md) で扱う。既存の固定引数 API と観測結果は維持する。

意味 query の編集跨ぎ再利用とキャンセルは [SemanticQueries](semantic-queries.md) を参照。
