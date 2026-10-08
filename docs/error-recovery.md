# エラー回復: CSTの継続と評価の境界

Issue: [#331](https://github.com/opaopa6969/unlaxer-parser/issues/331)。
対象はClassicのJava/Rust runtimeと、Java/native Rust両hostのUBNF生成経路。

## 何を回復するか

```ubnf
grammar Statements {
  @root @mapping(Program, params=[items])
  Program ::= { Statement @items };
  @recovery(sync=';')
  Statement ::= 'ok;';
}
```

`ok;bad;ok;`では、正常な前後のstatementに加えて、`bad;`の範囲`[3,7)`を回復CSTに残し、
構文解析を継続する。回復は不正なソースを正しい意味へ変換する処理ではない。
通常のtyped mapperは、回復領域を含む木を`cannot map recovered syntax`で拒否する。
エディタ等はCSTと回復診断を扱えるが、欠損した評価値を黙って作ってevaluatorへ渡さない。

必ず失敗する[`ERROR(...)`](error-elements.md)とは別機能である。
`ERROR`が報告した失敗を外側の回復combinatorが回復することはできる。

## 回復モード

childを通常どおり試し、成功すればその結果を保つ。失敗した場合は、その試行のcursor、
capture、scope、登録された利用者状態を巻き戻し、試行開始位置から回復範囲を決める。
回復後の外側が失敗すれば、回復markerも含めて通常のtransactionで巻き戻る。

| UBNF / runtime mode | 同期候補が見つかる場合 | 見つからない場合 |
|---|---|---|
| `sync` / `Sync` | 最も手前のtokenを含めて消費。同位置で複数一致なら最長token | 失敗 |
| `auto`（候補あり）/ `BeforeSync` | 候補の直前まで消費し、候補自体はcallerへ残す。先頭一致で0幅なら失敗 | 失敗 |
| `skip` / `Skip`（候補あり） | 候補の直前まで消費。先頭一致なら1 CP消費して前進する | EOFまで消費 |
| `skip` / `Skip`（候補なし） | 1 CP消費 | EOFなら失敗 |

すべての回復には1 CP以上の前進が必要。空入力からの挿入回復や、欠けた構文要素の合成は行わない。
`Skip`の先頭候補1 CP消費は、その後callerが要求するdelimiterを失わせる場合がある。
その場合は外側が失敗し、回復を含む全試行を巻き戻す。成功を保証する指定ではない。

`auto`は既存の文法走査から後続literal候補を求める。参照直後の要素や参照先の先頭literal、
一部のgroup/反復を調べるheuristicであり、nullableや間接参照を網羅する形式的FOLLOW集合ではない。
候補が得られなければ、従来どおり`;`を含めて消費する同期回復へfallbackする。
既知の候補がある場合にまでdelimiterを消費していた旧Java生成処理は修正した。
文法にとって重要な境界は、明示的な`sync`とrule構造で定義できる。

## 同期tokenとUnicode

UBNF `sync=';,}'`はカンマ区切りlistとして解釈し、各片をJava `String.trim()`相当でtrimする。
空片を除いたlistが空なら`;`を使う。カンマそのものをこの構文で指定するescapeはない。
NBSP/EM SPACEはJavaの`trim()`では除去されないため同期tokenとして残す。native frontendも揃える。

正規化後の重複tokenと、低水準APIで直接与えた空文字tokenは拒否する。
生成時には両hostで拒否する。手書きAPIではJavaは構築時の例外、Rustはparse時の失敗となる。
手書きSync APIでの空listは合法だが、child失敗時に回復できる同期点はない。
探索用のUTF-8/UTF-16位置と、公開cursor/spanのUnicode code point数を混同しない。
同期tokenが補助文字を含む場合や、その手前に補助文字がある場合も過剰消費しない。

## 公開APIと状態

- Java: `SyncPointRecoveryParser`と生成wrapper。`RecoveryDiagnostic.from(context)`または
  `RecoveryDiagnostic.from(token)`から`start/end/message`を取得する。専用markerは
  `ErrorMessageParser`の派生で、消費した回復領域全体をsourceとして持つ。
- Rust: `Expr::Recovery` / `Expr::recover`、`RecoveryMode::{Sync,BeforeSync,Skip}`。
  `ParseContext::recoveries()`と`Tree::recoveries()`から`RecoveryDiagnostic`を得る。
  `RECOVERY_ERROR_RULE`のCST markerとmessage/spanを保持する。
- owned Treeの回復記録はそのrootに属するもの。以前の別parseの回復や、捨てた枝を含めない。
  保持した木の記録は、その後のcontext操作で書き換わらない。
- 回復は構文診断であり、scopeの意味診断とは別に保持する。失敗探索のnative expected候補とも別である。
  childの構文失敗履歴は消さない。同期点より先で失敗したchildの最遠位置が、後続のfatal診断に残る場合がある。

Java `MatchOnly` 内の回復は消費cursorを進めず、既存の照合cursorだけを進める。
Rust `Ahead` は照合cursorも戻す既存の契約であり、このcursor差は汎用伝播の未対応範囲として残る。
どちらも外側へ回復markerを公開せず、回復できるchildの先読みは成功する。

生成rootもrule参照も同じ回復wrapperを使う。正常時のAST形状は変えない。
回復済みCSTに対する通常のroot/subtree mapping、source-based typed parseは拒否する。
Javaの生成`diagnose`は全入力を回復込みで受理した場合に`recovery`分類を返し、
全件の位置とmessageは上記CST/context APIから得る。Rustの構文parseは回復を含むTreeを
成功として返すので、`recoveries()`を確認する。通常mapperは同様に拒否する。
Javaの`recovery`分類では`offset`が最初の回復領域の開始、`farthestOffset`がその終了、
`expected`が回復messageであり、通常の`syntax`分類の最遠失敗位置とは区別する。
後続に未消費入力があれば`trailing_input`、外側が失敗すれば`syntax`を優先する。

回復の先頭はchildのFIRST集合だけでは予測できないため、候補除外は保守的に無効化する。
回復を含むruleはsafe memoの対象外とし、成功時の回復情報をmemo hitで消失させない。
診断policyを変えても回復message/spanは収集し、通常の失敗時詳細化とは別に扱う。

生成mapperの回復検査は、明示的な回復または不明なcustom parser経路がある場合に残す。
Javaでは外部Simple token・adapter・importを保守的に扱い、閉じた生成文法だけCST走査を省く。
RustではIRのRecoveryまたはCustomTokenが検査を必要とする。custom parserの内部で回復する場合も
通常ASTへ黙って渡さない。任意の動的なparser書換えや、利用者が偽造したTreeの正しさまで保証しない。
Javaで必要になるCST走査の費用は本機能の性能改善を意味せず、ここでは速度向上を主張しない。

## 検証と互換性

[共通corpus](../spec-corpus/recovery/README.md)は18文法43入力 × 6設定。
両hostの全5生成file一致、javac/rustc実行、JVM-free生成・実行、両cursor・全回復span/message、
scope状態、正常AST/span、回復時mapping拒否を比較する。
runtime単体では利用者状態、capture、lookahead、外側rollback、longest choice、snapshotも検証する。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RecoveryConformanceTest,RustUbnfFrontendConformanceTest,RustPortabilityConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --workspace --locked --manifest-path rust/Cargo.toml
```

CIは`unlaxer-dsl/target/rust-recovery.tsv`を必須artifactとする。
旧Javaからの変更は、補助文字の位置修正、同位置同期tokenの決定性、空token拒否、SKIP生成の修正、
root annotationの有効化、全幅marker、AUTOのdelimiter保持、回復時の明示診断とmapping拒否である。
旧来の「回復したなら通常ASTも必ず作れる」という扱いには依存しないこと。

部分/error ASTの型、汎用consume/invert伝播、増分解析、Rust LSP/DAPは別の未完了項目。
これらをこの回復実装のテスト成功で完了扱いにはしない。
