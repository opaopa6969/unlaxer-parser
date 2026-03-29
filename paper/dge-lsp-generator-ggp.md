# DGE Session: LSPGenerator の GGP 化 — monolithic 出力から拡張可能な2層アーキテクチャへ

## テーマ
LSPGenerator が生成する monolithic な LanguageServer クラスを、EvaluatorGenerator と同じ GGP (Generated-Generated-Pattern) で abstract base + concrete subclass の2層構造に変換する設計のギャップを発見する。

## キャラクター
- ☕ ヤン・ウェンリー — 「要らなくない？」「最もシンプルな解は？」
- 🎩 千石武 — 「品質基準を示す」「ユーザーのために」
- ⚔ リヴァイ兵長 — 「汚い。動くもの見せろ。」
- 👤 今泉慶太 — 「そもそも」「誰が困るの」

## 前提コンテキスト
- LSPGenerator (`unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/LSPGenerator.java`) は GrammarDecl から `{Name}LanguageServer.java` を1ファイル生成する
- 生成される LSP は keyword 補完、hover (Valid/Parse error)、semantic tokens (valid/invalid の2種のみ)、diagnostics (parse error offset) という最低限の機能
- TinyExpressionP4LanguageServerExt (2684行) が生成クラスを extends し、以下を全て手書きで追加:
  - 8種の semantic token types (keyword, variable, number, string, operator, function, comment, type)
  - ErrorCatalog (TE001-TE024) による enriched diagnostics
  - FormulaInfo multi-section document handling (DocumentFilter)
  - IncrementalParseCache
  - ScopeStore 連携 (declarations, references, scope diagnostics)
  - 完全な ExtTextDocumentService: completion, hover, codeAction, definition, references, linkedEditingRange, documentSymbol, rename, documentHighlight, signatureHelp, codeLens, inlayHint, foldingRange, semanticTokensFull, callHierarchy, formatting
  - ExtWorkspaceService: workspace symbol
- EvaluatorGenerator は GGP 成功例: `abstract class TinyExpressionP4Evaluator<T>` を生成し、`evalXxx()` を abstract/concrete で使い分け
- DAPGenerator も存在し、TinyExpressionP4DebugAdapterExt が同様に手書き拡張している
- self-hosting (ubnf.ubnf → UBNF LSP) にはこの GGP 化が前提条件

---

## Scene 1: 現状の問題

先輩 (ナレーション): TinyExpressionP4LanguageServerExt は TinyExpressionP4LanguageServer を extends している。しかし、生成されたベースクラスには拡張ポイントがない。Ext クラスは getTextDocumentService() と getWorkspaceService() を override して完全に独自の inner class を返している。つまり、生成クラスの TextDocumentService は一切使われていない。

今泉: 「そもそも TinyExpressionP4LanguageServerExt って何をオーバーライドしてるんですか？ extends してるけど、本当に継承として使ってるんですか？」

ヤン: 「見てみたけど...正直ほとんど使ってないね。override しているのは `connect()`, `initialize()`, `parseDocument()`, `getTextDocumentService()`, `getWorkspaceService()`, `setCatalogResolver()` の6つ。そのうち `getTextDocumentService()` と `getWorkspaceService()` は完全に別の実装を返してる。`initialize()` は `super.initialize()` を呼んで catalog resolver の初期化だけ借りて、capabilities は全部書き直してる。」

  → **Gap 発見: 生成された TextDocumentService inner class は Ext では完全に無視されている。生成コードの半分以上が死んでいる。**

今泉: 「要するに、生成クラスから実際に使っているのは何ですか？」

ヤン: 「parseDocument() の基本構造、DocumentState record, ParseResult record、それと KEYWORDS フィールド...いや、KEYWORDS も Ext では独自の KEYWORD_SET と COMPLETION_KEYWORDS で上書きしてるから使ってないな。実質的に使ってるのは catalogResolver 関連のインフラだけ。」

