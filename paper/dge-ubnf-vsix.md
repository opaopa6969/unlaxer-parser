# DGE Session: UBNF 用 VSCode 拡張 (VSIX) の設計

## テーマ
UBNF 文法記述言語 (.ubnf ファイル) の IDE サポートを VSCode 拡張として提供する設計のギャップを発見する。self-hosting（ubnf.ubnf から LSPGenerator で自動生成）の可能性と限界、文法固有バリデーション、Railroad Diagram 統合を含む。

## キャラクター
- ☕ ヤン・ウェンリー — 「要らなくない？」「最もシンプルな解は？」
- 🎩 千石武 — 「品質基準を示す」「ユーザーのために」
- ⚔ リヴァイ兵長 — 「汚い。動くもの見せろ。」
- 👤 今泉慶太 — 「そもそも」「誰が困るの」

## 前提コンテキスト
- unlaxer-parser は UBNF (Unlaxer BNF) 文法記法を持つ。拡張子は `.ubnf`
- 既存資産: UBNFParsers (bootstrap parser), UBNFAST (AST), UBNFMapper
- 既存資産: LSPGenerator, DAPGenerator — UBNF で定義されたターゲット言語の LSP/DAP を自動生成
- 未実装: UBNF 自体の LSP/DAP — 文法作者に IDE サポートがない
- tinyexpression-p4.ubnf は 350+ 行 — 大規模文法の編集にツーリングが必要
- self-hosting 文法: `unlaxer-dsl/grammar/ubnf.ubnf` が存在（169 行、UBNF 自身を UBNF で記述）
- RailroadMain.java が SVG 生成を既に実装済み
- 競合: ANTLR (ANTLRWorks), Xtext (grammar editor with cross-reference, validation, completion)

---

## Scene 1: Who uses UBNF and what do they need?

先輩: 「UBNF ファイルを編集するユーザーは現在3種類いる。(1) DSL 設計者（プログラマ）が新しい言語の文法を書く。(2) 既存文法を拡張・修正する開発者。(3) 将来的には LLM がプロンプトテンプレートから文法を生成する可能性がある。tinyexpression-p4.ubnf は 350 行を超えており、ルール間の参照関係を目視で追うのは現実的でなくなっている。」

今泉: 「そもそも UBNF を書く人は誰ですか？ プログラマーなんですか、それとも DSL のエンドユーザーですか？」

ヤン: 「プログラマーだよ。UBNF を書く人は、その UBNF で定義される DSL の設計者。エンドユーザーが UBNF を直接触ることはない。ただ、LLM が文法を生成するケースが出てきている。Claude に『こういう文法を UBNF で書いて』と頼む場面だね。」

今泉: 「LLM が書くなら、IDE サポートは要らないんじゃないですか？ LLM は補完も定義ジャンプも使わないですよね。」

ヤン: 「LLM が生成した文法を人間がレビューするときに要る。350 行の .ubnf を LLM に生成させて、ルール参照が正しいか、@mapping の params が実際のキャプチャと一致してるか、目視で確認するのは無理がある。」

  → **Gap 発見: LLM 生成文法のレビューワークフローが未定義。LSP のバリデーション結果を LLM にフィードバックして自動修正させるループの設計が必要か？**

千石: 「350 行の文法で、ルール参照を間違えたらどうなるんですか？ `@mapping(ExpressionNode, params=[left, op, right])` と書いて、実際のキャプチャ名が `@lhs` だった場合は？」

ヤン: 「UBNFMapper がランタイムで例外を投げる。コンパイル時じゃなくて実行時に初めて分かる。」

千石: 「それはユーザーへの侮辱です。文法を書いている時点で検出すべきです。」

  → **Gap 発見: @mapping params とキャプチャ名の不一致がランタイムまで検出されない。エディタ時点で静的に検証すべき最優先の診断対象。**

今泉: 「誰が困るんですか？ 具体的に。」

