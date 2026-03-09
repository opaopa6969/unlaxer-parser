# 未解決の設計疑問

> 最終更新: 2026-03-01

仕様書作成時に発見された設計上の疑問。各項目は確認後、仕様への反映または却下を行う。

---

## OQ-DSL-001: トークン解決のバリデーションギャップ — **調査完了**

**対象**: `token NAME = ParserClass` 宣言

**結論**: **意図的な設計判断**。バリデーション時にクラスパスが不明なため、エラー検出を Java コンパイラに委譲している。

### 調査結果

- `GrammarValidator` は `grammar.tokens()` を一切検査しない。`grammar.rules()` のみをイテレートする
- トークン関連のエラーコード（`E-TOKEN-*`）は存在しない
- `ParserGenerator.resolveTokenImports()` が `Class.forName()` で既知パッケージ（`org.unlaxer.parser.{clang, elementary, posix}`）を試みるが、見つからなくても**エラーにしない**（非ブロッキング）
- 完全修飾クラス名（`.` を含む）の場合は直接使用される（カスタムパーサーへの対応）
- クラスが解決できない場合、import 文なしで生成コードにクラス名が出力される → Java コンパイル時に `cannot find symbol` エラーとなる

### 疑問への回答

1. **はい、意図的な設計判断**。バリデーション段階ではクラスパスが確定しないため、存在確認は不可能。ユーザーが任意のカスタムパーサークラスを指定できる柔軟性も意図している
2. **完全な検証は困難だが、警告レベルのチェックは有用**。後述の改善案を参照
3. **有用**。既知パッケージのホワイトリストに存在しない場合に WARNING を出す程度の改善は低コストで実現可能

### 改善案（任意）

- `GrammarValidator` に WARNING レベルの検証を追加: 既知パッケージに存在せず、完全修飾名でもないクラス名に対して `W-TOKEN-UNRESOLVED` を発行
- エラーではなく警告とし、ユーザーが意図的にカスタムクラスを使う場合を阻害しない
- `--strict` フラグとの組み合わせで、警告をエラーに昇格させることも可能

**反映先**: [token-resolution.md](token-resolution.md)、[validation.md](validation.md)

---

## OQ-DSL-002: セルフホスティングの完了計画 — **調査完了**

**対象**: `grammar/ubnf.ubnf` と Bootstrap パーサー

**結論**: セルフホスティングの「能力」は証明済みだが、本番コードパスはまだ手書き Bootstrap を使用している。

### 調査結果

**本番の処理フロー**:
- `CodegenMain` → `UBNFMapper.parse()` → `UBNFParsers.getRootParser()` で手書き Bootstrap パーサー（`org.unlaxer.dsl.bootstrap.UBNFParsers`、939行）を使用

**セルフホスティングの証明**:
- `SelfHostingRoundTripTest` が以下を実行し成功:
  1. `grammar/ubnf.ubnf` を手書き Bootstrap でパース
  2. `ParserGenerator` で新しい `UBNFParsers.java` を生成
  3. `javax.tools.JavaCompiler` でコンパイル
  4. `URLClassLoader` でロード
  5. 生成されたパーサーで `ubnf.ubnf` を再パース → 全入力消費成功

**本番切り替えの未完了要因**:

| コンポーネント | 手書き | 生成可能 | 本番利用 |
|-------------|--------|---------|---------|
| `UBNFParsers`（パーサー） | あり | あり（テスト動作確認済み） | 手書き |
| `UBNFAST`（AST 型定義） | あり | なし（sealed interface 未対応） | 手書き |
| `UBNFMapper`（AST 変換） | あり | なし（スタブのみ） | 手書き |

パーサー生成は完了しているが、AST 型定義とマッパーの生成が未対応のため、全体としては Bootstrap に依存している。

### 疑問への回答

1. **パーサーのセルフホスティングは完了（テストで証明済み）**。本番切り替えは AST/Mapper 生成の完了待ち
2. **`ubnf.ubnf` が正**。Bootstrap パーサーは `ubnf.ubnf` を手動実装したもの。差異があれば Bootstrap を修正すべき
3. **将来的には Bootstrap パーサーを廃止可能**。ただし AST/Mapper 生成が完了するまでは維持が必要

**反映先**: [overview.md](overview.md) のセルフホスティング節

---

## OQ-DSL-003: メタデータ専用アノテーションのパーサー消費ロードマップ — **調査完了（spec 修正済み）**

**対象**: `@interleave`, `@backref`, `@scopeTree`

**結論**: 当初の spec 記載（「メタデータとして受理。パーサー動作は未変更」）は不正確だった。実際にはジェネレータでの消費が想定以上に進んでいる。

### 実態（調査結果）

| アノテーション | 構文解析 | AST 保存 | query API 生成 | パーサー動作への反映 | セマンティック検証 |
|-------------|---------|---------|---------------|------------------|----------------|
| `@interleave` | 完了 | 完了 | 完了 | **完了**（DelimitedChain 選択に反映） | N/A |
| `@backref` | 完了 | 完了 | 完了 | N/A（パーサーレベルではない） | **未実装** |
| `@scopeTree` | 完了 | 完了 | 完了（11+メソッド） | N/A（パーサーレベルではない） | **未実装** |

### TinyExpression P4 UBNF での使用状況

| アノテーション | 使用箇所 | 意図 |
|-------------|---------|------|
| `@interleave(profile=javaStyle)` | `VariableDeclaration`, `ImportDeclaration` | 要素間に Java スタイルのコメント/空白を自動インターリーブ |
| `@scopeTree(mode=lexical)` | `MethodDeclaration` | メソッド宣言がレキシカルスコープを導入することを宣言 |
| `@backref(name=methodName)` | `MethodInvocation` | `call foo(...)` の `foo` が先に宣言されたメソッド名を後方参照 |

### 実装済み（2026-03-08 完了）

unlaxer-dsl 0.3.0 で以下がすべて実装された:

1. ✅ **スコープツリー構築**: `@scopeTree(mode=lexical)` → `TransactionListener` 生成、`ScopeStore.enter/leave`
2. ✅ **シンボルテーブル**: `@declares(symbol=x)` → `ScopeStore.declare`、`ScopeStore.resolve/isDeclared`
3. ✅ **後方参照検証**: `@backref(name=x)` — スコープ参照モード（grammar に @scopeTree あり）と バックリファレンスモード（同一ルール内トークン一致）の両方
4. ✅ **LSP 統合**: `ScopeStore.getDiagnostics()` → `publishDiagnostics`（tinyexpression v0.2.1）

**残バックログ**: go-to-definition / find-references への接続（LSP TextDocument/Definition, References）