千石: 「それは設計として破綻しています。生成されたコードの9割が使われていない。Ext がベースクラスの意図しない内部構造に依存している。生成側を修正したら Ext が壊れる。」

  → **Gap 発見: 現在の extends は「継承」ではなく「コード流用」。生成クラスと手書きクラスの契約 (contract) が存在しない。**

リヴァイ: 「2684行の Ext クラスを見た。生成クラスは477行。Ext のほうが5倍以上デカい。これは base が提供すべき機能を提供していないということだ。汚い。」

  → **Gap 発見: 生成クラスが提供する機能 (keyword completion, basic hover, empty semantic tokens) と Ext が必要とする機能のギャップが巨大。GGP 化の前に、生成クラスが「何を提供すべきか」の設計が必要。**

---

## Scene 2: EvaluatorGenerator の GGP との比較

先輩 (ナレーション): EvaluatorGenerator は `abstract class XxxEvaluator<T>` を生成する。@mapping ルールごとに `evalXxx()` メソッドが生成される。@eval アノテーションの strategy が 'manual' か未指定なら abstract、strategy が 'binary_arithmetic' 等なら concrete。evalInternal() が sealed switch で dispatch する。サブクラスは abstract メソッドだけ実装すればよい。

千石: 「EvaluatorGenerator の GGP はうまくいっている。同じパターンを LSP に適用すべきです。Evaluator の場合は `abstract evalXxx()` がフックポイント。LSP の場合は何がフックポイントになりますか？」

ヤン: 「Evaluator と LSP は構造が違う。Evaluator は AST ノード型ごとに1メソッド、入力と出力が明確。LSP は LSP プロトコルの各リクエストに対応するメソッドがあって、それぞれの中で文法固有のロジックが必要になる。」

今泉: 「他にないんですか？ Evaluator 以外に参考になるパターンは？」

ヤン: 「Template Method パターンそのものだね。生成クラスが骨格 (skeleton) を提供し、サブクラスが特定のステップを実装する。Evaluator の `evalXxx()` は Template Method の abstract step そのもの。LSP でも同じ構造にできる。」

千石: 「ただし、Evaluator は @mapping ルールから abstract メソッドを自動生成できる。LSP のフックポイントは文法から自動生成できますか？」

ヤン: 「一部はできる。@mapping があれば AST ノード型が分かるから、`additionalCompletion(String prefix, DocumentState state)` のようなフックは生成できる。でも `codeAction` や `signatureHelp` のようなものは文法から自動導出できない。これは汎用フックとして空実装を生成して、サブクラスで override する形になる。」

  → **Gap 発見: LSP のフックポイントには2種類ある: (1) 文法から自動導出できるもの (completion candidates, hover text) と (2) 汎用的に提供するもの (codeAction, signatureHelp)。この区別が設計上重要。**

リヴァイ: 「Evaluator の GGP を見ろ。`abstract` と `concrete` の判定は @eval アノテーションでやっている。LSP も同じようにアノテーションで制御するのか？」

ヤン: 「いや、LSP は違うと思う。LSP の場合、生成クラスが提供する機能は全文法で共通 (parse, keyword completion, basic diagnostics)。拡張ポイントも全文法で共通 (additional completion, custom hover, custom diagnostics)。@eval のような per-rule アノテーションは不要。」

  → **Gap 発見: EvaluatorGenerator は per-rule granularity で abstract/concrete を制御するが、LSPGenerator のフックは per-protocol-method granularity。粒度が異なる。EvaluatorGenerator のパターンをそのままコピーすると設計を誤る。**

---

## Scene 3: 拡張ポイントの設計

先輩 (ナレーション): TinyExpressionP4LanguageServerExt の ExtTextDocumentService が実装している機能を整理する。Ext が生成クラスにないものとして追加している機能は: codeAction (if/ternary 変換、bracket insertion)、definition (ScopeStore backref → declaration)、references (ScopeStore declaration → references)、linkedEditingRange (variable rename preview)、documentSymbol (variable declarations, imports)、rename (全 $variable 一括)、documentHighlight (同名 variable ハイライト)、signatureHelp (関数の引数ヒント)、codeLens (variable count, complexity metric)、inlayHint (型推論結果の表示)、foldingRange (if/match ブロック)、callHierarchy (function calls)、formatting (式の整形)。