リヴァイ: 「俺が困る。文法を書いて、mvn test して、30 秒待って、`NoSuchCaptureException` が出て、文法に戻って、探して、直して、もう一度 mvn test。このサイクルを 10 回繰り返すのは汚い。」

  → **Gap 発見: フィードバックループの遅延。mvn test 30 秒 vs LSP 即時。開発体験の定量的な差が明確。**

今泉: 「他にないんですか？ UBNF を書くときに困ること。」

ヤン: 「ルールの数が増えると、どのルールがどこから参照されているか分からなくなる。未使用ルールが残る。あと、@interleave や @precedence の設定が意図通りか、実際にパースしてみないと分からない。」

  → **Gap 発見: ターゲットユーザーのスキルレベルの幅。UBNF のアノテーション体系（@mapping, @leftAssoc, @precedence, @eval, @root, @interleave, @whitespace, @backref, @scopeTree, @declares, @typeof, @catalog）を全て理解している人と、初めて触る人の差。ドキュメンテーションと補完ヒントの粒度が未定義。**

---

## Scene 2: Self-hosting — UBNF の UBNF で LSP 生成？

先輩: 「unlaxer-dsl/grammar/ubnf.ubnf に UBNF 自身の文法定義が 169 行で存在する。これは self-hosting 文法で、UBNF の構文を UBNF で記述している。LSPGenerator は GrammarDecl（UBNFAST）を入力として LanguageServer の Java コードを生成する。つまり理論上、ubnf.ubnf を LSPGenerator に通せば UBNF 用の LSP が自動生成されるはずだ。」

千石: 「UBNF の LSP を UBNF から生成できないんですか？ フレームワークが自分自身の IDE サポートを生成する — これは美しい self-hosting です。」

ヤン: 「やろうと思えばできるはず。ubnf.ubnf を UBNFParsers でパースして UBNFAST を得て、LSPGenerator に渡す。出力は UBNFLanguageServer.java。キーワード補完（`grammar`, `token`, `::=`, `@root`, `@mapping` など）と基本的な semantic tokens は自動で得られる。」

今泉: 「要するに、それだけで十分なんですか？」

ヤン: 「十分じゃない。LSPGenerator が生成するのは、文法のキーワード補完、括弧マッチング、基本的なエラー検出まで。でも UBNF 固有の意味的検証 — @mapping params がキャプチャ名と一致するか、ルール参照先が存在するか、循環参照の検出 — これは文法構造だけからは生成できない。」

リヴァイ: 「生成される LSP の機能を具体的に列挙しろ。」

ヤン: 「LSPGenerator が ubnf.ubnf から自動生成できるもの:
1. キーワード補完: `grammar`, `token`, `::=`, `;`, `@root`, `@mapping`, `@whitespace`, `@interleave`, `@leftAssoc`, `@rightAssoc`, `@precedence`
2. Semantic tokens: キーワード, 文字列リテラル, 識別子, アノテーション
3. 括弧マッチング: `(` `)`, `[` `]`, `{` `}`
4. 基本的なパースエラー検出

自動生成できないもの:
1. ルール名の cross-reference（定義 → 参照、参照 → 定義）
2. @mapping params のキャプチャ名バリデーション
3. 未使用ルールの検出
4. 重複ルール名の検出
5. token 宣言の parserClass 存在確認
6. Railroad Diagram 生成・プレビュー」

  → **Gap 発見: LSPGenerator の自動生成レイヤーと、UBNF 固有のセマンティックレイヤーの境界が不明確。2層アーキテクチャ（auto-generated base + hand-written semantic layer）が必要だが、その拡張ポイントの API が未設計。**

千石: 「auto-generated base に手書きの semantic layer を追加する仕組みはあるんですか？」

ヤン: 「今はない。LSPGenerator は完成した Java ファイルを一枚出力する。部分的にオーバーライドする仕組みがない。」

  → **Gap 発見: LSPGenerator の出力が monolithic。拡張ポイント（hook / callback / override）がなく、手書きコードとの結合方法が未設計。extends + override パターン、もしくは plugin/listener パターンが必要。**

