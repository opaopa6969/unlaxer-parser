# Versioned stateful failure memo (#629)

Java/Rust の既定は引き続き OFF です。safe policy の failure entry は、両 cursor・parser/rule・
Classic mode・reset/trivia等に加え、入力を観測する状態の version を区別します。
これは任意の stateful parser や成功時の副作用を自動再生する仕組みではありません。

## Version と rollback

Rust `ParseContext::memoization_state_version()` は現在の epoch を `Option<usize>` で返します。
`mark_memoization_state_changed()` は利用者が管理する parse-visible state の変更を通知します。
state を読む custom callback をこれだけで safe に認定することはありません。

自動更新は ScopeStore の enter、実際の leave、空でない declare、空でない clear_diagnostics と
owned capture の公開です。reference/diagnostic の追記は parse 後に読む write-only event のため
version を変えません。空の declare/clear と global depth での leave も変えません。
immutable AST field capture は一般 owned binding と別であり、この更新対象ではありません。
CaptureScope は binding を戻しますが、成功した内側 capture が進めた epoch は保守的に維持します。
これは Java の既存 CaptureBindings/ScopeStore の規則です。

transaction の rollback は current epoch と状態を戻し、epoch を割り当てる next counter は戻しません。
別 branch の同じ操作も別 epoch です。longest-choice が保存済み winner の状態を採用するときも、
破棄した候補で使った next counter を維持します。追加の heap allocation や共有 mutable counter は不要です。
Java は ScopeStore owner を初めて登録するときにも version を進め、Rust は context の構築時点で
empty owner を持ちます。ordinal の絶対値を両 host 共通の識別子とせず、同一性・変化・rollback の関係を比較します。

Rust counter は wrap しません。`usize::MAX` に達すると、その context は rollback 後も永久に
memo miss へ戻ります（getter は None）。Java の既存 long counter は `Math.incrementExact` により
overflow を例外にします。その context は破棄してください。いずれも古い epoch へ alias しません。

## 安全性と保存

Rust key は 4 から **5 usize** へ増えました（64-bit で key 自体は32→40 bytes）。
ハッシュへの切り詰めや状態の bit 制限で衝突を許す設計にはしていません。
normal/Classic の failure/success とも version を区別し、既存の保持窓と snapshot 予算を維持します。
資源上限は #634 の current depth <= saved depth 条件を満たす場合だけ再利用し、resource failure は保存しません。

`RuleEffects` の safe failure は child の安全性へ伝播します。失敗までに作られた scope/declaration/
reference/diagnostic は rollback 済みなので、failure hit で作用を再実行しません。
その rule の外で追加した write-only event は cache の hit 後も保持します。
成功した `RuleEffects` は success replay の対象外です。

opaque custom/Character predicate、OwnedCapture/Replay/Backreference/CaptureScope、name-resolution、
recovery の rule を無条件に safe へ広げません。安全な pure descendant はそれらの内側でも個別に cache できます。
Java の明示 `SafeFailureMemoizable` / `@memoSafeToken` も利用者による安全性の約束です。
任意 user state の変更は自動検出せず、Java/Rust のどちらも version 通知だけで不透明 callback の安全を証明しません。

[独立共通 oracle](../conformance/stateful-failure-memo/README.md) は実 hit、異 state の miss、
rollback 後の元 entry、write-only events、両 cursor、原文、位置、AST、syntax/semantic diagnostics を比較します。
[Classic memo](classic-memo.md) の ID/metadata コピー隔離・rollback と observer 境界も維持します。
以前の no-retry 約41% overhead は [測定記録](../conformance/pure-success-memo/benchmark-results.tsv) に残します。
5word keyでの[再測定](../conformance/stateful-failure-memo/benchmark-results.tsv)もno-retry約62% overheadでした。
記録付き成功 replay、任意 stateful success、production workload の検証は本対応の完了条件と混同せず、親 #184/#197 は未完です。

同位置・多数epochの failure entry は [#644 の保持予算](failure-memo-budget.md) で件数・
単体/合計 charge を制限します。位置windowだけを有界性の根拠にしません。
cache-owned payload の制限であり、process RSSや任意外部object graph全体の保証ではありません。