リヴァイ: 「具体的にどこにフックを入れるか。全部列挙しろ。」

ヤン: 「最低限のフックセット:」

```
1. additionalCompletionItems(CompletionParams, DocumentState) → List<CompletionItem>
   - 生成クラスが keyword 補完を出した後、追加の補完候補を混ぜる
   - Ext: variable 補完、catalog 補完、snippet 補完

2. customHover(HoverParams, DocumentState) → Hover
   - 生成クラスの basic hover (Valid/Parse error) を置き換えるか追加する
   - Ext: AST node type 表示、variable 型表示、catalog description

3. additionalDiagnostics(String uri, String content, ParseResult) → List<Diagnostic>
   - parse diagnostics に追加する semantic diagnostics
   - Ext: ScopeStore diagnostics、ErrorCatalog 診断、FormulaInfo validation

4. customSemanticTokens(String content, int lineOffset) → List<Integer>
   - 生成クラスの empty token list を置き換える
   - Ext: 8種の token type による分類

5. codeActions(CodeActionParams, DocumentState) → List<CodeAction>
   - 生成クラスでは未提供。サブクラスで追加
   - Ext: if↔ternary 変換、bracket insertion

6. definitions(DefinitionParams, DocumentState) → List<Location>
7. references(ReferenceParams, DocumentState) → List<Location>
8. documentSymbols(DocumentSymbolParams, DocumentState) → List<DocumentSymbol>
9. signatureHelp(SignatureHelpParams, DocumentState) → SignatureHelp
10. codeLens(CodeLensParams, DocumentState) → List<CodeLens>
11. inlayHints(InlayHintParams, DocumentState) → List<InlayHint>
12. foldingRanges(FoldingRangeRequestParams, DocumentState) → List<FoldingRange>
13. formatting(DocumentFormattingParams, DocumentState) → List<TextEdit>
```

千石: 「13個のフックは多すぎませんか？ サブクラスの実装者が何を実装すべきか迷います。」

ヤン: 「全部 default 空実装にすればいい。override したいものだけ override する。abstract にするのは危険で、LSP を動かすだけなら何も override しなくても keyword 補完と diagnostics が出るべき。」

  → **Gap 発見: フックメソッドは全て protected + default 空実装にすべきか、一部を abstract にすべきか。EvaluatorGenerator は abstract を使うが、LSP は「何も override しなくても最低限動く」が要件。設計方針が異なる。**

今泉: 「そもそも、additionalCompletionItems と customHover は意味が違いますよね？ additional は『追加する』、custom は『置き換える』。この naming convention は統一すべきじゃないですか？」

千石: 「正しい指摘です。パターンを整理しましょう:」

```
パターン A: additional — 生成クラスの結果に追加する
  additionalCompletionItems()
  additionalDiagnostics()

パターン B: custom — 生成クラスの結果を完全に置き換える
  customHover()
  customSemanticTokens()

パターン C: provide — 生成クラスでは未提供、サブクラスが提供
  provideCodeActions()
  provideDefinitions()
  provideReferences()
  ...
```

  → **Gap 発見: フックの3パターン (additional / custom / provide) を明確に区別する naming convention が必要。混在するとサブクラス実装者が「生成コードの結果がどう使われるか」を誤解する。**

リヴァイ: 「フックの粒度の問題がある。completion のフックが1つだと、variable 補完と snippet 補完と catalog 補完を1メソッドに詰め込むことになる。」

ヤン: 「でも細かくしすぎると、フックが30個になる。今のところ additionalCompletionItems() 1つで十分。サブクラスの中で分離すればいい。」

  → **Gap 発見: フック粒度のトレードオフ — 細かすぎると生成コードが肥大化、粗すぎるとサブクラスが肥大化。最初は粗い粒度で始めて、必要に応じて分割する方針が妥当か？**