今泉: 「前もそうだったっけ？ tinyexpression の LSP を作ったとき、同じ問題にぶつかりませんでしたか？」

ヤン: 「tinyexpression は式言語だから、文法固有のセマンティクスが少なかった。変数名の補完くらい。UBNF はメタ言語だから、セマンティクスの量が桁違い。」

  → **Gap 発見: self-hosting LSP が「フレームワークの dogfooding」になる。ここで発見した LSPGenerator の拡張性の問題は、全てのターゲット言語の LSP にも適用される汎用的な課題。**

---

## Scene 3: Grammar-specific validations

先輩: 「UBNF には豊富なアノテーション体系がある。@mapping, @leftAssoc, @rightAssoc, @precedence, @eval, @root, @interleave, @whitespace, @backref, @scopeTree, @declares, @typeof, @catalog。これらは単なる構文糖ではなく、コード生成と実行時の振る舞いに直結する。アノテーションの誤用は、コンパイル時ではなく実行時に表面化する。」

リヴァイ: 「文法固有のバリデーションは何が必要だ。全部列挙しろ。」

千石: 「優先度順に:」

**1. @mapping params チェック**
千石: 「`@mapping(ExprNode, params=[left, op, right])` と書いたら、ルール本体に `@left`, `@op`, `@right` のキャプチャが存在しなければならない。逆に、キャプチャがあるのに params に含まれていないケースも警告すべきです。」

  → **Gap 発見: @mapping params とキャプチャ名の双方向整合性チェック。params にあるがキャプチャにない（エラー）、キャプチャにあるが params にない（警告）の 2 パターン。**

**2. @leftAssoc / @rightAssoc の構造チェック**
リヴァイ: 「@leftAssoc が付いたルールは left/op/right パターンを持つべきだ。具体的には、ルール本体が `Self @left Op @op Self @right` の形か、少なくとも自己参照を持つ ChoiceBody か。」

今泉: 「そもそも @leftAssoc のルールの『正しい形』って定義されてるんですか？」

  → **Gap 発見: @leftAssoc / @rightAssoc が要求するルール本体の構造パターンが形式的に定義されていない。バリデーションルールを書くには形式定義が必要。**

**3. ルール参照の存在チェック**
千石: 「RuleRefElement の name が、同じ grammar 内の RuleDecl または TokenDecl の name と一致しなければエラー。これは go-to-definition の前提条件でもあります。」

**4. 重複ルール名**
リヴァイ: 「同じ名前の RuleDecl が 2 つあったら即エラー。」

**5. 未使用ルール**
ヤン: 「@root から到達可能でないルールは warning。ただし、token 宣言は参照されなくても有効かもしれない。」

  → **Gap 発見: 未使用ルールの定義が曖昧。@root からの到達可能性分析のアルゴリズムと、token 宣言の扱いが未定義。**

**6. @eval kind の値チェック**
千石: 「@eval(kind=...) の有効な kind 値は何ですか？」

ヤン: 「...正直、@eval のバリデーションは tinyexpression 固有かもしれない。UBNF 汎用の VSIX で対応すべきかは微妙。」

  → **Gap 発見: UBNF 汎用のバリデーションと、特定文法（tinyexpression）固有のバリデーションの境界が不明。VSIX はどこまでを担うのか。**

**7. 循環参照の検出**
リヴァイ: 「左再帰になるパターン。`A ::= A B` は UBNF のパーサーが処理できるのか。」

ヤン: 「unlaxer-parser は PEG ベースだから、直接左再帰は無限ループになる。ただし @leftAssoc アノテーションで処理する設計がある。未アノテーションの左再帰は確実にエラーにすべき。」

  → **Gap 発見: 左再帰の検出アルゴリズム。直接左再帰（`A ::= A ...`）は簡単だが、間接左再帰（`A ::= B ...`, `B ::= A ...`）の検出には固定点計算が必要。**

