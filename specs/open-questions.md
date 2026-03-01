# 未解決の設計疑問

> 最終更新: 2026-03-01

仕様書作成時に発見された設計上の疑問。各項目は確認後、仕様への反映または却下を行う。

---

## OQ-DSL-001: トークン解決のバリデーションギャップ

**対象**: `token NAME = ParserClass` 宣言

**疑問**: `token NAME = ParserClass` でパーサークラスが存在しない場合、UBNF バリデーション（`GrammarValidator`）ではエラーにならず、生成された Java ソースのコンパイル時に初めて失敗する。

**疑問の詳細**:
1. これは意図的な設計判断か？（バリデーション時にクラスパスが不明なため検証不可能という制約？）
2. 将来的にバリデーション段階での検証を追加する予定はあるか？
3. 既知パーサーパッケージのホワイトリストに基づく「既知クラスかどうか」の警告レベルチェックは有用か？

**影響**: [token-resolution.md](token-resolution.md)、[validation.md](validation.md)

---

## OQ-DSL-002: セルフホスティングの完了計画

**対象**: `grammar/ubnf.ubnf` と Bootstrap パーサー

**疑問**: UBNF 文法自体が UBNF で記述されている（`grammar/ubnf.ubnf`）が、実際のパースは `org.unlaxer.dsl.bootstrap` パッケージのハンドコードされた Bootstrap パーサーが行っている。

**疑問の詳細**:
1. セルフホスティング（`ubnf.ubnf` を unlaxer-dsl 自身で処理してパーサーを生成する）の優先度は？
2. Bootstrap パーサーと `ubnf.ubnf` の間に差異がある場合、どちらが正とされるか？
3. セルフホスティング完了後、Bootstrap パーサーは廃止されるか、フォールバックとして残すか？

**影響**: [overview.md](overview.md) のセルフホスティング節

---

## OQ-DSL-003: メタデータ専用アノテーションのパーサー消費ロードマップ

**対象**: `@interleave`, `@backref`, `@scopeTree`

**疑問**: これら3つのアノテーションは現在メタデータとして受理・保存されるが、パーサーの認識動作には影響しない。SPEC.md には "metadata-only" と明記されている。

**疑問の詳細**:
1. パーサー生成でこれらのメタデータを消費する計画のタイムラインは？
2. `@interleave` は PARSER-IR-DRAFT.md で「BNF extension（文法レベル）に属する」とされているが、現在はアノテーション（メタデータ）扱い。この矛盾はどう解消される予定か？
3. `@backref` のパーサーレベル制約（マッチング時に後方参照を検証）は実装可能性が検討されているか？

**影響**: [annotations.md](annotations.md)、[parser-ir.md](parser-ir.md)