今泉: 「前もそうだったっけ？ EvaluatorGenerator は最初から per-rule で細かい粒度にしてますよね？」

ヤン: 「Evaluator は自然にそうなる。AST ノード型の数だけメソッドがある。LSP はプロトコルメソッドの数だけフックがある。LSP プロトコルのメソッド数は固定 (LSP spec で定義) だから、フック数も固定。増えない。」

  → **Gap 発見: LSP フックの数は LSP プロトコルのメソッド数に比例する。LSP 5.x で新しいリクエストが追加されたら、生成クラスも新しいフックを追加する必要がある。LSP バージョンへの追従メカニズムが未設計。**

---

## Scene 4: DAPGenerator も同様に

先輩 (ナレーション): DAPGenerator.java が存在し、TinyExpressionP4DebugAdapterExt が TinyExpressionP4DebugAdapter を extends している。DAP も LSP と同じ monolithic 生成パターンで、同じ GGP 化の問題を持っている。

ヤン: 「DAP も同じ問題があるよね。TinyExpressionP4DebugAdapterExt が手書きで launch(), setBreakpoints(), evaluate() 等を override している。」

今泉: 「そもそも LSP と DAP で共通の拡張パターンを作れませんか？ 両方ともプロトコルベースのサーバーで、両方とも生成 base + 手書き ext の構造ですよね？」

ヤン: 「共通化できるのは pattern の考え方だけで、コードは共通化できない。LSP は LanguageServer interface を実装し、DAP は DebugAdapter interface を実装する。共通の abstract base class は作れない。」

千石: 「パターンの統一は重要です。LSP と DAP で naming convention を揃えましょう:」

```
LSP: XxxLanguageServerBase.java (generated, abstract hooks)
     XxxLanguageServerExt.java  (hand-written, implements hooks)

DAP: XxxDebugAdapterBase.java  (generated, abstract hooks)
     XxxDebugAdapterExt.java   (hand-written, implements hooks)
```

  → **Gap 発見: 生成クラスの命名規則。現在は XxxLanguageServer (suffix なし) だが、GGP 化後は XxxLanguageServerBase にすべきか？ 既存コードとの互換性が壊れる。**

リヴァイ: 「命名を変えるなら一度に変えろ。後から変えるとリファクタが倍になる。」

ヤン: 「でも互換性を考えると、クラス名はそのままで `abstract class XxxLanguageServer` にする手もある。Ext のコードは `extends XxxLanguageServer` で変わらない。」

今泉: 「前もそうだったっけ？ EvaluatorGenerator は `XxxEvaluator` という名前で abstract class を生成してますよね。LanguageServer も `XxxLanguageServer` のまま abstract にすれば統一感がある。」

  → **Gap 発見: クラス名の方針 — EvaluatorGenerator に合わせて `XxxLanguageServer` のまま abstract class にする vs. `XxxLanguageServerBase` に改名する。前者は互換性維持、後者は意図の明示。**

千石: 「abstract class にするなら、`public class` → `public abstract class` の変更だけで十分です。サブクラスのコードは一切変わりません。これがユーザー (= 開発者) にとって最も親切です。」

  → **決定提案: クラス名は変えない。`public class XxxLanguageServer` → `public abstract class XxxLanguageServer` に変更するだけ。EvaluatorGenerator と同じパターン。**

---

## Scene 5: self-hosting への道

先輩 (ナレーション): unlaxer-parser の self-hosting とは、ubnf.ubnf ファイルを自分自身の UBNF パーサーでパースし、LSP を生成して UBNF 編集の IDE サポートを得ること。現在 UBNF の VSCode 拡張 (ubnf-vsix) はベタ書きの LSP を使っている。GGP 化された LSPGenerator があれば、ubnf.ubnf → UBNFLanguageServer.java (abstract, generated) → UBNFLanguageServerExt.java (hand-written, @mapping params 検証等) という流れが実現する。