**8. token 宣言の parserClass 存在確認**
今泉: 「`token IDENTIFIER = IdentifierParser` の `IdentifierParser` が実際に Java クラスとして存在するか確認できるんですか？」

ヤン: 「クラスパスの問題になる。LSP プロセスがプロジェクトのクラスパスを知っている必要がある。これは重い。」

  → **Gap 発見: token の parserClass 存在確認はクラスパス解決が必要。LSP プロセスにプロジェクトのクラスパスを渡す設定メカニズムが未設計。もしくは、よく使われる組み込み parserClass（IdentifierParser, NumberParser, SingleQuotedParser 等）のホワイトリストで簡易チェックするか。**

---

## Scene 4: Railroad Diagram Integration

先輩: 「RailroadMain.java は UBNFAST の GrammarDecl から SVG 形式の Railroad Diagram を生成する機能を既に持っている。これを VSCode のサイドパネルに統合すれば、文法のビジュアル表現がリアルタイムで得られる。」

ヤン: 「RailroadMain で SVG 生成できるなら、VSIX にプレビューつけられるね。コマンドパレットから 'Show Railroad Diagram' で横にパネルが出る。」

リヴァイ: 「RailroadMain の実行方法は。in-process か subprocess か。」

ヤン: 「LSP サーバーは Java プロセスだから、RailroadMain を同じプロセスで呼べる。SVG 文字列を生成して、LSP のカスタムコマンドで VSCode に返す。VSCode 側は WebView パネルに SVG を表示する。」

千石: 「文法変更時にリアルタイムで更新してほしいです。保存のたびに再生成では遅い。タイピング中にデバウンスして更新すべきです。」

ヤン: 「デバウンス 500ms くらいで textDocument/didChange をフックして再生成か。でも 350 行の文法の Railroad を毎回全部生成するのはコストが高い。」

  → **Gap 発見: Railroad Diagram の増分更新戦略が未定義。全体再生成 vs 変更されたルールのみ再生成。ルール単位で表示するか、文法全体を一枚の図にするかの UX 判断も未決定。**

今泉: 「そもそも Railroad Diagram は誰が見るんですか？ 文法を書いてる人ですか、それとも文法のドキュメントを読む人ですか？」

ヤン: 「両方。書いてる人はルールの構造を視覚的に確認したい。ドキュメント用途なら、SVG をエクスポートする機能も要る。」

  → **Gap 発見: Railroad Diagram の用途が 2 つ（開発中の視覚確認 vs ドキュメント出力）あり、それぞれ要件が異なる。開発中はルール単位でインタラクティブ、ドキュメントは文法全体で静的 SVG。**

リヴァイ: 「RailroadMain の入力は何だ。.ubnf ファイルパスか、UBNFAST オブジェクトか。」

ヤン: 「UBNFAST の GrammarDecl だね。つまり .ubnf をパースして AST を構築してから渡す。LSP がパース済みの AST を持っているなら、それをそのまま渡せる。」

千石: 「Railroad Diagram 上のルールをクリックしたら、エディタで該当ルールにジャンプできるべきです。逆に、エディタでカーソルがあるルールが Railroad Diagram 上でハイライトされるべきです。」

  → **Gap 発見: Railroad Diagram とエディタの双方向連携（クリック→ジャンプ、カーソル→ハイライト）の API 設計が未定義。SVG 内の要素に data 属性でルール位置情報を埋め込む必要がある。**

---

## Scene 5: Project Structure

先輩: 「UBNF VSIX は、tinyexpression 固有ではなく、UBNF 文法記法の汎用エディタである。プロジェクト構造をどうするか — tinyexpression の VSIX とは別プロジェクトとすべきか、統合すべきか。」

今泉: 「これは tinyexpression の VSIX とは別プロジェクトですよね？」

ヤン: 「当然別。UBNF VSIX は `.ubnf` ファイル全般を扱う。tinyexpression VSIX は `.te` ファイルを扱う。activationEvents も languageId も違う。」

