# 未解決の設計疑問

> 最終更新: 2026-03-01

仕様書作成時に発見された設計上の疑問。各項目は確認後、仕様への反映または却下を行う。

---

## OQ-COMMON-001: stopped ステータスのセマンティクス境界 — **解決済み**

**対象**: `Parsed.Status.stopped`

**解決**: 調査により設計意図を確認（2026-03-01）。仕様に反映済み。

### 調査結果

`stopped` は **「パース自体は成功したが、親コンビネータの後続処理を中断すべき」という早期終了シグナル** である。コード補完/サジェスション収集のための制御フロー信号として設計された。

#### 生成元

- `SuggestsCollectorParser`（unlaxer-common 内で唯一の生成元）: コード補完用パーサー。Choice の最後のオプションとして配置され、他の候補パーサーが全て失敗した際にサジェストを収集し `stopped` を返す

#### 消費箇所と動作

| 消費箇所 | 動作 |
|---------|------|
| `ChainInterface:24` | `break` → コミット。残りの子をスキップして部分成功 |
| `Occurs:49` | `break` → matchCount で成否判定。ループ中断だが蓄積済み結果は保持 |
| `ExclusiveNakedVariableParser:71` (tinyexpression) | Chain 相当の手動ループで同パターン |
| `VariableDeclarationMatchedTokenParser:56` (tinyexpression) | while ループ内で同パターン |

#### 疑問への回答

1. **はい**。`stopped` は「パース自体は成功したが、後続処理を中断すべき」シグナルである
2. **はい、意図的**。Occurs 内での matchCount ベースの成否判定は正しい動作。サジェスト収集中にそれまでマッチした回数が min 以上であれば成功とすべき
3. **コード補完/LSP サジェスション収集**が主要ユースケース。入力途中のソースを「ここまでは成功」として扱い、後続パースを中断する

**反映先**: [core-types.md](core-types.md) の Parsed セクション（stopped の設計意図セクションを追加）

---

## OQ-COMMON-002: Not コンビネータのトランザクション不使用 — **解決済み**

**対象**: `org.unlaxer.parser.combinator.Not`

**解決**: バグとして修正済み（2026-03-01）。

修正内容:
- `begin()`/`commit()`/`rollback()` トランザクションを追加
- 子パーサーを常に `TokenKind.matchOnly` で実行（入力を消費しない）
- 子パーサー成功時はロールバック後に `FAILED` を返す
- 子パーサー失敗時はコミット後に `succeeded` を返す

全169テスト通過を確認。

---

## OQ-COMMON-003: メモ化（doMemoize）の実装状況と計画

**対象**: `ParseContext.doMemoize`

**疑問**: `ParseContext` に `doMemoize` フラグが存在し、TODO コメント（`// TODO store successfully token's <position,tokens> map`）があるが、メモ化の実装が見当たらない。

**疑問の詳細**:
1. メモ化は将来実装予定か、検討段階のまま放置されているか？
2. 実装する場合、Packrat Parsing 方式を想定しているか？
3. メモ化のスコープ（どのレベルのパーサー結果をキャッシュするか）は決まっているか？

**影響**: [parse-context.md](parse-context.md) の制限事項セクション