今泉: 「これが実装されたら ubnf.ubnf → UBNF LSP が本当に可能になりますか？ 具体的にステップを教えてください。」

ヤン: 「ステップは3つ:」

```
Step 1: LSPGenerator を GGP 化 (abstract hooks 追加)
Step 2: ubnf.ubnf を LSPGenerator に通す → UBNFLanguageServer.java (abstract)
Step 3: UBNFLanguageServerExt.java を手書き — @mapping params 検証、@eval strategy 検証等
```

今泉: 「Step 2 は実際に動くんですか？ ubnf.ubnf の @mapping 構造は LSPGenerator が想定するパターンですか？」

ヤン: 「...これは確認が必要だな。ubnf.ubnf には @mapping アノテーションがあるけど、LSPGenerator が使うのは主に keyword 収集と @catalog チェック。@mapping の className は AST 生成のためのもので、LSP 生成には直接関係しない。だから Step 2 は動くと思う。ただし...」

千石: 「ただし何ですか？」

ヤン: 「生成される LSP は keyword 補完しかできない。UBNF に特化した機能 — @mapping params の検証、@eval strategy の候補表示、ルール名の go-to-definition — は全て Step 3 で手書きする必要がある。」

  → **Gap 発見: Step 2 (LSPGenerator に ubnf.ubnf を通す) は技術的に可能だが、生成される LSP の価値が低い (keyword 補完のみ)。Step 3 の手書き部分が UBNF LSP の価値の9割を占める。GGP 化の価値は「Step 3 を書きやすくすること」にある。**

リヴァイ: 「今の ubnf-vsix の LSP はどうなっている。」

ヤン: 「ベタ書き。生成コードを使っていない。GGP 化された LSPGenerator があれば、ベタ書き部分を Ext クラスに移行できる。共通部分 (parse, diagnostics, keyword completion) は生成に任せて、UBNF 固有部分だけ書けばいい。」

今泉: 「誰が困るんですか？ 今のベタ書き LSP で困っている人はいますか？」

ヤン: 「今のところは困ってない。でも LSP プロトコルの新機能 (semantic tokens, inlay hints 等) を追加するたびに、全 LSP を個別に修正している。GGP 化すれば、生成側に追加すれば全文法の LSP に反映される。」

  → **Gap 発見: GGP 化の ROI — 現時点では文法が tinyexpression と ubnf の2つだけ。GGP 化のコストに見合うのは、3つ目以降の文法 LSP が必要になったとき。ただし、FormulaInfo LSP の改善は tinyexpression の Ext に閉じるため、GGP 化が blocker ではない。**

千石: 「しかし、GGP 化しないと生成コードの改善が Ext に伝播しません。たとえば LSPGenerator に incremental parse を追加しても、Ext は parseDocument() を override しているので恩恵を受けられない。」

  → **Gap 発見: GGP 化しないリスク — 生成クラスの改善が手書き Ext クラスに伝播しない。Ext が生成コードと diverge し続ける。**

---

## Scene 6: MVP

先輩 (ナレーション): LSPGenerator の GGP 化を段階的に進めるとして、最小限の MVP は何か。

リヴァイ: 「今すぐ必要なのは何だ。全部やろうとするな。」

ヤン: 「MVP は3つの変更だけ:」

```
変更 1: LSPGenerator の出力を `public class` → `public abstract class` に変更
変更 2: 以下の protected hook メソッドを default 空実装で追加:
  - additionalCompletionItems() → List.of()
  - additionalDiagnostics() → List.of()
  - customSemanticTokens() → null (null なら生成デフォルトを使う)
  - customHover() → null (null なら生成デフォルトを使う)
変更 3: 生成コードの completion/diagnostics/hover/semanticTokens が hook を呼ぶように修正
```

千石: 「capabilities の登録もフック化すべきです。Ext は initialize() で capabilities を全部書き直しています。」