今泉: 「どこに置くんですか？」

ヤン: 「`unlaxer-parser/tools/ubnf-lsp-vscode/` が自然。unlaxer-parser のサブモジュールとして。」

リヴァイ: 「ディレクトリ構造を見せろ。」

ヤン: 「こんな感じ:
```
unlaxer-parser/tools/ubnf-lsp-vscode/
  package.json          # VSIX manifest
  src/
    extension.ts        # activate/deactivate, LSP client 起動
    railroadPanel.ts    # WebView panel for Railroad Diagram
  syntaxes/
    ubnf.tmLanguage.json  # TextMate grammar for syntax highlighting
  language-configuration.json
  server/               # LSP server (Java, launched as subprocess)
```」

千石: 「LSP サーバーが Java で、VSIX クライアントが TypeScript。サーバーの起動方法は？ JAR を同梱するのか、`mvn exec:java` で起動するのか。」

  → **Gap 発見: LSP サーバーの配布形態が未定義。Fat JAR 同梱 vs ユーザー環境の JDK + mvn に依存。VSIX のサイズ制限（<20MB 推奨）と Java ランタイム依存の問題。**

今泉: 「既存の tinyexpression VSIX との関係はどうなるんですか？ 共存するんですか？」

ヤン: 「共存する。.ubnf を開いたら UBNF VSIX が発動、.te を開いたら tinyexpression VSIX が発動。activationEvents が異なるから衝突しない。ただ、LSP サーバーの共有は可能性がある — 同じ Java プロセスで両方の言語をサポートできる。」

  → **Gap 発見: 複数言語の LSP サーバーを 1 プロセスにまとめるか、言語ごとに別プロセスにするかのアーキテクチャ判断が未決定。1 プロセスならメモリ効率が良いが、クラッシュ時に全滅する。**

---

## Scene 6: MVP — 最小限で最大の効果

先輩: 「限られたリソースで最大の効果を出すために、MVP のスコープを決める必要がある。」

リヴァイ: 「最小限で最大の効果を出せ。何を最初に作る。」

ヤン: 「紅茶を飲みながら考えるけど...MVP は 3 つだけでいい:
1. **TextMate grammar による syntax highlighting** — これは TypeScript だけで完結する。LSP サーバー不要。半日で作れる
2. **ルール名の go-to-definition** — LSP サーバーが必要だが、UBNFAST からルール名→行番号のマップを作るだけ
3. **@mapping params とキャプチャ名の不一致エラー** — 最も高い ROI。ランタイムエラーを排除する」

千石: 「syntax highlighting なしに他の機能を出すのは順番が間違っています。まず syntax highlighting、それから LSP 機能の順です。」

リヴァイ: 「同意する。Phase 分けしろ。」

ヤン: 「
- **Phase 1 (1-2日)**: TextMate syntax highlighting + language-configuration (括弧マッチング, コメントトグル, auto-closing)
- **Phase 2 (3-5日)**: LSP サーバー基盤 + go-to-definition + diagnostics (@mapping params チェック, 未定義ルール参照, 重複ルール名)
- **Phase 3 (2-3日)**: completion (ルール名, アノテーション名) + hover (ルール本体表示)
- **Phase 4 (2-3日)**: Railroad Diagram preview (WebView panel)
- **Phase 5 (後日)**: code actions (extract rule, inline rule), semantic tokens, 左再帰検出」

今泉: 「Phase 1 だけで、ANTLR の拡張に対する最低限の対等にはなりますか？」

ヤン: 「syntax highlighting だけでは ANTLRWorks には程遠い。でも Phase 2 まで行けば、ANTLR の VSCode 拡張（mike-lischke/vscode-antlr4）と同等レベル。あの拡張も syntax highlighting + go-to-definition + diagnostics が中心だから。」

  → **Gap 発見: 競合比較の定量的な機能マトリクスがない。vscode-antlr4 と Xtext の grammar editor の機能を具体的に列挙して、UBNF VSIX の各 Phase でどこまでカバーするかの対応表が必要。**

