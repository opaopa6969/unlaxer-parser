# Java / Rust の transactional scope store

追跡: #174、親 #111。P4 の `@scopeTree` / `@declares` / `@backref` を Rust の生成経路へ
接続する前提として、手書き parser と生成 parser が共有するスコープ状態の寿命を揃える。
この変更だけで Rust がこれらの生成注釈を受理するわけではない。

## 状態と寿命

store は各 ParseContext に属し、lexical scope stack と global scope を持つ。
宣言の lookup は内側から外側へ検索する。同じ scope で再宣言すると lookup は後勝ちに
なるが、全宣言の履歴には両方を残す。scope を leave するとその名前は見えなくなるが、
成功した宣言・参照・semantic diagnostics はパース後の利用のために残る。
depth 0 での leave と空名の declare/addReference は no-op。
参照記録は自動的な未定義警告とは別操作である。

失敗した transaction では、開始前の scope stack、global lookup、全宣言、参照、
semantic diagnostics を復元する。子の成功を親が後で rollback した場合も同じ。
`clearDiagnostics` も復元対象。初回 store 利用が既に開いている transaction の途中でも
それ以前へ戻せる。外部 I/O や任意の共有オブジェクトの副作用まで取り消すものではない。

syntax の最遠失敗ヒントと semantic diagnostics は異なる。Rust は従来どおり syntax の
最遠失敗を失敗後も保持するが、試行中に追加した semantic diagnostics は破棄する。

## Java の修正と互換性

従来の ScopeStore は自動 rollback を文書化していたが、ParseContext に該当する
snapshot はなく、失敗した枝の宣言・参照・診断が残った。#174 では common の
`TransactionalState` と `ParseContext.registerTransactionalState` を追加し、
ScopeStore が所有する状態だけを明示的に保存する。登録は最初の変更前に行い、
同じ instance の重複登録は無害。登録は context の寿命中保持し、解除 API は設けない。
scope store を使う新しい DSL runtime と新しい common runtime は同時に更新する。

begin の通知前に checkpoint を取り、rollback 通知後に復元する。通知中の初回登録も
当該 transaction と開いている親の baseline に含める。
`TransactionalState.checkpoint()` が返す復元 action は独立した snapshot を使い、
例外を投げたり transaction のネストを変更したりしてはならない。
複数 owner の復元順序には依存できない。
一般の parser/listener 例外からの context 再利用は保証しない。

既存の ScopeStore メソッドは維持し、返した unmodifiable list の live view も維持する。
rollback 後に view から破棄済みイベントが見えないよう、元の list を復元する。
Java で独立 snapshot が必要なら `List.copyOf(ScopeStore.getAllDeclarations(ctx))` 等を使う。
このバグ修正により、以前は失敗した試行から漏れていたシンボルや診断は見えなくなる。
それらを正式な解析結果として利用していた場合は、成功した解析から取得するよう修正する。

`MatchOnly` と `Not` は wrapper の終了時に登録済み state を復元する。
先読み内部の子 commit は後続の子から見えるが、外部には残らない。
全ての `commit(..., TokenKind.matchOnly)` を rollback に変えるわけではない。
MatchOnly の既存の matched cursor と token の契約は保ち、消費位置と同一化しない。

## Rust API

`unlaxer_runtime::{ScopeStore, SymbolInfo, ReferenceInfo, SymbolDiagnostic, Severity}` を公開する。
`ParseContext::scopes()` / `scopes_mut()` でアクセスし、既存 parser checkpoints と一緒に
保存・復元する。利用者の文字列キー付き state とは独立しており、キーの衝突はない。

```rust
use unlaxer_runtime::{Expr, ParseContext, Severity};

let mut context = ParseContext::new("name");
context.with_scope(|local| {
    let parsed = local.parse(&Expr::Identifier)?;
    local.scopes_mut().declare("name", parsed.span.start);
    local.scopes_mut().add_reference("name", parsed.span.start, 4);
    local.scopes_mut().add_diagnostic("example", 0, 4, Severity::Info);
    Ok(())
}).unwrap();
assert!(!context.scopes().is_declared("name"));
assert_eq!(context.scopes().all_declarations().len(), 1);
```

`with_scope` は成功時に leave し、エラー時は transaction 全体を復元する。内部で手動の
enter/leave を使う場合は対応を保つ。panic の unwinding は既存 transaction と同様に
保証しないため、panic 後の context は破棄する。
`ScopeStore::clone()` は後続パースや元 context の破棄に影響されない owned snapshot。
単体で作った ScopeStore は transaction を持たず、context に属する場合だけ自動復元する。

offset/length は呼出し側で算出して渡す。Rust の位置は Unicode scalar（code point）であり、
UTF-8 byte 数や UTF-16 unit 数ではない。Java との共通 corpus は非負の code-point 値を
渡して比較する。既存 Java 生成 backref の length 算出など全注釈の位置契約を、この
runtime 比較のみで検証済みとは扱わない。

Java の `declaredInCurrentScope` は順序未規定、Rust の対応メソッドは名前順。
共通テストは lookup 集合を名前で整列し、全宣言・参照・診断は挿入順のまま比較する。

## 検証と残項目

共通 operation corpus は実 Java ParseContext の transaction と Rust transaction を動かし、
depth・lookup・宣言/参照/診断・consumed/matched cursor を独立期待値で照合する。
8 fixtures・9 contexts・17 observations を両言語で比較し、全4 severity も検証する。
CI は `rust-scope-store.tsv` を必須 artifact とする。
Rust の choice/optional/repeat/PEG lookahead と nested grammar、Java 生成 parser の
scope annotation についても、失敗した枝の変更が漏れないことを別途検証する。
Java match-only cursor と Rust PEG lookahead の位置の違いは、この変更では統一しない。

Rust UBNF の annotation lowering/emitter、正確な capture binding、symbol metadata の
AST/IDE 搬送、全 tinyexpression、rustcodeblock、LSP/DAP は引き続き未完了である。