ヤン: 「あー、そうだね。`configureCapabilities(ServerCapabilities cap)` というフックを追加して、生成コードがデフォルト capabilities を設定した後にサブクラスが追加・変更できるようにする。」

```
変更 4: configureCapabilities(ServerCapabilities) hook を追加
  - 生成コードが基本 capabilities を設定
  - サブクラスが codeActionProvider, definitionProvider 等を追加
  - Ext は initialize() の override が不要になる
```

  → **Gap 発見: initialize() の override が不要になると、catalog resolver の初期化タイミングも変わる。Ext は super.initialize() を呼んで catalog resolver を初期化しているが、フック化後はこの呼び出しが暗黙的になる。初期化順序の保証が必要。**

リヴァイ: 「TextDocumentService の inner class はどうする。Ext は完全に別の inner class を作っている。」

ヤン: 「生成される TextDocumentService の各メソッドが server の hook を呼ぶようにすればいい。TextDocumentService.completion() が server.additionalCompletionItems() を呼ぶ。TextDocumentService 自体は生成のまま。Ext は TextDocumentService を override する必要がなくなる。」

今泉: 「でも Ext の ExtTextDocumentService は codeAction(), definition(), references() など、生成クラスの TextDocumentService にはないメソッドを実装してますよね？」

ヤン: 「そこが問題だ。生成クラスの TextDocumentService は completion, hover, semanticTokensFull しか実装していない。codeAction 等は capabilities として宣言しても TextDocumentService に実装がない。」

  → **Gap 発見: LSP プロトコルメソッドのうち、生成クラスの TextDocumentService に実装がないもの (codeAction, definition, references, documentSymbol, rename, documentHighlight, signatureHelp, codeLens, inlayHint, foldingRange, formatting, callHierarchy) を、フック経由で提供する仕組みが必要。TextDocumentService が hook 結果を返すだけの thin wrapper になる。**

千石: 「これは大きな変更です。MVP ですか？」

ヤン: 「MVP では capabilities を登録しなければ、TextDocumentService にメソッドがなくても問題ない。capabilities に codeActionProvider:true を設定しなければ、クライアントは codeAction リクエストを送らない。」

リヴァイ: 「つまり MVP はこうだ:」

```
MVP:
  1. abstract class 化
  2. 4 hooks (completion, diagnostics, hover, semanticTokens)
  3. configureCapabilities hook
  4. 生成 TextDocumentService が hooks を呼ぶ

Phase 2:
  5. provide 系フック (codeAction, definition, references...)
  6. 生成 TextDocumentService に全 LSP メソッドの thin wrapper を追加
  7. DAPGenerator にも同じパターン適用

Phase 3:
  8. ubnf.ubnf → UBNF LSP self-hosting
  9. ubnf-vsix を GGP ベースに移行
```

  → **Gap 発見: Phase 2 の「全 LSP メソッドの thin wrapper」を生成するには、LSPGenerator がどの LSP メソッドをサポートするか知っている必要がある。現在はハードコードされているが、将来 LSP 5.x で新メソッドが追加されたとき、LSPGenerator の更新が必要。**

今泉: 「誰が困るの？ この MVP を待っている人は？」

ヤン: 「2人。ubnf.ubnf の self-hosting をやりたい人と、FormulaInfo LSP を改善するときに生成コードの恩恵を受けたい人。どちらも作者自身だけど。」

千石: 「しかし、SLE 2026 論文で GGP を Code Generation の主要パターンとして報告するなら、LSP + DAP での適用は証拠として重要です。」

  → **Gap 発見: SLE 2026 の論文で GGP の汎用性を主張するなら、Evaluator だけでなく LSP/DAP での適用実績が必要。これは技術的要件ではなく論文の要件。**

---

## Gap List