千石: 「self-hosting の話に戻りますが、Phase 2 の LSP サーバーを手書きするか、ubnf.ubnf + LSPGenerator で生成するか、どちらですか？」

ヤン: 「正直、MVP は手書きの方が速い。self-hosting は LSPGenerator の拡張性を証明する技術的チャレンジとしてやる価値はあるけど、MVP のブロッカーにすべきじゃない。」

リヴァイ: 「手書きで動くものを先に作れ。self-hosting は後からリファクタリングしろ。」

  → **Gap 発見: MVP の実装方針（手書き vs self-hosting 生成）が未決定。手書きを先行させて後から self-hosting に置き換えるパスの実現可能性が未検証。**

今泉: 「そもそも、TextMate grammar の ubnf.tmLanguage.json は手書きですよね？ これも UBNF から生成できませんか？」

ヤン: 「面白い発想だけど、TextMate grammar は正規表現ベースで、UBNF は CFG ベース。変換は理論的に不完全。手書きの方が確実。」

  → **Gap 発見: TextMate grammar (正規表現) と UBNF (CFG) の表現力のギャップ。syntax highlighting のための TextMate grammar は手書きが必要であり、self-hosting の範囲外。**

---

## Gap 一覧

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| G-1 | LLM 生成文法のレビューワークフローが未定義。LSP バリデーション → LLM フィードバックループ | Integration gap | Medium |
| G-2 | @mapping params とキャプチャ名の不一致がランタイムまで検出されない | Spec-impl mismatch | **Critical** |
| G-3 | フィードバックループの遅延（mvn test 30秒 vs LSP 即時）の定量比較 | Error quality | High |
| G-4 | ターゲットユーザーのスキルレベルの幅。アノテーション体系の理解度差 | Message gap | Medium |
| G-5 | LSPGenerator の自動生成レイヤーと UBNF 固有セマンティックレイヤーの境界が不明確 | Missing logic | **Critical** |
| G-6 | LSPGenerator の出力が monolithic。拡張ポイント（hook/override）がない | Missing logic | High |
| G-7 | self-hosting LSP が LSPGenerator の dogfooding になる（汎用的課題の発見機会） | Integration gap | Medium |
| G-8 | @mapping params とキャプチャ名の双方向整合性チェック（2パターン） | Missing logic | High |
| G-9 | @leftAssoc / @rightAssoc が要求するルール構造パターンの形式定義がない | Spec-impl mismatch | Medium |
| G-10 | 未使用ルールの定義が曖昧。@root 到達可能性分析と token 宣言の扱い | Missing logic | Medium |
| G-11 | UBNF 汎用バリデーションと特定文法固有バリデーションの境界が不明 | Missing logic | Medium |
| G-12 | 左再帰の検出アルゴリズム（直接 + 間接）の設計が未着手 | Missing logic | Medium |
| G-13 | token parserClass の存在確認にクラスパス解決が必要。設定メカニズム未設計 | Integration gap | Low |
| G-14 | Railroad Diagram の増分更新戦略とルール単位 vs 全体のUX判断が未定義 | Missing logic | Medium |
| G-15 | Railroad Diagram の用途が 2 つ（開発中 vs ドキュメント）あり要件が異なる | Spec-impl mismatch | Low |
| G-16 | Railroad Diagram とエディタの双方向連携 API が未設計 | Integration gap | Medium |
| G-17 | LSP サーバーの配布形態（Fat JAR vs JDK 依存）が未定義 | Ops gap | High |
| G-18 | 複数言語 LSP サーバーの 1 プロセス vs 別プロセスのアーキテクチャ判断 | Missing logic | Medium |
| G-19 | 競合（vscode-antlr4, Xtext）との機能マトリクス比較がない | Message gap | Medium |
| G-20 | MVP 実装方針（手書き vs self-hosting 生成）が未決定 | Missing logic | High |
| G-21 | TextMate grammar と UBNF の表現力ギャップ。syntax highlighting は self-hosting 範囲外 | Spec-impl mismatch | Low |

---

## Gap 詳細（Critical + High）

### G-2: @mapping params とキャプチャ名の不一致がランタイムまで検出されない
- **Observe**: `@mapping(Node, params=[left, op, right])` と書いて、実際のキャプチャが `@lhs, @operator, @rhs` の場合、UBNFMapper が実行時に例外を投げる
- **Suggest**: LSP の diagnostics で、ルール宣言の解析時にパラメータ名とキャプチャ名を突合し、不一致をエディタ上に赤波線で表示すべき
- **Act**: UBNFSemanticAnalyzer クラスを新設。validateMappingParams(RuleDecl) メソッドで @mapping の params リストとルール本体内の @captureName を収集・比較。不一致を Diagnostic として返す

### G-5: LSPGenerator の自動生成レイヤーと UBNF 固有セマンティックレイヤーの境界
- **Observe**: LSPGenerator は構文レベルの LSP コードを生成するが、意味レベルの検証（クロスリファレンス、型チェック、到達可能性）は生成できない
- **Suggest**: 2 層アーキテクチャを明示的に設計。Base layer (auto-generated) + Semantic layer (hand-written or plugin)
- **Act**: LSPGenerator の出力を abstract class にして、semantic validation メソッドを abstract / hook として定義。具体例: `protected List<Diagnostic> additionalDiagnostics(ParseResult ast)` を override ポイントとして提供

### G-6: LSPGenerator の出力が monolithic
- **Observe**: LSPGenerator.generate() は完成した Java ファイルを 1 つ出力する。手書きコードを追加する拡張ポイントがない
- **Suggest**: 生成コードを base class として出力し、ユーザーが extends で拡張できるパターンにする
- **Act**: `generate()` の出力を `{Name}LanguageServerBase.java` に変更。ユーザーは `{Name}LanguageServer extends {Name}LanguageServerBase` を手書きして、override ポイントで semantic 検証を追加

### G-3: フィードバックループの遅延
- **Observe**: 文法エラーが mvn test (30秒+) を経ないと判明しない
- **Suggest**: LSP の didChange/didSave イベントで即座に UBNF をパースし、diagnostics を返す
- **Act**: Phase 2 MVP の中核機能。textDocument/didChange → UBNFParsers.parse() → diagnostics publish のパイプラインを実装

### G-8: @mapping params とキャプチャ名の双方向整合性
- **Observe**: params にあるがキャプチャにない → エラー（実行時に必ず失敗）、キャプチャにあるが params にない → 警告（情報の欠落）
- **Suggest**: 2 種類の diagnostic severity で区別
- **Act**: G-2 の UBNFSemanticAnalyzer 内で実装。params → captures: DiagnosticSeverity.Error, captures → params: DiagnosticSeverity.Warning

### G-17: LSP サーバーの配布形態
- **Observe**: LSP サーバーは Java で実装されるが、VSIX に Fat JAR を同梱するとサイズが大きくなる。ユーザー環境に JDK が必要
- **Suggest**: (A) Fat JAR 同梱 + VSIX 内蔵 JRE、(B) ユーザーの JDK に依存 + settings で java.home 指定、(C) GraalVM native-image で OS 別バイナリ
- **Act**: MVP は (B) でスタート。`ubnf.java.home` 設定を package.json に追加。将来的に (C) に移行

### G-20: MVP 実装方針
- **Observe**: self-hosting (ubnf.ubnf → LSPGenerator) は美しいが、LSPGenerator の拡張性問題 (G-5, G-6) が先に解決される必要がある
- **Suggest**: Phase 1-3 は手書きで速く動くものを作り、Phase 5 以降で self-hosting にリファクタリング
- **Act**: 手書き LSP サーバーを `tools/ubnf-lsp-vscode/server/src/main/java/org/unlaxer/dsl/lsp/UBNFLanguageServer.java` に実装。将来的に LSPGenerator 生成の base class に置き換え