| # | Category | Gap | Scene | Severity |
|---|----------|-----|-------|----------|
| G1 | Spec-impl mismatch | 生成 TextDocumentService が Ext で完全無視されている (dead code) | 1 | High |
| G2 | Integration gap | 生成クラスと Ext の間に contract (abstract methods / protected hooks) が存在しない | 1 | High |
| G3 | Spec-impl mismatch | 生成クラスの機能 (keyword completion, basic hover) と Ext が必要とする機能のギャップが巨大 | 1 | High |
| G4 | Missing logic | LSP フックの2種 (文法自動導出 vs 汎用提供) の区別が未設計 | 2 | Medium |
| G5 | Spec-impl mismatch | EvaluatorGenerator は per-rule 粒度、LSPGenerator は per-protocol-method 粒度。パターンの直接コピーは誤り | 2 | Medium |
| G6 | Missing logic | フックメソッドの abstract vs default 空実装の方針未決定 | 3 | High |
| G7 | Missing logic | フックの3パターン (additional / custom / provide) の naming convention 未設計 | 3 | Medium |
| G8 | Missing logic | フック粒度のトレードオフ — 最初の粒度決定基準がない | 3 | Low |
| G9 | Missing logic | LSP バージョン追従メカニズムが未設計 | 3 | Low |
| G10 | Missing logic | 生成クラスの命名方針 — `XxxLanguageServer` (abstract) vs `XxxLanguageServerBase` | 4 | Medium |
| G11 | Integration gap | GGP 化の ROI — 文法2つでは投資回収できない可能性 | 5 | Low |
| G12 | Integration gap | GGP 化しないリスク — 生成コードの改善が Ext に伝播しない | 5 | Medium |
| G13 | Missing logic | initialize() フック化後の catalog resolver 初期化順序の保証 | 6 | Medium |
| G14 | Missing logic | TextDocumentService の provide 系メソッド (codeAction 等) の thin wrapper 生成 | 6 | High |
| G15 | Test coverage | SLE 2026 論文での GGP 汎用性主張には LSP/DAP での適用実績が必要 | 6 | Medium |

---

## Decision Record

| ID | Decision | Rationale | Scene |
|----|----------|-----------|-------|
| D1 | クラス名は変えない: `public class` → `public abstract class` | EvaluatorGenerator と統一。既存 Ext コードの変更不要。 | 4 |
| D2 | フックは全て protected + default 空実装 (abstract にしない) | 「何も override しなくても最低限動く LSP」が要件。Evaluator とは設計方針が異なる。 | 3 |
| D3 | フック naming: additional (追加) / custom (置換、null なら default) / provide (新規提供) | サブクラス実装者が生成コードとの関係を誤解しない。 | 3 |
| D4 | MVP は4フック + configureCapabilities のみ | completion, diagnostics, hover, semanticTokens が Ext の最重要 override 対象。 | 6 |
| D5 | DAPGenerator は Phase 2 で LSP と同パターンを適用 | LSP で実績を作ってから DAP に展開。 | 4 |

---

## Action Items

| # | Action | Gap | Priority | Effort |
|---|--------|-----|----------|--------|
| A1 | LSPGenerator の出力を `abstract class` に変更 | G2 | P0 | S |
| A2 | 4 hook メソッドを生成コードに追加: `additionalCompletionItems()`, `additionalDiagnostics()`, `customHover()`, `customSemanticTokens()` | G3, G6 | P0 | M |
| A3 | `configureCapabilities(ServerCapabilities)` hook を追加 | G13 | P0 | S |
| A4 | 生成 TextDocumentService が hooks を呼ぶように修正 | G1 | P0 | M |
| A5 | TinyExpressionP4LanguageServerExt を hook ベースにリファクタ (initialize, completion, hover, diagnostics, semanticTokens の override を hook 実装に移行) | G2 | P1 | L |
| A6 | provide 系フック追加 (codeAction, definition, references 等) — Phase 2 | G14 | P1 | L |
| A7 | DAPGenerator に同パターン適用 — Phase 2 | G10 | P2 | M |
| A8 | ubnf.ubnf → UBNFLanguageServer 生成テスト — Phase 3 | G11 | P2 | M |
| A9 | SLE 2026 論文に LSP GGP 適用セクション追加 | G15 | P2 | S |