---

## Decision Record

| # | Decision | Rationale | Alternatives Rejected |
|---|----------|-----------|----------------------|
| D-1 | MVP は手書き LSP サーバーで開始 | self-hosting は LSPGenerator の拡張性問題 (G-5, G-6) を先に解決する必要があり、MVP のブロッカーにすべきでない | self-hosting 生成を先行 → LSPGenerator の拡張性が不足しており MVP が遅延するリスク |
| D-2 | TextMate grammar は手書き | TextMate (正規表現) と UBNF (CFG) の表現力ギャップにより自動生成は不完全 | UBNF → TextMate 変換器 → 理論的に不完全 |
| D-3 | プロジェクト配置は `unlaxer-parser/tools/ubnf-lsp-vscode/` | UBNF 汎用ツーリングであり tinyexpression 固有ではない。unlaxer-parser のサブモジュールが自然 | tinyexpression-group 内 → UBNF 汎用性に反する |
| D-4 | Phase 分けは 5 段階 | syntax highlighting → LSP 基盤 → completion/hover → Railroad → code actions の順で価値が漸増 | 全機能一括 → スコープが大きすぎてリリースが遅れる |
| D-5 | LSP サーバー配布は JDK 依存 (settings) でスタート | MVP の速度優先。Fat JAR はサイズ問題、native-image は設定が重い | Fat JAR 同梱 → VSIX サイズ超過 |
| D-6 | LSP サーバーは言語ごとに別プロセス | クラッシュ隔離。将来的に 1 プロセスに統合する余地は残す | 1 プロセス → クラッシュ全滅リスク |

---

## Action Items

| # | Action | Phase | Depends On | Assignee |
|---|--------|-------|------------|----------|
| A-1 | `ubnf.tmLanguage.json` 作成: keywords (`grammar`, `token`, `::=`), annotations (`@root`, `@mapping`, ...), strings, comments, identifiers, captures (`@name`) | Phase 1 | — | — |
| A-2 | `language-configuration.json` 作成: 括弧マッチング, コメントトグル (`//`), auto-closing (`(`, `[`, `{`, `'`) | Phase 1 | — | — |
| A-3 | `package.json` (VSIX manifest) 作成: activationEvents (`onLanguage:ubnf`), contributes (languages, grammars, configuration) | Phase 1 | — | — |
| A-4 | UBNFLanguageServer.java 手書き実装: textDocument/didOpen, didChange, didSave → UBNFParsers.parse() → diagnostics | Phase 2 | A-3 | — |
| A-5 | UBNFSemanticAnalyzer.java 実装: @mapping params vs キャプチャ名の双方向チェック (G-2, G-8) | Phase 2 | A-4 | — |
| A-6 | ルール参照の cross-reference 実装: go-to-definition, 未定義ルール参照エラー, 重複ルール名エラー | Phase 2 | A-4 | — |
| A-7 | Completion provider: ルール名補完, アノテーション名補完 (@mapping, @leftAssoc, @precedence, ...) | Phase 3 | A-4 | — |
| A-8 | Hover provider: ルール参照上でルール本体を表示 | Phase 3 | A-6 | — |
| A-9 | Railroad Diagram WebView panel: RailroadMain 呼び出し → SVG → WebView 表示, デバウンス更新 | Phase 4 | A-4 | — |
| A-10 | LSPGenerator を 2 層アーキテクチャに改修: `{Name}LanguageServerBase` + override ポイント (G-5, G-6) | Phase 5 | A-4 | — |
| A-11 | self-hosting 検証: ubnf.ubnf → 改修済み LSPGenerator → UBNFLanguageServerBase → 手書き layer との比較 | Phase 5 | A-10 | — |
| A-12 | 競合機能マトリクス作成: vscode-antlr4 / Xtext grammar editor vs UBNF VSIX の Phase 別カバレッジ (G-19) | Phase 1 | — | — |
