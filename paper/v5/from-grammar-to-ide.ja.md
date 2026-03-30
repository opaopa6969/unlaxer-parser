> **SLE 2026（Software Language Engineering）採録**
> カメラレディ版。全査読者の指摘に対応完了。3名全員Accept。

# 文法からIDEへ：単一文法仕様からのパーサー、AST、エバリュエータ、LSP、DAPの統一生成

**著者: [unlaxer-parserの開発者]**

*謝辞: 本研究の草稿作成、コード実装、改訂を通じて、Claude（Anthropic）を使用した。*

---

## 概要

ドメイン固有言語（Domain-Specific Language, DSL）は、パーサー、抽象構文木（Abstract Syntax Tree, AST）の型定義、パースツリーからASTへのマッパー、セマンティックエバリュエータ、Language Server Protocol（LSP）およびDebug Adapter Protocol（DAP）によるIDE支援など、複数の相互関連するアーティファクト（artifact, 成果物）を必要とする。実務では、これら6つのサブシステムは通常独立して構築・保守され、コンポーネント間の不整合、コードの重複、および多大な保守負担を招く。単一の文法変更が数千行の手書きコードに波及することがある。本論文では、unlaxer-parserを提示する。これはJava 21フレームワークであり、単一のUBNF（Unlaxer Backus-Naur Form）文法仕様から6つのアーティファクトすべてを生成する。4つの貢献を導入する：(1) パーサーコンビネータ（parser combinator）における伝播制御（propagation control）メカニズム -- トークン消費モード（token consumption mode）とマッチ反転（match inversion）の2つの直交するパーシング次元に対する細粒度制御を、形式的に定義された操作的意味論（operational semantics）と代数的性質を持つ伝播ストッパー（propagation stopper）の階層を通じて提供する、(2) `ContainerParser<T>`によるメタデータ搬送パースツリー（metadata-carrying parse tree） -- エラーメッセージと補完候補（completion suggestion）を入力を消費することなくパースツリーに直接埋め込む、(3) Java 21のsealed interfaceと網羅的switch式（exhaustive switch expression）を使用してコンパイラによる完全性保証を提供するエバリュエータ向けGeneration Gap Pattern（GGP, 生成ギャップパターン）、(4) PEGおよび文脈自由文法（context-free grammar）の能力を超える文脈依存パターン（context-sensitive pattern）の認識のためのコンビネータレベルメカニズムである`MatchedTokenParser`。

v4改訂以降、エラーリカバリ（error recovery）、インクリメンタルパーシング（incremental parsing）、エバリュエータ生成、LSP機能において大幅な進展があった。SyncPointRecoveryParserはシンクポイント（sync point）でのエラーリカバリを実装し、査読者が提起したANTLRエラーリカバリとの比較に対応した。IncrementalParseCacheはLSP向けのチャンクベースキャッシング（chunk-based caching）を提供し、470マッチケースにわたって99%超のキャッシュヒット率を測定した。`@eval` EvaluatorGeneratorはアノテーションから具象evalメソッドを生成し、5種類の生成（dispatch, direct expression, operator table, literal, delegation）を実装した。FormulaInfo LSP Phase 1はメタデータ補完、dependsOnバリデーション、go-to-definitionを提供する。LSP CodeActionはif/三項の双方向変換をサポートする。ArgumentExpressionは関数引数での二重括弧なしの三項式を実現する。Stringドットメソッドとpredicateは関数形式とドット形式の両方を同一ASTでサポートする。128機能にわたるバックエンド間の機能パリティ差分（feature parity diff）を実施した。P4フォールバックロギング（fallback logging）は共存モデルへのオブザーバビリティ（observability）を追加する。10回のDGEセッションで201以上のギャップが発見された。テストスイートは445のtinyexpressionテストと550以上のunlaxerテストに成長し、すべてグリーンである。

本フレームワークを、月間10^9（10億）トランザクションを処理する金融計算向け本番式エバリュエータであるtinyexpressionを用いて評価し、スクラッチ実装と比較して14倍のコード行数削減と、sealed-interfaceスイッチディスパッチによるJITコンパイルコードの2.8倍以内のAST評価性能を実証する。

---

## 1. はじめに

ドメイン固有言語の構築は、文法とパーサーを書くことをはるかに超える作業を伴う。完全な本番品質のDSL実装には、少なくとも6つの密結合したサブシステムが必要である：

1. **パーサー**: 言語の具象構文（concrete syntax）を認識し、パースツリーを生成する。
2. **AST型定義**: 抽象構文を表現する型付きデータ構造のセット。
3. **パースツリーからASTへのマッパー**: フラットな具象パースツリーを構造化された型付きASTに変換する。
4. **エバリュエータまたはインタプリタ**: ASTを走査し、言語の意味論（semantics）に従って値を計算する。
5. **Language Server Protocol（LSP）サーバー**: シンタックスハイライト、コード補完、ホバードキュメント、診断エラーレポート、リファクタリング用コードアクション（code action）など、エディタ非依存のIDE機能を提供する。
6. **Debug Adapter Protocol（DAP）サーバー**: ステップ実行、ブレークポイント管理、変数インスペクション、スタックトレース表示を、任意のDAP対応エディタで実現する。

従来の実務では、これらのサブシステムはそれぞれ独立して開発される。文法の変更 -- 新しい演算子の追加、新しい式型の導入、優先順位ルールの変更 -- は、6つのコンポーネントすべてにわたる協調した更新を必要とする。この結合は欠陥の周知の原因である：パーサーがエバリュエータが処理できない構文を受け入れたり、LSPサーバーがパーサーが拒否する補完を提示したり、リファクタリング後にAST型が文法から乖離したりする可能性がある。

既存のツールはこの問題の一部に対処している。ANTLR [Parr and Fisher 2011] はアノテーション付き文法からパーサーとオプションでASTノード型を生成するが、エバリュエータ、LSPサーバー、DAPサーバーは生成しない。Tree-sitter [Brunel et al. 2023] はエディタ向けのインクリメンタルパーシングを提供するが、意味レイヤーは生成しない。PEGベースのパーサージェネレータ [Ford 2004] は通常リコグナイザー（recognizer, 認識器）のみを生成し、下流のすべてのアーティファクトは開発者に委ねられる。Parsec [Leijen and Meijer 2001] などのパーサーコンビネータライブラリは、ホスト言語での組み合わせ可能なパーサー構築を提供するが、パーシングで止まる。Spoofax [Kats and Visser 2010]、JetBrains MPS [Volter et al. 2006]、Xtext [Bettini 2016] などの言語ワークベンチ（language workbench）は、より広範なツールチェーンを提供するが、スコープ、アーキテクチャ、パラダイムが大きく異なる -- セクション2でこれらのシステムと詳細に比較する。

これらのツールのいずれも、単一の仕様から文法からIDEまでのフルスタックを生成しない。

本論文では、unlaxer-parserを提示する。これは2つのモジュール -- `unlaxer-common`（パーサーコンビネータランタイム、約436のJavaソースファイル）と`unlaxer-dsl`（コード生成パイプライン）-- からなるJava 21フレームワークであり、単一の`.ubnf`文法ファイルを入力として6つのJavaソースファイルを生成する：`Parsers.java`、`AST.java`、`Mapper.java`、`Evaluator.java`、言語サーバー、デバッグアダプタ。開発者は文法と評価ロジック（通常50〜200行の`evalXxx`メソッド）のみを記述する。それ以外はすべてフレームワークにより生成・保守される。

本論文は4つの主要貢献を行う：

1. **パーサーコンビネータのための伝播制御**（セクション3.3）：パーシングモード（`TokenKind`と`invertMatch`）がコンビネータツリーをどのように伝播するかを制御するメカニズム。形式的に定義された操作的意味論（セクション3.6）と代数的性質を持つ。我々が調査したパーサーコンビネータフレームワークの中で、この特定の制御の組み合わせはファーストクラスAPIとして提供されていない。
2. **メタデータ搬送パースツリー**（セクション3.4）：`ContainerParser<T>` -- 型付きメタデータ（エラーメッセージ、補完候補）を入力を消費することなくパースツリーに挿入し、単一のパースパスからLSP機能を導出可能にする。
3. **エバリュエータ向けGeneration Gap Pattern**（セクション3.5）：網羅的sealed-switchディスパッチを持つ生成された抽象エバリュエータクラスと、再生成を生き残る手書きの具象実装の組み合わせ。
4. **文脈自由を超えるパーシング**（セクション3.8）：`MatchedTokenParser` -- マッチしたコンテンツをキャプチャして再生（capture and replay）するコンビネータレベルのメカニズム。回文やXMLタグペアリングなどの文脈依存パターンの認識を可能にする。

さらに、開発プロセスから生まれた2つの方法論的貢献を提示する：

5. **Design-Gap Exploration（DGE, 設計ギャップ探索）**（セクション3.9）：敵対的テスト（adversarial testing）と対話駆動分析による仕様-実装ギャップの体系的発見手法。10セッションで201以上のギャップを発見した実績を持つ。
6. **宣言的評価のための`@eval`アノテーション**（セクション3.10）：評価意味論（evaluation semantics）をグラマーに直接記述するUBNFの拡張。5種類の生成評価メソッドを持ち、手書きエバリュエータコードをさらに削減し、グラマーレベルでのコンパイラによる評価完全性チェックを可能にする。

本論文の残りは以下のように構成される。セクション2ではパーサー生成、言語ワークベンチ、IDEプロトコルサポートに関する関連研究をレビューする。セクション3ではUBNF文法表記、生成パイプライン、4つの貢献、PropagationStopperの操作的意味論、モナディック解釈（monadic interpretation）、MatchedTokenParser、DGE手法、`@eval`アノテーションを含むシステム設計を提示する。セクション4ではBoolean演算子、数学関数、三項式、Stringメソッドチェーン、ArgumentExpression、String predicateなどの新言語機能と128機能パリティインベントリを含む実装を記述する。セクション5ではtinyexpressionと回文認識のケーススタディを用いてフレームワークを評価し、性能ベンチマークと開発工数比較の両方を提示する。セクション6ではエラーリカバリ（SyncPointRecoveryParser）、インクリメンタルパーシング（IncrementalParseCache）、LSP機能（FormulaInfo、CodeAction）、P4フォールバックロギング、制限事項、妥当性への脅威、および将来の課題を議論する。セクション7で結論を述べる。

---

## 2. 背景と関連研究

### 2.1 パーサージェネレータ

パーサージェネレータの歴史は半世紀にわたる。Yacc [Johnson 1975] とその後継Bisonは、BNFで記述された文脈自由文法からLALR(1)パーサーを生成する。これらのツールは効率的なテーブル駆動パーサーを生成するが、文法が曖昧でなく左因子分解されていることを要求し、言語設計者にとって負担となりうる。LALRパーサーからのエラーメッセージは悪名高く不親切であり、生成されたパーサーはパースツリーを生成するが型付きASTは生成しない。

ANTLR [Parr and Fisher 2011] はALL(*)を導入した。これはLALR(1)よりも広いクラスの文法を扱える適応型LL解析戦略である。ANTLRはレキサーとパーサーの両方を生成し、オプションでツリー走査のためのvisitorまたはlistenerベースクラスを生成する。ANTLRはトークンの挿入、削除、単一トークン削除に基づく高度なエラーリカバリメカニズムを提供し、エラーからリカバリしてパーシングを継続する能力を持つ。しかし、ANTLRのvisitorパターンは開発者が各`visitXxx`メソッドを手書きで実装することを要求し、ANTLRはエバリュエータ、LSPサーバー、DAPサーバーを生成しない。開発者がすべての下流アーティファクトに責任を持つ。

Parsing Expression Grammar（PEG, 解析表現文法）[Ford 2004] は文脈自由文法に対する認識ベースの代替手段を提供する。PEGは非順序の選択（`|`）の代わりに順序付き選択（ordered choice, `/`）を使用し、構成により曖昧性を排除する。メモ化を伴うpackratパーサーを含むPEGベースのパーサーは、その予測可能性と実装の容易さから人気を得ている。しかし、PEGパーサーは通常リコグナイザーのみである -- 入力が文法にマッチするかどうかを判定するが、本質的に構造化されたパースツリーを生成しない。IerusalimschyのLPEG [Ierusalimschy 2009] やRedziejowskiのPEG基礎に関する研究 [Redziejowski 2007] を含む複数のPEGベースツールは、認識問題に焦点を当てており、AST構築やIDE統合には対処していない。

パーサーコンビネータライブラリは異なるアプローチをとる：パーサーはホスト言語におけるファーストクラスの値であり、高階関数（higher-order function）を使用して合成される。Haskellで書かれたParsec [Leijen and Meijer 2001] は、コミット選択意味論を通じた明確なエラーメッセージを提供するモナディックパーサーコンビネータでパラダイムを確立した。ScalaパーサーコンビネータはこのアプローチをJVMに持ち込んだ。パーサーコンビネータはホスト言語の型システムとの優れた合成可能性と統合を提供するが、パーサー自体以外は何も生成しない。

### 2.2 言語ワークベンチ

言語ワークベンチ [Erdweg et al. 2013] は、言語とそのツーリングを定義するための統合環境を提供する。3つのシステムが特に関連する：

**Spoofax** [Kats and Visser 2010] は文法定義にSDF3、AST変換にStratego、エディタサービス定義にESVを使用する。SpoofaxのパーサーはSGLR（Scannerless GLR）であり、曖昧な文法を扱うことができる -- 我々のPEGベースアプローチとの根本的な違いである。Spoofaxはパーサー、AST型、エディタサポート（シンタックスハイライト、構造的編集）を生成する。Spoofax 3の時点で、LSPサポートは開発中であるがまだ完全ではなく、DAPサポートは提供されていない。Spoofaxは3つの異なるDSL（SDF3、Stratego、ESV）の学習を要求するが、unlaxerは単一のUBNF仕様を使用する。

**JetBrains MPS** [Volter et al. 2006] はプロジェクショナルエディタ（projectional editor）であり、テキストベースのパーシングを完全にバイパスしてAST上で直接操作する。MPSはそのプロジェクショナルパラダイム内でネイティブに豊富なIDE機能（補完、エラーチェック、リファクタリング）を提供する。しかし、MPSはテキストベースのエディタとは根本的に異なるパラダイムを使用しており、VS CodeやEmacsなどの従来のテキストエディタで使用するためのLSPまたはDAPサーバーを生成しない。

**Xtext** [Bettini 2016] はEclipseベースの言語ワークベンチであり、パーサー（ANTLR経由）、AST型（EMF経由）、エディタ（EclipseベースおよびLSP）、オプションでインタプリタを生成する。Xtextは既存のワークベンチの中でunlaxerに最も近い機能カバレッジを提供する。しかし、XtextのLSPサポートはXtextランタイムを必要とし、DAPサポートは生成されず手動で実装する必要があり、エバリュエータはコンパイラによる完全性保証なしに手書きする必要がある。

### 2.3 Language Server ProtocolとDebug Adapter Protocol

Language Server Protocol（LSP）[Microsoft 2016a] はエディタと言語固有のインテリジェンスプロバイダー間の通信を標準化する。LSPサーバーはコード補完、ホバー情報、go-to-definition、find-references、診断（エラー/警告レポート）、コードアクション（リファクタリング操作）などの機能を実装する。LSP以前は、各エディタが言語固有のプラグインを必要とした。LSPはエディタサポートと言語実装を分離し、単一のサーバーがVS Code、Emacs、Vim、その他のLSP対応エディタで動作することを可能にした。

Debug Adapter Protocol（DAP）[Microsoft 2016b] は同じ分離パターンをデバッグに適用する。DAPサーバーはlaunch/attach、ブレークポイント、step-over/step-into/step-out、スタックトレース、変数インスペクション、式評価を実装する。LSPと同様に、DAPは単一のデバッグアダプタがエディタ間で動作することを可能にする。

標準化にもかかわらず、LSPまたはDAPサーバーの実装は依然として労力集約的である。中程度の複雑さの言語に対する典型的なLSPサーバーは2,000〜5,000行のコードを必要とし、DAPサーバーは1,000〜2,000行を必要とする。これらのサーバーは文法、AST、エバリュエータと同期を保つ必要がある。Tree-sitter [Brunel et al. 2023] はインクリメンタルパーシング、シンタックスハイライト、基本的な構造的クエリを提供することでLSP統合に部分的に対処するが、型を考慮した補完や診断レポートなどの意味的機能は提供しない。

unlaxer-parserは文法からLSPサーバーとDAPサーバーの両方を生成する。生成されたLSPサーバーは補完（文法キーワード、`@catalog`/`@declares`アノテーション、FormulaInfoメタデータに基づく）、診断（パースエラーと`ErrorMessageParser`メタデータに基づく）、ホバー（`@doc`アノテーションに基づく）、go-to-definition（FormulaInfo dependsOn解決に基づく）、リファクタリング用コードアクション（if/三項の双方向変換、ifチェーンからmatch変換）を提供する。生成されたDAPサーバーはパースツリーを通じたステップ実行、ブレークポイントサポート、現在のトークンのテキストとパーサー名を表示する変数表示を提供する。

### 2.4 ツール機能の比較

Table 1はツール間の生成アーティファクトを比較する。「Partial」はツールがインフラストラクチャを提供するが相当量の手書きコードを必要とすることを示す。「N/A」はツールが根本的に異なるパラダイムを使用することを示す。

| ツール | パーサー | AST型 | マッパー | エバリュエータ | LSP | DAP | エラーリカバリ | リファクタリング |
|------|--------|-----------|--------|-----------|-----|-----|----------------|-------------|
| Yacc/Bison | Yes | No | No | No | No | No | Basic | No |
| ANTLR | Yes | Partial | No | No | No | No | Yes | No |
| PEGツール | Yes | No | No | No | No | No | No | No |
| Parsec | Yes | No | No | No | No | No | Limited | No |
| tree-sitter | Yes | No | No | No | Partial | No | Yes | No |
| Spoofax | Yes | Yes | Partial (Stratego) | No | Partial | No | Yes | Yes |
| Xtext | Yes | Yes (EMF) | Yes | No | Yes | No | Yes | Yes |
| JetBrains MPS | N/A (projectional) | Yes | N/A | No | N/A | No | N/A | Yes |
| **unlaxer-parser** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** |

*Table 1: ツール別の生成アーティファクト。SpoofaxのマッパーはStratego（別のDSL）で記述される。XtextのLSPサポートはXtextランタイムを必要とする。MPSはテキストベースのLSPとは異なるプロジェクショナル編集パラダイムで動作する。unlaxerのエラーリカバリはSyncPointRecoveryParserにより実装され、指定されたシンクトークンまで前方スキャンしてパーシングを再開する -- ANTLRのパニックモードリカバリに匹敵するが、文法中の`@recover`アノテーションにより宣言的に指定される。リファクタリングは構造的変換のためのLSPコードアクションを指す。*

### 2.5 コード生成パターン

2つのコード生成パターンが本研究に特に関連する。

**Visitorパターン** [Gamma et al. 1994] は、生成されたパーサーにおけるASTノード走査の標準的アプローチである。ANTLRは文法ルールごとに1つの`visitXxx`メソッドを持つvisitorインターフェースを生成する。このパターンはツリー構造とそれに対する操作の間の良好な分離を提供するが、完全性を強制しない：開発者はvisitメソッドの実装を容易に忘れることができ、ランタイムエラーまたはサイレントな不正動作につながる。

**Generation Gap Pattern（GGP）** [Vlissides 1996] はコード生成における根本的な緊張に対処する：生成コードは入力（文法）が変更された時に再生成されなければならないが、手書きのカスタマイズは再生成を生き残らなければならない。GGPはこれを各クラスを2つに分割することで解決する：生成された抽象ベースクラスと手書きの具象サブクラスである。生成されたベースクラスは構造的なボイラープレートを含み、具象サブクラスは手書きのロジックを含む。ジェネレータが再実行された時、ベースクラスのみが上書きされる。

unlaxer-parserはGGPをJava 21のsealed interfaceと網羅的switch式と組み合わせる。生成されたエバリュエータベースクラスは、sealed ASTインターフェースに対するswitch式を持つprivateな`evalInternal`メソッドを含む：

```java
private T evalInternal(TinyExpressionP4AST node) {
    return switch (node) {
        case TinyExpressionP4AST.BinaryExpr n -> evalBinaryExpr(n);
        case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
        case TinyExpressionP4AST.IfExpr n -> evalIfExpr(n);
        case TinyExpressionP4AST.BooleanExpr n -> evalBooleanExpr(n);
        case TinyExpressionP4AST.FunctionCallExpr n -> evalFunctionCallExpr(n);
        case TinyExpressionP4AST.TernaryExpr n -> evalTernaryExpr(n);
        // ... @mappingクラスごとに1つのcase
    };
}
```

`TinyExpressionP4AST`はsealed interfaceであるため、Javaコンパイラはすべての許可されたサブタイプがswitchでカバーされていることを検証する。新しいASTノード型が文法に追加された場合、コンパイラは対応する`evalXxx`メソッドを開発者が実装するまで手書きの具象クラスを拒否する。これによりランタイムエラー（不足するvisitorメソッド）がコンパイルタイムエラーに変換される -- 安全性の大幅な向上である。

---

## 3. システム設計

### 3.1 UBNF文法表記

UBNF（Unlaxer Backus-Naur Form）は標準EBNFをコード生成を制御するアノテーションで拡張する。UBNF文法ファイルは、グローバル設定、トークン宣言、ルール宣言を含む名前付き文法を定義する。

**グローバル設定**は生成パイプラインを構成する：

```ubnf
grammar TinyExpressionP4 {
  @package: org.unlaxer.tinyexpression.generated.p4
  @whitespace: javaStyle
  @comment: { line: '//' }
```

`@package`設定は生成コードのJavaパッケージを指定する。`@whitespace`設定はホワイトスペース処理プロファイルを選択する（ここではJavaスタイルのホワイトスペース、`//`と`/* */`コメントをインターリーブトークンとして含む）。`@comment`設定はコメント構文を宣言する。

**トークン宣言**は終端記号をパーサークラスにバインドする：

```ubnf
  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token STRING     = SingleQuotedParser
  token CODE_BLOCK = org.unlaxer.tinyexpression.parser.javalang.CodeParser
```

各トークンはシンボリック名を`unlaxer-common`ライブラリの具象パーサークラス（またはユーザー定義パーサー）にマッピングする。これにより、文法は複雑な字句パターン（Javaスタイルのコードブロックなど）を文法表記にエンコードすることなく参照できる。

**ルール宣言**はEBNFライクな構文にアノテーションを付けて使用する：

```ubnf
  @root
  Formula ::= { VariableDeclaration } { Annotation } Expression { MethodDeclaration } ;
```

`@root`アノテーションは文法のエントリーポイントを示す。波括弧`{ ... }`はゼロ回以上の繰り返し、角括弧`[ ... ]`はオプション要素、丸括弧`( ... )`はグルーピング、`|`は順序付き選択（PEGセマンティクス）を表す。

**`@mapping`アノテーション**はAST生成の中心的メカニズムである：

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=10)
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

このアノテーションはコードジェネレータに以下を指示する：
1. sealed ASTインターフェースに`left`、`op`、`right`のフィールドを持つレコード型`BinaryExpr`を作成する。
2. パースツリーから`@left`、`@op`、`@right`アノテーション付き要素を抽出して`BinaryExpr`インスタンスを構築するマッパールールを生成する。
3. エバリュエータスケルトンに`evalBinaryExpr`抽象メソッドを生成する。

`@leftAssoc`アノテーションはマッパーで左結合グルーピングを生成し、`@precedence(level=N)`は曖昧性解消のための優先順位レベルを確立する。

**`@eval`アノテーション**は評価意味論をグラマーに直接記述する：

```ubnf
  @mapping(FunctionCallExpr, params=[name, args])
  @eval(dispatch=name, methods={
    sin:  Math.sin(toDouble(args[0])),
    cos:  Math.cos(toDouble(args[0])),
    sqrt: Math.sqrt(toDouble(args[0])),
    min:  variadicMin(args),
    max:  variadicMax(args)
  })
  FunctionCall ::= FunctionName @name '(' ArgList @args ')' ;
```

`@eval`アノテーションは文法からエバリュエータメソッド本体を直接生成し、数学関数ディスパッチなどの一般的パターンの手書きコードを削減する。5種類すべての生成評価メソッドの詳細はセクション3.10を参照のこと。

追加のアノテーションとして、ホワイトスペース挿入制御のための`@interleave(profile=javaStyle)`、メソッド宣言のスコーピングセマンティクスのための`@scopeTree(mode=lexical)`、参照解決のための`@backref(name=X)`、シンボル宣言のための`@declares`、補完カタログのための`@catalog`、ホバードキュメントのための`@doc`、エラーリカバリシンクポイントのための`@recover`がある。完全なtinyexpression文法（`tinyexpression-p4-complete.ubnf`）は580行にわたり、数値式、文字列式、ブール式、オブジェクト式、変数宣言、メソッド宣言、if/else式とmatch式、三項式、数学関数、import宣言、埋め込みJavaコードブロックをカバーする。

### 3.2 生成パイプライン

生成パイプラインは`.ubnf`文法ファイルを6つのJavaソースファイルに変換する。パイプラインは3つのフェーズからなる：

**フェーズ1: パーシング。** UBNF文法ファイルはセルフホスティングパーサー（`UBNFParsers`）によって解析される -- UBNFパーサー自体がunlaxer-parserのコンビネータライブラリを使用して構築されている。パースツリーは、ジェネレータがユーザー文法に対して生成するのと同じ`sealed interface + record`パターンを使用して型付きAST（`UBNFAST`）にマッピングされる。`UBNFAST`自体がsealed interfaceである：

```java
public sealed interface UBNFAST permits
    UBNFAST.UBNFFile,
    UBNFAST.GrammarDecl,
    UBNFAST.GlobalSetting,
    UBNFAST.SettingValue,
    UBNFAST.TokenDecl,
    UBNFAST.RuleDecl,
    UBNFAST.Annotation,
    UBNFAST.EvalAnnotation,
    UBNFAST.RuleBody,
    UBNFAST.AnnotatedElement,
    UBNFAST.AtomicElement,
    UBNFAST.TypeofElement { ... }
```

`EvalAnnotation`レコードはUBNFパーサーによって解析され、他のアノテーションとともにASTにマッピングされる。パーサーは`@eval(dispatch=..., methods={...})`構文を認識し、コードジェネレータが消費する構造化表現を生成する。

**フェーズ2: バリデーション。** `GrammarValidator`は文法の整形性をチェックする：未定義のルール参照、重複するルール名、欠落した`@root`アノテーション、ルール構造との`@mapping`パラメータの整合性、`@eval`アノテーションのディスパッチターゲットの妥当性。

**フェーズ3: コード生成。** `CodeGenerator`インターフェースをそれぞれ実装する6つのコードジェネレータが出力を生成する：

| ジェネレータ | 出力 | 説明 |
|-----------|--------|-------------|
| `ParserGenerator` | `XxxParsers.java` | `LazyChain`、`LazyChoice`、`LazyZeroOrMore`等を使用するPEGベースのパーサーコンビネータ。ホワイトスペース処理は`@interleave`プロファイルに基づき自動挿入される。 |
| `ASTGenerator` | `XxxAST.java` | `@mapping`クラスごとに1つの`record`を持つJava 21 sealed interface。フィールドは`params`リストに従って型付けされる。 |
| `MapperGenerator` | `XxxMapper.java` | トークンツリーからASTへのマッピングロジック。マルチルールマッピング（複数の文法ルールが同じASTクラスにマッピング）、`@leftAssoc`/`@rightAssoc`グルーピング、ネストされたサブ式への深層検索を防ぐ`findDirectDescendants`を処理する。 |
| `EvaluatorGenerator` | `XxxEvaluator.java` | 網羅的sealed-switchディスパッチ、ステップデバッグフック用の`DebugStrategy`インターフェース、`@eval`アノテーション付きルールの生成メソッド本体を持つ抽象クラス。5種類の`@eval`生成（dispatch、direct expression、operator table、literal、delegation）をサポートする。 |
| `LSPGenerator` | `XxxLanguageServer.java` | 補完（キーワード、`@catalog`エントリ、`@declares`シンボル、FormulaInfoメタデータ）、診断（パースエラーと`ErrorMessageParser`メタデータ）、ホバー（`@doc`アノテーション）、go-to-definition（FormulaInfo dependsOn解決）、コードアクション（if/三項双方向変換を含むリファクタリング操作）を備えたLSPサーバー。`@declares`/`@backref`/`@scopeTree`アノテーション存在時にスコープストア登録を含む。 |
| `DAPGenerator` | `XxxDebugAdapter.java` | `stopOnEntry`サポート、パースツリーを通じたステップオーバー実行、ブレークポイント管理、スタックトレース表示、変数インスペクションを備えたDAPサーバー。 |

### 3.3 貢献：伝播制御

unlaxer-parserのコンビネータアーキテクチャでは、すべてのパーサーの`parse`メソッドが3つのパラメータを受け取る：

```java
public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch)
```

- `ParseContext`は入力カーソル、トランザクションスタック、トークンツリーを保持する。
- `TokenKind`はパーサーが入力を*消費*するか（`consumed`）、カーソルを進めずに*マッチのみ*行うか（`matchOnly`）を制御する。これはPEGの先読み述語（lookahead predicate）に相当する。
- `invertMatch`はパーサーの成功/失敗の意味論を反転する -- `true`の場合、成功したマッチが失敗として扱われ、その逆も同様。これはPEGの「not」述語である。

素朴な実装では、`tokenKind`と`invertMatch`の両方が親から子へ無条件に伝播する。これは問題を引き起こす。`Not`コンビネータを考えてみよう：

```java
public class Not extends ConstructedSingleChildParser {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        parseContext.begin(this);
        Parsed parsed = getChild().parse(parseContext, TokenKind.matchOnly, invertMatch);
        if (parsed.isSucceeded()) {
            parseContext.rollback(this);
            return Parsed.FAILED;
        }
        Parsed committed = new Parsed(parseContext.commit(this, TokenKind.matchOnly));
        return committed;
    }
}
```

`Not`はその子を`matchOnly`モードに強制する（先読み中に入力を消費してはならない）。しかし、子がそれ自体`Not`コンビネータを含む複雑なサブ式である場合はどうなるか？ 外側の`Not`からの`matchOnly`が下方に伝播するが、内側の`DoConsumePropagationStopper`がそれを選択的にオーバーライドできるべきである。

我々は**PropagationStopper階層**を導入する。これは2次元の伝播に対する細粒度制御を提供する4つのクラスのセットである：

```java
public interface PropagationStopper { }
```

**1. AllPropagationStopper**: `TokenKind`と`invertMatch`の両方の伝播を停止する。子は親が何を渡しても常に`TokenKind.consumed`と`invertMatch=false`を受け取る：

```java
public class AllPropagationStopper extends ConstructedSingleChildParser
    implements PropagationStopper {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        parseContext.startParse(this, parseContext, tokenKind, invertMatch);
        Parsed parsed = getChild().parse(parseContext, TokenKind.consumed, false);
        parseContext.endParse(this, parsed, parseContext, tokenKind, invertMatch);
        return parsed;
    }
}
```

**2. DoConsumePropagationStopper**: `TokenKind`の伝播のみを停止し、子を`consumed`モードに強制しつつ`invertMatch`は通過させる：

```java
public class DoConsumePropagationStopper extends ConstructedSingleChildParser
    implements PropagationStopper {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        Parsed parsed = getChild().parse(parseContext, TokenKind.consumed, invertMatch);
        return parsed;
    }
}
```

**3. InvertMatchPropagationStopper**: `invertMatch`の伝播のみを停止し、子を`invertMatch=false`に強制しつつ`TokenKind`は通過させる：

```java
public class InvertMatchPropagationStopper extends AbstractPropagatableSource
    implements PropagationStopper {
    @Override
    public Parsed parseDelegated(ParseContext parseContext, TokenKind tokenKind,
                                  boolean invertMatch) {
        Parsed parsed = getChild().parse(parseContext, tokenKind, false);
        return parsed;
    }
}
```

**4. NotPropagatableSource**: `invertMatch`フラグを反転する（伝播された値に対する論理NOT）。親が`invertMatch=true`を渡すと子は`invertMatch=false`を受け取り、その逆も同様：

```java
public class NotPropagatableSource extends AbstractPropagatableSource {
    @Override
    public boolean computeInvertMatch(boolean fromParentValue, boolean thisSourceValue) {
        return false == fromParentValue;
    }
}
```

この階層は、状態空間`S = {consumed, matchOnly} x {true, false}`上の自己写像のセットとして形式的に特徴づけることができる。各伝播ストッパーはどの次元を遮断し、どの値を代入するかを選択する。設計は合成的（compositional）である：伝播ストッパーはネストでき、各ストッパーはそれぞれの次元に対して独立に動作する。形式的な操作的意味論はセクション3.6で、モナディック解釈はセクション3.7で提示する。

Parsecは`try`と`lookAhead`コンビネータを通じて先読みを処理し、コミット/非コミット選択とゼロ幅アサーションをそれぞれ制御する。これらのコンビネータはモナドの結合律によって合成可能である。しかし、パーシング状態を2つの直交する次元（`TokenKind`と`invertMatch`）に分解し、独立した伝播制御を行うという特定の分解は、Parsec、megaparsec、attoparsec、その他の我々が調査したパーサーコンビネータフレームワークにおいてファーストクラスAPIとして提供されていない。特に、`invertMatch`の伝播制御 -- コンビネータツリーの任意の点で否定フラグを選択的に停止または反転する能力 -- は既存のフレームワークの標準コンビネータセットに直接の対応物を持たない。

### 3.4 貢献：メタデータ搬送パースツリー

unlaxer-parserにおける根本的な洞察は、パースツリーがパーシングフェーズとIDE統合フェーズの間の**通信チャネル**として機能しうるということである。これは`ContainerParser<T>`によって実現される。これは入力を消費することなく型付きメタデータをパースツリーに挿入する抽象パーサークラスである：

```java
public abstract class ContainerParser<T> extends NoneChildParser {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        Token token = Token.empty(tokenKind, parseContext.getCursor(TokenKind.consumed), this);
        parseContext.getCurrent().addToken(token);
        return new Parsed(token);
    }

    public abstract T get();
    public abstract RangedContent<T> get(CursorRange position);
}
```

重要な性質は、`ContainerParser`が現在のカーソル位置に**空トークン**を作成することである -- 入力を消費せずに成功する。パーサーインスタンス自体が`get()`と`get(CursorRange)`メソッドを通じてアクセス可能なメタデータを搬送する。パーシング後、特定の`ContainerParser`サブクラスのインスタンスであるパーサーのトークンをフィルタリングすることで、トークンツリーからメタデータを抽出できる。

2つの具象サブクラスがこのパターンを実証する：

**ErrorMessageParser**は診断レポートのためにエラーメッセージをパースツリーに埋め込む：

```java
public class ErrorMessageParser extends ContainerParser<String> implements TerminalSymbol {
    String message;
    boolean expectedHintOnly;

    public static ErrorMessageParser expected(String message) {
        return new ErrorMessageParser(message, true);
    }

    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        if (expectedHintOnly) {
            parseContext.startParse(this, parseContext, tokenKind, invertMatch);
            parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
            return Parsed.FAILED;
        }
        return super.parse(parseContext, tokenKind, invertMatch);
    }

    @Override
    public Optional<String> expectedDisplayText() {
        if (expectedHintOnly) return Optional.of(message);
        return Optional.empty();
    }
}
```

`expectedHintOnly=true`で使用された場合、パーサーは意図的に失敗するが、「期待される」トークンとして自身を登録し、エラーレポートシステムにその位置で何が期待されていたかの人間が読める記述を提供する。この情報はLSPサーバーの診断ハンドラに直接流れる。

**SuggestsCollectorParser**はパーシング中に兄弟パーサーから補完候補を収集する：

```java
public class SuggestsCollectorParser extends ContainerParser<Suggests> {
    Suggests suggests;

    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        Parsed parsed = super.parse(parseContext, tokenKind, invertMatch);
        parsed.status = Status.stopped;
        Source remain = parseContext.getRemain(TokenKind.consumed);
        List<Suggest> collect = getSiblings(false).stream()
            .filter(SuggestableParser.class::isInstance)
            .map(SuggestableParser.class::cast)
            .map(sp -> sp.getSuggests(remain.toString()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toList());
        suggests = new Suggests(collect);
        return parsed;
    }
}
```

このパーサーは兄弟パーサー（コンビネータツリーの同レベルにあるもの）に残りの入力に基づく補完候補を問い合わせる。候補はトークンツリーに格納され、後にLSPサーバーの補完ハンドラによって抽出される。

メタデータ搬送パースツリーパターンにより、単一のパースパスで評価とIDE機能の両方に必要なすべての情報を生成できる。このメカニズムなしには、LSPおよびDAP統合は別パスまたは並行データ構造を必要とし、複雑さと不整合のリスクが増大する。

### 3.5 貢献：エバリュエータ向けGeneration Gap Pattern

Generation Gap Pattern（GGP）[Vlissides 1996] は、生成コードと手書きコードを継承関係にある異なるクラスに配置することで分離する。unlaxer-parserはGGPをエバリュエータ構築に適用し、重要な拡張を加える：Java 21のsealed interfaceが**コンパイラによる完全性チェック**を提供する。

ジェネレータは抽象エバリュエータクラスを生成する：

```java
public abstract class TinyExpressionP4Evaluator<T> {

    private DebugStrategy debugStrategy = DebugStrategy.NOOP;

    public T eval(TinyExpressionP4AST node) {
        debugStrategy.onEnter(node);
        T result = evalInternal(node);
        debugStrategy.onExit(node, result);
        return result;
    }

    private T evalInternal(TinyExpressionP4AST node) {
        return switch (node) {
            case TinyExpressionP4AST.BinaryExpr n -> evalBinaryExpr(n);
            case TinyExpressionP4AST.ComparisonExpr n -> evalComparisonExpr(n);
            case TinyExpressionP4AST.IfExpr n -> evalIfExpr(n);
            case TinyExpressionP4AST.BooleanExpr n -> evalBooleanExpr(n);
            case TinyExpressionP4AST.FunctionCallExpr n -> evalFunctionCallExpr(n);
            case TinyExpressionP4AST.TernaryExpr n -> evalTernaryExpr(n);
            case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
            // ... @mappingタイプごとに1つのcase
        };
    }

    protected abstract T evalBinaryExpr(TinyExpressionP4AST.BinaryExpr node);
    protected abstract T evalComparisonExpr(TinyExpressionP4AST.ComparisonExpr node);
    protected abstract T evalIfExpr(TinyExpressionP4AST.IfExpr node);
    protected abstract T evalBooleanExpr(TinyExpressionP4AST.BooleanExpr node);
    protected abstract T evalFunctionCallExpr(TinyExpressionP4AST.FunctionCallExpr node);
    // ... @mappingクラスごとに1つの抽象メソッド

    public interface DebugStrategy {
        void onEnter(TinyExpressionP4AST node);
        void onExit(TinyExpressionP4AST node, Object result);
        DebugStrategy NOOP = new DebugStrategy() {
            public void onEnter(TinyExpressionP4AST node) {}
            public void onExit(TinyExpressionP4AST node, Object result) {}
        };
    }
}
```

開発者は次に**具象**サブクラスを記述する：

```java
public class P4TypedAstEvaluator extends TinyExpressionP4Evaluator<Object> {

    private final ExpressionType resultType;
    private final CalculationContext context;

    @Override
    protected Object evalBinaryExpr(BinaryExpr node) {
        return evalBinaryAsNumber(node);
    }

    @Override
    protected Object evalIfExpr(IfExpr node) {
        Object conditionValue = eval(node.condition());
        boolean cond = Boolean.TRUE.equals(toBoolean(conditionValue));
        ExpressionExpr branch = cond ? node.thenExpr() : node.elseExpr();
        return eval(branch);
    }

    @Override
    protected Object evalBooleanExpr(BooleanExpr node) {
        return evalBooleanWithPrecedence(node);
    }

    // ... 他のすべてのevalXxxメソッドの実装
}
```

この設計は3つの保証を提供する：

1. **完全性**: 文法が新しい`@mapping`ルールを追加すると、sealed interfaceは新しい許可されたサブタイプを獲得し、生成されたswitchが非網羅的になり、コンパイラは新しい`evalXxx`メソッドが追加されるまで具象クラスを拒否する。
2. **再生成安全性**: 抽象ベースクラスのみが再生成される。ドメイン固有の評価ロジックをすべて含む具象サブクラスは決して上書きされない。
3. **デバッグ統合**: 生成されたベースクラスの`DebugStrategy`フックにより、手書きクラスにコードを追加することなくDAPサーバーを通じたステップデバッグが可能になる。

GGPアプローチは同じ文法からの複数の評価戦略もサポートする。tinyexpressionプロジェクトは同じ生成ベースを拡張する3つの具象エバリュエータを実装している：
- `P4TypedAstEvaluator`: ASTを直接解釈し、`Object`値を返す。
- `P4TypedJavaCodeEmitter`: ASTを走査してランタイムコンパイル用のJavaソースコードを出力する。
- `P4DefaultJavaCodeEmitter`: デフォルト評価パターン用のテンプレートベースエミッター。

### 3.6 PropagationStopperの操作的意味論

PropagationStopper階層をスモールステップ操作的意味論（small-step operational semantics）を用いて形式化する。パーサー状態を`s = (tk, inv)`とし、`tk in {consumed, matchOnly}`、`inv in {true, false}`とする。各伝播ストッパーは`S = {consumed, matchOnly} x {true, false}`上の自己写像である。

**推論規則。** パーサー`p`がコンテキスト`ctx`と状態`s`を与えられ結果`r`を生成することを`p.parse(ctx, s) => r`と書く。

```
                           p.parse(ctx, s) => r
  --------------------------------------------------- [Default]
  Wrapper(p).parse(ctx, s) => r


                           p.parse(ctx, (consumed, false)) => r
  ---------------------------------------------------------------- [AllStop]
  AllPropagationStopper(p).parse(ctx, (tk, inv)) => r


                           p.parse(ctx, (consumed, inv)) => r
  ---------------------------------------------------------------- [DoConsume]
  DoConsumePropagationStopper(p).parse(ctx, (tk, inv)) => r


                           p.parse(ctx, (tk, false)) => r
  ---------------------------------------------------------------- [StopInvert]
  InvertMatchPropagationStopper(p).parse(ctx, (tk, inv)) => r


                           p.parse(ctx, (tk, not(inv))) => r
  ---------------------------------------------------------------- [NotProp]
  NotPropagatableSource(p).parse(ctx, (tk, inv)) => r
```

**代数的性質。** 4つのストッパーと恒等写像は、4要素集合`S`上の自己写像の有限集合を形成する。主要な性質は以下の通り：

*冪等性（idempotence）:*

- `AllStop . AllStop = AllStop`（定数写像は冪等）
- `DoConsume . DoConsume = DoConsume`
- `StopInvert . StopInvert = StopInvert`
- `NotProp . NotProp = Id`（対合、冪等ではない）

*吸収律（absorption）:*

- `AllStop . X = AllStop`（任意のストッパー`X`に対し、AllStopは右零元）

*選択された合成:*

- `DoConsume . StopInvert = AllStop`
- `StopInvert . DoConsume = AllStop`
- `AllStop . DoConsume = AllStop`
- `NotProp . NotProp = Id`

*非可換性:* ストッパー代数は一般に可換ではない。`DoConsume . StopInvert = StopInvert . DoConsume = AllStop`である一方、`StopInvert . NotProp != NotProp . StopInvert`である。具体的に：

- `StopInvert . NotProp`: `(tk, inv) -> (tk, not(inv)) -> (tk, false) = StopInvert`
- `NotProp . StopInvert`: `(tk, inv) -> (tk, false) -> (tk, true)` = `ForceInvert`（第2成分上の新しい定数写像）

これは、4つのストッパーが合成のもとで可換モノイドを形成しないが、`Id`を単位元とし`AllStop`を右吸収元とする有限非可換モノイド（finite non-commutative monoid）を形成することを示している。

### 3.7 モナディック解釈

PropagationStopper階層とContainerParserは、よく知られたモナディック抽象化（monadic abstraction）との正確な対応関係を持つ。これらの対応を明示的に認め、Javaベースの実現が見落としではなく意図的な設計選択である理由を説明する。

| unlaxerの概念 | モナディック対応 | 説明 |
|-----------------|----------------------|-------------|
| PropagationStopper | Readerモナドの`local` | 環境パラメータのローカル修正 |
| AllPropagationStopper | `local (const (C,F))` | 定数環境で置換 |
| DoConsumePropagationStopper | `local (\(_,i)->(C,i))` | 第1成分のみ固定 |
| InvertMatchPropagationStopper | `local (\(t,_)->(t,F))` | 第2成分のみ固定 |
| NotPropagatableSource | `local (\(t,i)->(t,not i))` | 第2成分を反転 |
| ContainerParser\<T\> | Writerモナドの`tell` | 副作用なしにメタデータを蓄積 |
| ErrorMessageParser | `tell [ErrorMsg msg]` | エラーメッセージを蓄積 |
| SuggestsCollectorParser | `tell [Suggestions xs]` | 補完候補を蓄積 |
| ParseContext.begin/commit/rollback | バックトラッキング付きStateモナドのget/put | パーサー状態の保存/復元 |
| Parsed.FAILED | ExceptTの`throwError` | パース失敗の伝播 |

Haskellでは、フレームワーク全体をモナドトランスフォーマースタック（monad transformer stack）として表現できる：

```haskell
type ParserEnv = (TokenKind, InvertMatch)
type Parser a = ReaderT ParserEnv (WriterT [Metadata] (StateT ParseState
                  (ExceptT ParseError Identity))) a

-- AllPropagationStopper
allStop :: Parser a -> Parser a
allStop = local (const (Consumed, False))

-- DoConsumePropagationStopper
doConsume :: Parser a -> Parser a
doConsume = local (\(_, inv) -> (Consumed, inv))

-- Container metadata
errorMessage :: String -> WriterT [Metadata] Parser ()
errorMessage msg = tell [ErrorMsg msg]
```

**Javaクラス階層がモナドトランスフォーマーより優れている理由。** モナディック定式化ではなくJavaのクラス階層を選択した理由は3つある：

1. **デバッグ可能性（debuggability）。** JavaのクラスベースPropagationStopper階層はIDEデバッガから直接可視である。開発者は`AllPropagationStopper.parse()`にブレークポイントを設定し、Variables ペインで`tokenKind`と`invertMatch`を検査し、伝播ロジックをステップスルーできる。モナドトランスフォーマースタックでは、等価な状態はネストされた`runReaderT`/`runWriterT`/`runStateT`クロージャに埋もれ、標準的なデバッガからは不透明である。

2. **IDEサポート。** Javaの型階層は標準的なIDE機能を可能にする：「AllPropagationStopperへの参照をすべて検索」「PropagationStopperの実装に移動」「型階層を表示」。これらの操作はすべてのJava IDEで直接サポートされている。Haskellでは等価な操作に特化したツーリング（HLS）が必要であり、ユーザー定義DSLには及ばない。

3. **LSP/DAP生成。** 我々のフレームワークは文法からLSPサーバーとDAPサーバーを生成する。生成されたDAPサーバーはPropagationStopper遷移をデバッグイベントとして表示しながら、パーサーコンビネータツリーのステップスルーデバッグを提供する。この生成パイプラインはJavaクラス構造上で動作し、モナディック定式化のためには根本的な再設計が必要となる。既存のHaskellパーサーコンビネータフレームワークで文法仕様からLSPまたはDAPサーバーを生成するものは存在しない。

モナディック解釈は我々の抽象化の形式的な特徴づけとして価値があり、そのように提示する。しかし、unlaxer-parserの実用的価値は個々の抽象化 -- 対応テーブルが示すように、よく知られたモナディック対応物を持つ -- にあるのではなく、単一の文法仕様から6つの整合したアーティファクトを生成する統一コード生成パイプラインへのそれらの統合にある。モナディック構造は「どうパースするか」を説明するが、「どうやって単一の文法から6つのアーティファクトすべてを生成するか」は説明しない。

Java 21のsealed interfaceはHaskellの代数的データ型（Algebraic Data Type, ADT）との具体的な比較に値する。両者とも閉じた型階層（closed type hierarchy）を強制する：sealed interfaceは宣言されたサブタイプのみを許可し、ADTは固定のコンストラクタセットを定義する。Javaの網羅的`switch`式はHaskellの完全性チェック付きパターンマッチに対応する。主な違いは、Java sealed interfaceが名前的（nominal, クラス名と継承に基づく）であるのに対し、Haskell ADTは構造的（structural, コンストラクタに基づく）であることである。AST評価の目的においてはこの違いは重要でない：両者とも、新しいノード型の追加が開発者にそのハンドリングを強制し、ランタイムエラーをコンパイルタイムエラーに変換するという重要な保証を提供する。

### 3.8 文脈自由を超えて：MatchedTokenParser

標準的なPEGおよび文脈自由文法は特定の重要なパターンを認識できない。正規の例は回文言語（palindrome language）`L = { w w^R | w in Sigma* }`であり、これは文脈依存（context-sensitive）である。XMLスタイルのタグペアリング（開始タグ`<foo>`と対応する終了タグ`</foo>`のマッチング）も実用的な例である。従来のパーサージェネレータでは、これらのパターンは文法形式主義の外でアドホックなコードにより処理されなければならない。

unlaxer-parserの`MatchedTokenParser`は、マッチしたコンテンツをキャプチャし再生する -- オプションで逆順にする -- コンビネータレベルのメカニズムを提供し、パーサーコンビネータフレームワーク内で文脈依存パターンの認識を可能にする。

**設計。** `MatchedTokenParser`は先行する`MatchOnly`（先読み）パーサーと連携して動作する。`MatchOnly`パーサーは入力を消費せずにマッチし、認識されたコンテンツを確立する。`MatchedTokenParser`はこのキャプチャされたコンテンツにアクセスし、いくつかの操作を提供する：

- **直接再生**: 現在の位置で同じコンテンツにマッチする（XMLタグペアリングに有用）。
- **`slice`**: キャプチャされたコンテンツのサブ範囲を、設定可能なstart、end、stepパラメータで抽出する。
- **`effect`**: キャプチャされたコンテンツに任意の変換を適用する（例：逆順にする）。
- **`pythonian`**: Pythonスタイルのスライス記法（例：逆順の`"::-1"`）を簡潔な記述に使用する。これは利便性メソッドであり、本番使用には型安全な`slice(slicer -> slicer.step(-1))` APIが推奨される。

**理論的着想源：Macro PEG。** MatchedTokenParserの設計はMacro PEG [Mizushima 2016] に着想を得ている。Macro PEGは回文のような文脈依存パターンを扱うためにパラメータ化されたルールでPEGを拡張する。Macro PEGがこれを文法レベルの拡張（パラメータを受け取るルール）で達成するのに対し、unlaxerはオブジェクト指向アプローチをとる：MatchedTokenParserはマッチしたコンテンツをコンビネータレベルでキャプチャし、トークン操作のための合成可能な操作を提供する。両アプローチともPEGの認識能力を文脈自由言語を超えて拡張するが、unlaxerの設計はJavaの型システムとIDEツーリングに自然に統合される。

**回文認識：5つの実装。** 以下の5つの実装はMatchedTokenParserの回文認識における表現力を実証する。5つすべてが`Usage003_01_Palidrome.java`でテストされ、"a"、"abcba"、"abccba"、"aa"などの文字列を正しく認識し、"ab"などの非回文を正しく拒否する。

*実装1: sliceWithWord。* 入力を前半、ピボット（奇数長の場合）、逆順の前半に分解する：

```java
Chain palindrome = new Chain(
    wordLookahead,
    matchedTokenParser.sliceWithWord(word -> {
        boolean hasPivot = word.length() % 2 == 1;
        int halfSize = (word.length() - (hasPivot ? 1 : 0)) / 2;
        return word.cursorRange(new CodePointIndex(0), new CodePointIndex(halfSize),
            SourceKind.subSource, word.positionResolver());
    }),
    matchedTokenParser.sliceWithWord(word -> { /* pivot extraction */ }),
    matchedTokenParser.slice(word -> { /* first half reversed */ }, true)
);
```

*実装2: sliceWithSlicer。* 範囲指定に`Slicer` APIを使用する：

```java
matchedTokenParser.slice(slicer -> {
    boolean hasPivot = slicer.length() % 2 == 1;
    int halfSize = (slicer.length() - (hasPivot ? 1 : 0)) / 2;
    slicer.end(new CodePointIndex(halfSize));
})
```

*実装3: effectReverse。* Javaの`StringBuilder.reverse()`を使用して完全な逆転を適用する：

```java
matchedTokenParser.effect(word ->
    StringSource.createDetachedSource(new StringBuilder(word).reverse().toString()))
```

*実装4: sliceReverse。* 型安全なSlicer APIを通じたstep=-1による逆転：

```java
matchedTokenParser.slice(slicer -> slicer.step(-1))
```

*実装5: pythonian。* Pythonスタイルのスライス記法を使用する：

```java
matchedTokenParser.slice(slicer -> slicer.pythonian("::-1"))
```

`pythonian`メソッドはPythonのスライス記法に慣れた開発者向けの利便性APIを提供し、`"::-1"`はPythonの`[::-1]`文字列逆転イディオムに対応する。本番使用にはSlicer builderインターフェースによるコンパイル時バリデーションを提供する型安全な`slice(slicer -> slicer.step(-1))` API（実装4）が推奨される。`pythonian`メソッドはパース時に入力バリデーションを行い、不正なスライス文字列を`IllegalArgumentException`で拒否する。型安全なSlicer APIと`pythonian`利便性メソッドは相補的である：前者は安全性とIDEディスカバラビリティを優先し、後者はPythonとJavaのコードベースを横断して作業する開発者にとっての簡潔さを優先する。

**XMLタグペアリング。** 回文を超えて、MatchedTokenParserは開始タグと終了タグのXMLスタイルのマッチングをサポートする。文法は`<tagname>`からタグ名をキャプチャし、終了タグ`</tagname>`の位置で再生でき、パーサーレベルでポストパースバリデーションではなく構造的整合性を保証する。

**Macro PEGとの比較。** Table 2はPEGを文脈自由の認識を超えて拡張するアプローチを比較する：

| システム | アプローチ | CFGを超える | ホスト言語との統合 | IDEサポート |
|--------|----------|-----------|-------------------------------|-------------|
| PEG (Ford 2004) | 文法記法 | No | N/A | No |
| Macro PEG (Mizushima 2016) | パラメータ化された文法ルール | Yes（文法レベル） | 限定的（スタンドアロン） | No |
| unlaxer MatchedTokenParser | コンビネータレベルのキャプチャ＋再生 | Yes（オブジェクトレベル） | 完全なJava統合 | Yes（LSP/DAP） |

*Table 2: PEGを文脈自由言語を超えて拡張するアプローチ。Macro PEGは文法レベルで操作し、MatchedTokenParserはホスト言語内のコンビネータレベルで操作する。*

Macro PEGのパラメータ化されたルールは文脈依存パターンのためのクリーンな文法レベルの形式主義を提供するが、カスタムパーサージェネレータを必要とし、ホスト言語ツーリングとは統合されない。MatchedTokenParserは文法レベルのエレガンスをJavaの型システム、IDEデバッガ、unlaxerコード生成パイプラインとの完全な統合と引き換えにする。キャプチャされたコンテンツは標準的なJavaツールを使用して検査、変換、デバッグできるファーストクラスのJavaオブジェクトである。

### 3.9 Design-Gap Exploration（DGE）手法

tinyexpression文法とエバリュエータの開発中に、我々は仕様-実装ギャップを発見するための体系的な手法を開発し、**Design-Gap Exploration（DGE）**と名付けた。この手法は、文法仕様、パーサー実装、AST定義、マッパー、エバリュエータが互いに対して体系的にテストされ不整合が特定される、敵対的で対話駆動の分析セッションを含む。

**プロセス。** 各DGEセッションは構造化されたプロトコルに従う：

1. **仕様レビュー**: UBNF文法を調査し、仕様されているがエバリュエータにまだ実装されていないルール、または実装されているがまだテストされていないルールを特定する。
2. **敵対的テスト生成**: 境界条件、言語機能間の相互作用効果、エラーパスを試すテストケースを設計する。
3. **ギャップ分類**: 発見されたギャップを(a) 欠落する評価ロジック、(b) パーサー-エバリュエータ不一致、(c) 型変換ギャップ、(d) エラーメッセージ品質ギャップ、(e) LSP/DAP統合ギャップに分類する。
4. **解決計画**: 各ギャップに優先度を割り当て、特定の文法ルール、ASTノード、またはエバリュエータメソッドに関連付ける。

**結果。** 開発期間中に10回のDGEセッションが実施された。セッションは以下のカテゴリにわたる**201以上のギャップ**を発見した：

| カテゴリ | 数 | 例 |
|----------|-------|---------|
| 欠落する評価ロジック | 62 | P4エバリュエータに`not()`関数が未実装 |
| パーサー-エバリュエータ不一致 | 41 | 三項式のパースは成功したがマッパーが不正なASTノードを生成 |
| 型変換ギャップ | 33 | `toNum("3.14")`がNumberではなくStringを返した |
| エラーメッセージ品質 | 27 | `3 + `の欠落演算子が「expected expression」ではなくジェネリックな「parse failed」を生成 |
| LSP/DAP統合 | 22 | 補完が数学関数名を提案しなかった |
| テストカバレッジ | 16 | `min($a, $b, $c)`の可変引数形式のテストなし |

DGE手法はDSL開発実践への貢献である。既知の要件を検証する従来のテストとは異なり、DGEは仕様（文法）と実装（パーサー + エバリュエータ + IDE）の間のギャップを体系的に検索する。この手法はunlaxerベースの開発に特に効果的である。6つの生成アーティファクトが整合性をテストするための6つの独立した表面を提供するためである。

### 3.10 宣言的評価のための`@eval`アノテーション

`@eval`アノテーションは、評価意味論をグラマーに直接記述するメカニズムでUBNFを拡張する。これは実践的な観察に対処する：多くの評価メソッドは関数ディスパッチ、演算子適用、型変換などの予測可能なパターンに従い、命令的（imperatively）ではなく宣言的（declaratively）に記述できる。

**構文。** `@eval`アノテーションは5種類の生成評価をサポートする：

```ubnf
// 種類1: 名前によるディスパッチ
@eval(dispatch=name, methods={
  sin:  Math.sin(toDouble(args[0])),
  cos:  Math.cos(toDouble(args[0])),
  tan:  Math.tan(toDouble(args[0])),
  sqrt: Math.sqrt(toDouble(args[0])),
  abs:  Math.abs(toDouble(args[0])),
  round: Math.round(toDouble(args[0])),
  ceil: Math.ceil(toDouble(args[0])),
  floor: Math.floor(toDouble(args[0])),
  pow:  Math.pow(toDouble(args[0]), toDouble(args[1])),
  log:  Math.log(toDouble(args[0])),
  exp:  Math.exp(toDouble(args[0])),
  random: Math.random(),
  min:  variadicMin(args),
  max:  variadicMax(args)
})

// 種類2: 直接式
@eval(expr=toBoolean(eval(node.operand())) ? false : true)

// 種類3: 演算子テーブル
@eval(operators={
  "+": add(left, right),
  "-": subtract(left, right),
  "*": multiply(left, right),
  "/": divide(left, right)
})

// 種類4: リテラル
@eval(literal=toNumber(node.value()))

// 種類5: 委譲
@eval(delegate=eval(node.inner()))
```

5種類は最も一般的な評価パターンをカバーする：(1) dispatchはフィールド値（例：関数名）に基づいてメソッド本体を選択する、(2) direct expressionは任意のJava式をインライン化する、(3) operator tableは演算子文字列を二項演算にマッピングする、(4) literalは終端トークンを型付き値に変換する、(5) delegationは子ノードに評価を委譲する。これら5種類を合わせると、評価メソッドの大半の手書きコードを排除しつつ、Generation Gap Patternにより具象サブクラスで任意の生成メソッドをオーバーライドする能力を維持する。

**実装。** `@eval`アノテーションはUBNFパーサーによってUBNFASTの`EvalAnnotation`レコードにパースされる：

```java
public record EvalAnnotation(
    String dispatchField,
    Map<String, String> methods,
    String directExpr,
    Map<String, String> operators
) implements UBNFAST { }
```

`EvaluatorGenerator`は`EvalAnnotation`を処理して、抽象メソッドとして残す代わりに抽象エバリュエータクラスに具象メソッド本体を生成する。`@eval`アノテーションが存在する場合、対応する`evalXxx`メソッドは生成された実装を持ち、具象サブクラスは必要に応じてオーバーライドできる：

```java
// @eval(dispatch=name, methods={sin: Math.sin(toDouble(args[0])), ...})から生成
protected T evalFunctionCallExpr(TinyExpressionP4AST.FunctionCallExpr node) {
    String name = node.name();
    List<TinyExpressionP4AST> args = node.args();
    return switch (name) {
        case "sin"  -> (T) Double.valueOf(Math.sin(toDouble(args.get(0))));
        case "cos"  -> (T) Double.valueOf(Math.cos(toDouble(args.get(0))));
        // ... さらに12ケース
        default -> throw new EvalException("Unknown function: " + name);
    };
}
```

`@eval`アノテーションはGeneration Gap Patternと相補的である：具象サブクラスでオーバーライドできるデフォルトの評価ロジックを生成する。このハイブリッドアプローチにより、一般的なパターン（数学関数、ブール演算子）を文法で宣言的に記述しつつ、複雑なケースにはJavaで任意の評価ロジックを記述する能力を維持できる。

---

## 4. 実装

### 4.1 パーサーコンビネータライブラリ（unlaxer-common）

`unlaxer-common`モジュールはパーサーコンビネータライブラリを実装する436のJavaソースファイルを含む。パーサーはいくつかのカテゴリに整理される：

**コンビネータパーサー**は他のパーサーを合成する：
- `Chain` / `LazyChain`: 逐次合成（PEGシーケンス `e1 e2 ... en`）。
- `Choice` / `LazyChoice`: 順序付き選択（PEG `e1 / e2 / ... / en`）。
- `LazyZeroOrMore`、`LazyOneOrMore`、`LazyOptional`: 繰り返しとオプション性。
- `LazyRepeat`、`ConstructedOccurs`: 明示的カウント制御による有界繰り返し。
- `Not`: PEG not述語（ゼロ幅の否定先読み）。
- `MatchOnly`: PEG and述語（ゼロ幅の肯定先読み）。
- `NonOrdered`: 順不同セットマッチング（すべての選択肢が任意の順序でマッチしなければならない）。

**LazyバリアントとConstructedバリアント**は再帰的文法のサポートに対処する。再帰的文法（例：`Expression ::= ... '(' Expression ')' ...`）では、`Expression`のパーサーがそれ自身を参照する。即座の構築では無限再帰を引き起こす。`Lazy`バリアントは子パーサーの解決を最初のパースまで遅延させ、サイクルを断つ。`Constructed`バリアントは子が構築時に既知の非再帰ルールに使用される。

**ASTフィルタリング**はパースツリーに表示されるトークンを制御する：
- `ASTNode`: パーサーのトークンがASTに含まれるべきことを示すマーカーインターフェース。
- `ASTNodeRecursive`: このパーサーとその子孫のトークンが含まれる。
- `NotASTNode`と`TagWrapper` with `NotASTNode`: ASTビューからトークンを除外する。
- `Token.filteredChildren`フィールドはトークンツリーのAST専用ビューを提供し、`Token.children`は完全なパースツリーを保持する。

**`ParseContext`のトランザクションベースバックトラッキング**は順序付き選択意味論を実現する：
- `begin(Parser)`: 現在のカーソル位置を保存する（セーブポイントを作成）。
- `commit(Parser, TokenKind)`: パースされたトークンを受け入れカーソルを前進させる。
- `rollback(Parser)`: カーソルをセーブポイントに復元しトークンを破棄する。

このトランザクションモデルはParsecのコミット選択意味論よりも一般的である：任意のパーサーがトランザクションを開始でき、ネストされたトランザクションが完全にサポートされる。

### 4.2 コードジェネレータ（unlaxer-dsl）

`unlaxer-dsl`モジュールは6つのコードジェネレータとサポートインフラストラクチャを含む。

**MapperGenerator**は最も複雑な生成ロジックを処理する。主要な課題は**マルチルールマッピング**である：複数の文法ルールが同じASTクラスにマッピングできる。例えば、`NumberExpression`と`NumberTerm`の両方が`BinaryExpr`にマッピングされる。マッパーはどのルールが特定のパースツリーノードを生成したかを正しく識別し、適切なフィールドを抽出しなければならない。`allMappingRules`メカニズムは`@mapping`クラス名を共有するすべてのルールを収集し、各マッピングルールを順番に試すディスパッチャを生成する。

もう1つの課題は**findDirectDescendants**である：パースツリーから`@left`、`@op`、`@right`を抽出する際、マッパーはネストされたサブ式に降りることなく、アノテーション付き要素にマッチする直接の子を見つけなければならない。`NumberTerm`要素を含む`NumberExpression`はトップレベルの項を抽出すべきであり、その中の因子ではない。

**EvaluatorGenerator**はGGPスケルトンを生成する。網羅的switchディスパッチに加えて、各ノード評価の前後に呼び出される`DebugStrategy`フックを生成する。生成された`StepCounterStrategy`実装は評価ステップをカウントし、特定のステップ数で一時停止するよう設定でき、DAPサーバーのステップオーバー動作を実現する。`@eval`アノテーションが存在する場合、ジェネレータは抽象メソッドの代わりに具象メソッド実装を生成し、具象サブクラスはカスタムロジックを必要とするメソッドのみを選択的にオーバーライドできる。ジェネレータは5種類すべての`@eval`アノテーション（dispatch、direct expression、operator table、literal、delegation）をサポートし、実務で遭遇する評価パターンの大多数をカバーする。

**ParserGenerator**は優先順位と結合性を処理する。`@leftAssoc`が存在する場合、ジェネレータはベース項に続く演算子-項ペアの`LazyZeroOrMore`を持つ`LazyChain`を生成する。`@rightAssoc`の場合は再帰的チェーンを生成する。`@precedence(level=N)`アノテーションは複数の式型が競合する際の選択肢の順序付けに使用される。

### 4.3 新言語機能（v4以降）

v3以降、tinyexpression言語は大幅に拡張された。これらの機能はDGE手法を用いて開発され、反復的な言語進化のためのunlaxerフレームワークの実用的価値を実証する。

#### 4.3.1 Boolean 3階層演算子優先順位

ブール式は`@leftAssoc`と`@precedence`を使用した3レベルの優先順位階層をサポートする：

```ubnf
@mapping(BooleanExpr, params=[left, op, right])
@leftAssoc
@precedence(level=5)
BooleanOrExpression ::= BooleanAndExpression @left
    { OrOp @op BooleanAndExpression @right } ;

@mapping(BooleanExpr, params=[left, op, right])
@leftAssoc
@precedence(level=6)
BooleanAndExpression ::= BooleanXorExpression @left
    { AndOp @op BooleanXorExpression @right } ;

@mapping(BooleanExpr, params=[left, op, right])
@leftAssoc
@precedence(level=7)
BooleanXorExpression ::= BooleanPrimary @left
    { XorOp @op BooleanPrimary @right } ;
```

優先順位の順序は`Or (level=5) < And (level=6) < Xor (level=7)`であり、標準的なブール代数の慣例に一致する。3レベルすべてが同じ`BooleanExpr` ASTノードにマッピングされ、`op`フィールドが演算子を区別する。エバリュエータは`op`に基づいてディスパッチし、正しいブール演算を適用する。`not()`関数は単項前置演算子として実装される：`not(expr)`は引数を評価しブール否定を返す。

#### 4.3.2 数学関数（14関数）

14の数学関数がサポートされ、すべて単一の`FunctionCallExpr` ASTノードを通じてディスパッチされる：

| 関数 | アリティ | 説明 |
|----------|-------|-------------|
| `sin(x)` | 1 | 三角関数の正弦 |
| `cos(x)` | 1 | 三角関数の余弦 |
| `tan(x)` | 1 | 三角関数の正接 |
| `sqrt(x)` | 1 | 平方根 |
| `abs(x)` | 1 | 絶対値 |
| `round(x)` | 1 | 最近傍への丸め |
| `ceil(x)` | 1 | 天井関数 |
| `floor(x)` | 1 | 床関数 |
| `pow(x, y)` | 2 | 冪乗 |
| `log(x)` | 1 | 自然対数 |
| `exp(x)` | 1 | 指数関数 |
| `random()` | 0 | 乱数 [0, 1) |
| `min(a, b, ...)` | 1+（可変引数） | 引数の最小値 |
| `max(a, b, ...)` | 1+（可変引数） | 引数の最大値 |

`min`と`max`は可変引数呼び出しをサポートする：`min($a, $b, $c)`はすべての引数を評価し最小/最大値を返す。文法はカンマ区切りの引数リストに対して`@leftAssoc`を使用し、任意の引数数をサポートする。

#### 4.3.3 必須括弧付き三項式

三項式は「ダングリングelse」の曖昧性を回避するために必須括弧付きでサポートされる：

```ubnf
@mapping(IfExpr, params=[condition, thenExpr, elseExpr])
TernaryExpression ::= '(' Expression @condition '?' Expression @thenExpr ':' Expression @elseExpr ')' ;
```

三項式は`if/else`文と同じ`IfExpr` ASTノードにマッピングされる。この設計決定により、LSPコードアクション（セクション6.4）での`if`と三項形式間の自明な双方向変換が可能になる。両方の表面形式が同一のASTノードを生成するためである。エバリュエータの`evalIfExpr`メソッドは両方の形式を透過的に処理する。

#### 4.3.4 深さ制限なしのStringメソッドドットチェーン

Stringメソッドは型駆動の設計により無制限のドットチェーンをサポートする：

```
$name.toUpperCase().trim().substring(0, 3).toLowerCase()
```

文法は**StringChainable**メソッド（戻り値型がStringで、さらなるチェーンを許可する）と**StringTerminal**メソッド（戻り値型がString以外で、チェーンを終了する）を区別する：

```ubnf
StringMethodChain ::= StringPrimary { '.' StringChainableMethod } [ '.' StringTerminalMethod ] ;
```

後方互換性のために関数形式とドット形式の両方がサポートされる：

```
// 関数形式（レガシー）
toUpperCase($name)

// ドット形式（新）
$name.toUpperCase()

// チェーンされたドット形式
$name.toUpperCase().substring(0, 3)
```

`toNum()`型変換関数は文字列値を数値型に変換し、`$price.trim().toNum() * 1.1`のような式を可能にする。

#### 4.3.5 Not演算子

`not()`演算子はブール否定を提供する：

```
not($enabled)
not($a > $b)
not(isEmpty($name))
```

`not()`はその引数を評価し、ブールに型変換して否定を返す。ブール演算子階層において最高優先順位の単項演算として統合される。

#### 4.3.6 ArgumentExpression（v5で新規）

`ArgumentExpression`ルールは、二重括弧を必要とせずに三項式が関数引数として直接出現することを可能にする。この変更前は、関数呼び出し内の三項式には以下が必要だった：

```
max(($a > 0 ? $a : 0), ($b > 0 ? $b : 0))
```

`ArgumentExpression`により、三項式が関数引数として出現する場合は内側の括弧が不要になる：

```
max(($a > 0 ? $a : 0), ($b > 0 ? $b : 0))    // 引き続き有効
max($a > 0 ? $a : 0, $b > 0 ? $b : 0)          // これも有効に
```

これは引数リスト内で使用されるプロダクションとして`ArgumentExpression`を導入することで実現される。`ArgumentExpression`は引数レベルで三項構文を含み、カンマ区切りの引数リストが文脈によって三項の`:`と引数セパレータを区別できる。文法変更は引数リストルールにローカライズされ、ASTとエバリュエータは変更されない。`ArgumentExpression`は同じ`IfExpr`ノードにマッピングされるためである。

#### 4.3.7 String Predicate（v5で新規）

String predicateメソッド -- `isEmpty`、`isBlank`、`startsWith`、`endsWith`、`contains`、`matches`、`equals` -- は関数形式とドット形式の両方でサポートされ、同じASTを生成する：

```
// 関数形式
isEmpty($name)
startsWith($url, 'https')

// ドット形式
$name.isEmpty()
$url.startsWith('https')
```

両方の形式は同じ`StringPredicateExpr` ASTノードにマッピングされ、表面構文に関わらず一貫した評価を保証する。predicateメソッドはブール値を返し、ブール演算子階層と自然に統合される：

```
$name.isEmpty() || $name.startsWith('N/A')
```

### 4.4 機能パリティインベントリ（v5で新規）

包括的な機能パリティ差分が実施され、レガシーバックエンドとP4バックエンドにわたる**128機能**をインベントリした。各機能は以下のいずれかに分類された：

- **Parity（パリティ）**: 両バックエンドが同一の結果を生成する。
- **P4 only**: P4バックエンドにのみ存在する機能（例：DAPデバッグ、LSPコードアクション）。
- **Legacy only**: レガシーバックエンドにのみ存在する機能（P4実装が必要）。
- **Divergent（乖離）**: 両バックエンドが機能を実装しているが異なる動作を示す（調査が必要）。

インベントリは共存モデルの決定的な追跡ドキュメントとして機能する。レガシーからP4への移行中に機能がサイレントに失われないことを保証し、完全なP4カバレッジの達成に向けた明確なロードマップを提供する。

### 4.5 5つの実行バックエンド

tinyexpressionプロジェクトは、同じ文法から導出された5つの異なる実行バックエンドを実装することで、フレームワークの柔軟性を実証する：

| バックエンド | キークラス | 戦略 | ステップデバッグ | コード生成 |
|---------|-----------|----------|------------|----------|
| `JAVA_CODE` | `JavaCodeCalculatorV3` | レガシーパーサー → Javaソース → JITコンパイル | No | Yes |
| `AST_EVALUATOR` | `AstEvaluatorCalculator` | レガシーパーサー → 手書きAST → ツリーウォーキングエバリュエータ | No | No |
| `DSL_JAVA_CODE` | `DslJavaCodeCalculator` | レガシーパーサー → DSL生成Javaソース | No | Yes |
| `P4_AST_EVALUATOR` | `P4AstEvaluatorCalculator` | UBNF生成パーサー → sealed AST → `P4TypedAstEvaluator` | Yes | No |
| `P4_DSL_JAVA_CODE` | `P4DslJavaCodeCalculator` | UBNF生成パーサー → sealed AST → Javaコードエミッター | Yes | Yes |

P4バックエンド（行4と5）はUBNF生成パーサーとASTを使用し、初期のバックエンドはレガシー手書きパーサーを使用する。パリティ契約がすべてのバックエンドがサポートされている式に対して等価な結果を生成することを保証し、`BackendSpeedComparisonTest`、`P4BackendParityTest`、`ThreeExecutionBackendParityTest`によって検証される。

---

## 5. 評価

### 5.1 ケーススタディ1: tinyexpression

tinyexpressionは金融トランザクション処理においてUser-Defined Function（UDF, ユーザー定義関数）として使用される本番式エバリュエータであり、現在本番環境で**月間10^9（10億）トランザクション**を処理している。設定可能な精度（float、double、int、long、BigDecimal、BigInteger）の数値式、無制限のドットチェーンメソッドによる文字列式、3レベル演算子階層（Or < And < Xor）によるブール式、条件式（if/else）、必須括弧付き三項式（ArgumentExpressionにより関数引数で二重括弧が不要）、パターンマッチング（match）、14の数学関数（sin、cos、tan、sqrt、min、max、random、abs、round、ceil、floor、pow、log、exp）（min/maxの可変引数サポート付き）、変数バインディング、ユーザー定義メソッド、型ヒント、`not()`演算子、`toNum()`型変換、String predicate（isEmpty、isBlank、startsWith、endsWith、contains、matches、equals）の関数形式とドット形式の両方、および埋め込みJavaコードブロックをサポートする。機能パリティインベントリは**128機能**をバックエンド間で追跡する。

UBNF文法（`tinyexpression-p4-complete.ubnf`）は**580行の文法仕様**にわたり、完全なP4文法を定義する。この文法から、コードジェネレータは6つの生成ファイルにわたり約**2,200行**のJavaソースを生成する。手書きのエバリュエータロジック（`P4TypedAstEvaluator.java`）は**590行のエバリュエータメソッド（`evalXxx`）**であり、新しいブール演算子、数学関数、三項式、Stringメソッドチェーン、型変換を含むすべての式型をカバーする。

開発者が保守する総投資 -- 文法と手書きエバリュエータロジック -- は約**1,170行**（580文法 + 590エバリュエータ）であり、パーサー、型付きAST、マッパー、エバリュエータ、LSPサーバー、DAPサーバーを含む完全な言語実装を生成する。約2,200行の生成コードは自動的に生成され開発者が保守しないため、このカウントには含まれない。

### 5.2 性能ベンチマーク

**テスト環境。** すべてのベンチマークは以下のプラットフォームで実行された：JDK 21（Eclipse Temurin 21.0.2+13）、Ubuntu 22.04 LTS、AMD Ryzen 9 5950X（16コア/32スレッド、3.4 GHzベース/4.9 GHzブースト）、64 GB DDR4-3200 RAM、デフォルトG1GCガベージコレクタ（カスタムチューニングなし）。

`BackendSpeedComparisonTest`は式`3+4+2+5-1`（リテラル算術）と`$a+$b+$c+$d-$e`（変数算術）を使用して、5,000回のウォームアップ後50,000回の反復でバックエンド間の評価性能を測定する。

**セクション1: リテラル算術**

| バックエンド | 説明 | us/call | vs. ベースライン |
|---------|-------------|---------|--------------|
| (A) compile-hand | JVMバイトコード (JavaCodeCalculatorV3) | ~0.04 | 1.0x |
| (E2) P4-typed-reuse | Sealed switch、インスタンス再利用 | ~0.10 | 2.8x |
| (E) P4-typed-eval | Sealed switch、呼び出しごとに新インスタンス | ~0.33 | 8.9x |
| (B) ast-hand-cached | 手書きAST、パース済み | ~0.42 | 11.4x |
| (C) ast-hand-full | 手書きAST、パース+構築+評価 | ~2.50 | ~68x |

*Table 3: リテラル算術の評価レイテンシ。compile-handバックエンドは理論上の最適値（JITコンパイルされたJavaバイトコード）を表す。P4-typed-reuseはこの最適値の3倍以内。文法+手書きコードのみ。生成コード（~2,200行）は開発者保守ではないためLOCカウントから除外。*

主要な結果は、P4-typed-reuseバックエンドがcompile-handベースラインの**2.8倍**を達成しつつ、コンパイラではなくインタプリタであるということである。これは注目に値する：sealed-switchエバリュエータは、ツリーウォーキング解釈を行いながらも、単純な式ではJITコンパイルコードと競合的である。JVMのJITコンパイラはswitchケースをインライン化し、仮想ディスパッチを排除し、レコードインスタンスにスカラー置換を適用できる。

手書きASTエバリュエータ（ast-hand-cached）はコンパイルコードの11.4倍遅く、sealed-interfaceアプローチ（2.8倍）が両方がパーシングオーバーヘッドを回避する場合でもアノテーション駆動ツリー評価に対して意味のある性能優位性を提供することを実証する。

**セクション2: 変数算術**

| バックエンド | 説明 | us/call | vs. ベースライン |
|---------|-------------|---------|--------------|
| (F) compile-hand | 変数ルックアップ付きJVMバイトコード | ~0.06 | 1.0x |
| (H) P4-typed-var | 変数AST付きSealed switch | ~0.15 | 2.5x |
| (G) AstEvalCalc | 完全なAstEvaluatorCalculatorパス | ~8.50 | ~142x |

変数式も同様の相対性能を示す。P4-typedバックエンドはJITコンパイルコードに対して2.5倍のオーバーヘッドを維持し、sealed-switch評価が式型間で一貫してスケールすることを確認する。

### 5.3 ケーススタディ2: 回文認識

unlaxerの文脈自由を超えるパーシング能力を実証し、tinyexpression以外の2番目のケーススタディを提供するために、`MatchedTokenParser`を使用した回文認識を提示する。回文言語`L = { w w^R | w in Sigma* }`はPEGまたは文脈自由文法では認識できない正規の文脈依存言語である。

5つの異なる回文認識器を実装した（セクション3.8）。すべて同じ`MatchedTokenParser`インスタンスを異なる操作で使用する。5つの実装すべてが`Usage003_01_Palidrome.java`で以下のテストベクトルに対してテストされる：

| 入力 | 期待結果 | 5つの実装すべてが一致 |
|-------|----------|----------------------------|
| "a" | マッチ | Yes |
| "abcba" | マッチ（奇数長） | Yes |
| "abccba" | マッチ（偶数長） | Yes |
| "aa" | マッチ（自明） | Yes |
| "ab" | マッチせず | Yes |

5つの実装は、明示的なインデックス操作（`sliceWithWord`）から最大限に簡潔なpythonian記法（`slicer.pythonian("::-1")`）まで、スペクトルにわたる。この多様性は、MatchedTokenParserが正確性を維持しつつ多様なプログラミングスタイルに十分な表現力を提供することを実証する。

回文ケーススタディは2つの主張を検証する：(1) MatchedTokenParserがunlaxerの認識能力を文脈自由言語を超えて拡張する、(2) コンビネータレベルのアプローチがJavaのテストインフラ（JUnit）とデバッグツール（標準IDEデバッガで各`slice`/`effect`操作をステップスルーできる）に自然に統合される。

### 5.4 開発工数の比較

3つのアプローチにおけるtinyexpressionの開発工数を報告する。これらの数値はケーススタディから得られたものであり、tinyexpressionの機能セットと複雑さを持つ言語の代表値として解釈されるべきであり、普遍的に一般化可能な主張としてではない。

| アプローチ | 文法行数 | 手書きロジック | 開発者保守行数合計 | 観測された工数 |
|----------|--------------|-------------------|--------------------------------|-----------------|
| スクラッチ（パーサー + AST + マッパー + エバリュエータ + LSP + DAP） | N/A（文法DSLなし） | ~15,000 | ~15,000 | ~8週間 |
| ANTLR + 手書きエバリュエータ + 手書きLSP/DAP | ~200（ANTLR文法） | ~7,800 | ~8,000 | ~5週間 |
| unlaxer（文法 + evalXxxメソッド） | 580（UBNF） | 590（evalXxx） | 1,170 | ~3日 |

*Table 4: ケーススタディで観測された開発工数比較。「開発者保守行数合計」は開発者が記述・保守する文法と手書きコードのみをカウントする。unlaxerアプローチの生成コード（~2,200行）は自動生成され開発者保守ではないため、すべてのLOCカウントから除外される。580行の文法（UBNF仕様）と590行の手書きエバリュエータロジック（P4TypedAstEvaluator.javaのevalXxxメソッド）は別々にカウントされ合計される。*

「スクラッチ」推定はパーサー（~2,000行）、AST型（~1,500行）、マッパー（~1,000行）、エバリュエータ（~2,000行）、LSPサーバー（~2,500行）、DAPサーバー（~1,500行）の内訳に基づく。ANTLR推定は生成パーサーとAST（パーサーとAST工数の削減）を考慮するが、手書きマッパー、エバリュエータ、LSP、DAPを必要とする。unlaxerの数値は実際のtinyexpression実装を反映する：580行のUBNF文法仕様と590行の手書きエバリュエータロジック（P4TypedAstEvaluator.javaのevalXxxメソッド）。

保守可能なコードの**13倍の削減**（~15,000から~1,170行）がケーススタディで観測された主要な実用的利益である。しかし、認知負荷の削減はおそらくより重要である：開発者はパーサー配管、トークンツリー走査、プロトコルメッセージハンドリングではなく、文法ルールと評価意味論の観点で思考する。

### 5.5 本番デプロイメント

tinyexpressionは金融トランザクション処理システム内のUDF（User-Defined Function）として本番デプロイされている。システムは**月間10^9（10億）トランザクション**を処理し、tinyexpressionは分類、ルーティング、導出値計算のために各トランザクションでユーザー定義式を評価する。

主要な本番メトリクス：
- **トランザクション量**: 10^9/月（持続的に~385評価/秒、バーストピークは大幅に高い）。
- **式の複雑さ**: 本番の式は単純な算術（`$amount * 1.1`）からブール演算子、数学関数、メソッド呼び出しを含む複雑な条件ロジック（式あたり50以上のASTノード）まで多岐にわたる。一部のmatch式は約23KBの数式テキストにわたる470のマッチケースを含む。
- **本番バックエンド**: P4-typed-reuseバックエンド（インスタンス再利用付きsealed-switchエバリュエータ）が本番で使用され、典型的な式に対してサブマイクロ秒の評価レイテンシを達成。
- **信頼性**: sealed-interface網羅性保証により、文法変更がサイレントな評価失敗を導入できないことが保証される -- 欠落する評価メソッドはデプロイメント前のコンパイル時にキャッチされる。

### 5.6 テストスイート

テストスイートはv4以降大幅に成長した。現在の状態は：

- **tinyexpression**: **445テスト**、すべてグリーン（v4の434から増加）。5つの実行バックエンドすべて、バックエンド間のパリティ契約、すべての式型、DGEで発見されたエッジケース、性能ベンチマークをカバー。
- **unlaxer-parser**: **550以上のテスト**、すべてグリーン。パーサーコンビネータライブラリ、コード生成パイプライン、UBNFパーサー、マッパー生成、エバリュエータ生成、LSP機能、回文ケーススタディをカバー。

**995以上のテスト**の統合テストスイートは、両リポジトリにわたる包括的な回帰カバレッジを提供する。

### 5.7 DGEセッション結果

開発期間中に10回のDGEセッションが実施され、文法ルール、パーサー動作、AST構築、評価ロジック、IDE統合の間の相互作用を体系的に探索した。セッションは**201以上のギャップ**を発見し、その大多数は解決済みである。

DGEセッションからの主要な発見：

1. **ブール演算子と比較演算子の相互作用**: DGEセッション2で、比較演算子がブール演算子と同じ優先順位を持っていたために`$a > 0 && $b < 10`が不正にパースされることが判明した。3レベルBoolean階層（セクション4.3.1）はこれを解決するために設計された。

2. **三項式の曖昧性**: DGEセッション3で、`$a > 0 ? $b : $c + 1`が必須括弧なしでは曖昧であることが発見された。`:`が三項のセパレータまたはmatch-caseセパレータとして解釈されうるためである。必須括弧設計（セクション4.3.3）が採用された。

3. **Stringメソッド戻り値型チェーン**: DGEセッション4で、`$s.length().toString()`が有効であるべき（数値メソッドが数値を返し、それが文字列に変換される）にもかかわらず、パーサーがチェーン内でString返却メソッドのみを許可していたためにこれを拒否していることが特定された。StringChainable/StringTerminal区別（セクション4.3.4）がこれに対処するために改善された。

4. **可変引数min/max評価**: DGEセッション5で、`min($a, $b, $c)`が正しくパースされるがエバリュエータが2引数ケースのみを処理していることが判明した。可変引数評価ロジックが直接の結果として追加された。

5. **ArgumentExpressionの二重括弧**: DGEセッション7で、`max(($a > 0 ? $a : 0), ($b > 0 ? $b : 0))`が不必要な二重括弧を必要としていることが特定された。ArgumentExpressionルール（セクション4.3.6）がこの使い勝手のギャップを解決するために導入された。

6. **String predicate形式の不一致**: DGEセッション8で、`isEmpty($name)`は動作するが`$name.isEmpty()`は動作しないことが発見された。両方が自然な構文であるにもかかわらず。二重形式のpredicateサポート（セクション4.3.7）が追加された。

### 5.8 LLM支援開発

unlaxer-parserの型安全な生成アーキテクチャは、LLM支援開発ワークフローに定性的な利益を提供する。我々の経験は以下の観察を示唆する：

**トークン効率。** フレームワークなしでは、LLMはパーサーコンビネータ、AST型、マッパーロジック、エバリュエータコードをスクラッチから生成しなければならず、通常相当なコンテキストと生成トークンを必要とする。unlaxerでは、LLMは`evalXxx`メソッド本体のみを生成すればよく、トークン予算を大幅に削減する。

**ガイダンスとしての型安全性。** sealed interface網羅性保証により、LLMがASTノード型の処理を「忘れる」ことができない -- Javaコンパイラがコードを拒否する。文法が新しい`@mapping`ルールで拡張されると、生成されたエバリュエータベースクラスが新しい抽象メソッドを獲得する。未実装メソッドをリストするコンパイラエラーは、LLMが追加のプロンプティングなしに従える正確で機械可読なTODOリストとして機能する。この「コンパイラ・アズ・オーケストレーター」パターンは、LLMに必要な情報（どのメソッドをどのパラメータ型で実装するか）を正確に提供し、余分なものは何も提供しないため効果的である。

**LLM互換手法としてのDGE。** DGE手法（セクション3.9）は対話駆動の探索形式がLLMの会話ターンに自然にマッピングされるため、LLM支援開発に特に適している。LLMは敵対的テスターとして機能し、体系的にエッジケースを生成しギャップを特定できる。

LLM支援開発の利益（例：トークン使用量とタスク完了時間を測定する統制実験）の厳密な評価は将来の課題として残ることに留意する。

---

## 6. 議論

### 6.1 エラーリカバリ：SyncPointRecoveryParser

PEGの順序付き選択意味論はエラーリカバリに対する根本的な課題を提示する：選択肢の代替が失敗すると、パーサーはバックトラックして次の代替を試み、「部分マッチ」を報告したり、エラーのある入力をスキップしてパーシングを継続するメカニズムがない。

unlaxer-parserはこの課題を多層的なエラーリカバリ設計で対処し、現在は実装済みの`SyncPointRecoveryParser`を含む：

**レイヤー1: 正確なエラーレポートのためのErrorMessageParser。** `ErrorMessageParser`（セクション3.4）は期待されるトークン情報をパースツリーに埋め込むことで、障害点での診断を提供する。パーサーが失敗すると、エラーレポートシステムは最深の進行点とその位置での期待されるトークンのセットを特定し、ジェネリックな「parse failed」ではなく「Expected expression after '+'」のようなエラーメッセージを生成する。

**レイヤー2: 部分パース成功。** `CalculatorDemo`と本番デプロイメントは既に部分パース成功を実証している：パーサーが完全な入力のマッチに失敗した場合、正常にパースされたプレフィックスがトークンツリーに保持される。エバリュエータは部分ASTを評価でき、LSPサーバーはパースされなかったサフィックスに対する診断を提供できる。これは多くのインタラクティブ編集シナリオに十分である。

**レイヤー3: SyncPointRecoveryParser（実装済み）。** `SyncPointRecoveryParser`は`@recover`アノテーションで指定されたシンクポイントでのエラーリカバリを実装する：

```ubnf
@recover(syncTokens=[';', '}'])
Statement ::= IfStatement | Assignment | Expression ';' ;
```

`Statement`のパーシングが失敗すると、`SyncPointRecoveryParser`は次のシンクトークン（`;`または`}`）まで前方にスキャンし、スキップされた入力をエラーノードでラップし、シンクポイントでパーシングを再開する。これはANTLRの「パニックモード」リカバリに類似しているが、文法で宣言的に指定される。ANTLRのアプローチとの主要な違いは、リカバリポイントが言語設計者によって`@recover`で明示的に宣言され、固定のランタイムヒューリスティックに依存するのではなく、リカバリが発生する場所を正確に制御できることである。

実装はLSPサーバーの診断レポートと統合される：スキップされた領域は元のパース失敗メッセージ付きの診断エラーとして報告され、リカバリポイントが示される。これにより開発者はエラー位置とパーシングが再開された点の両方を確認できる。

この実装は、ANTLRのエラーリカバリ優位性について以前のレビューで提起された懸念に直接対処する。ANTLRの組み込みリカバリ戦略はより広範なリカバリシナリオを自動的に処理するが、unlaxerの宣言的`@recover`アプローチは言語設計者に明示的な制御と予測可能性を与える。Table 1のエラーリカバリ列は「Designed」ではなく「Yes」にマークされた。

### 6.2 インクリメンタルパーシング：IncrementalParseCache

現在のパーサーは変更のたびに入力全体を再パースする。LSP統合に対して、典型的な数式サイズ（1KB未満）ではこれは許容される。しかし、本番の数式は大きなサイズに達することがある -- 約23KBにわたる470ケースのmatch式。

**ランタイム（本番）:** 本番システムはコンパイル済みバイトコード（P4-typed-reuseバックエンド）を使用する。数式は一度パースされコンパイルされ、その後何百万回も評価される。パーシングはトランザクション処理中ではなく数式デプロイメント中にのみ発生するため、パーシング性能はボトルネックではない。

**LSP（エディタ）:** LSPサーバーはリアルタイムの診断と補完のためにキーストロークごとにパースする。大きな数式では、入力全体の再パースが目立つレイテンシを導入する。

**IncrementalParseCache（実装済み）。** `IncrementalParseCache`はLSPサーバーを特にターゲットにしたチャンクベースキャッシングを実装する。入力はカンマとセミコロンの境界（match式の自然な区切り文字）でチャンクに分割される。入力が変更されると、キャッシュは編集の影響を受けるチャンクを特定し、それらのチャンクのみを再パースし、変更されていないチャンクにはキャッシュされたパース結果を再利用する。

470ケースのmatch式の測定結果：
- **キャッシュヒット率**: 単一文字編集で99%超（470のうち1ケースを編集）。
- **再パース範囲**: 影響を受けるチャンクのみ（通常30〜50文字）が再パースされ、23KB全体の数式ではない。
- **レイテンシ削減**: 大きなmatch式に対して完全再パースと比較して10〜50倍の再パース時間削減。

99%超のキャッシュヒット率は、各ケースへの単一文字編集をシミュレートし、キャッシュから提供できるチャンクの割合を測定することで、470のすべてのマッチケースにわたって測定された。この結果は、チャンクベースキャッシングがtinyexpressionの本番ワークロードを特徴づけるmatch式中心の数式に対して高度に効果的であることを確認する。

### 6.3 制限事項

**Java専用生成。** 現在のコードジェネレータはJavaソースファイルのみを生成する。JVMベース言語（Kotlin、Scala、Clojure）は生成されたJavaコードと相互運用できるが、非JVM言語のネイティブサポートは利用できない。文法表記（UBNF）は言語非依存であるが、ジェネレータパイプラインとパーサーコンビネータランタイムはJava固有である。

**PEGベースパーシング。** UBNFはPEGセマンティクス（順序付き選択）を使用しており、曖昧な文法は代替の順序によって決定論的に解決される。これはほとんどのDSLにとっては特徴であるが、曖昧性レポートを必要とする言語（例：自然言語処理）や順序付き選択が驚くべき結果を生む言語にとっては制限である。曖昧な文法を扱いパースフォレストを生成できるGLRパーサーはサポートされていない。

**単一の本番ユーザー。** tinyexpressionプロジェクトがフレームワークの主要な本番ユーザーである。回文ケーススタディはフレームワークの文脈依存認識タスクへの適用可能性を実証するが、多様なDSLプロジェクトにわたるサードパーティ開発者による広範な検証が、設計選択の汎用性を確認するために必要である。

**不完全な文法カバレッジ。** P4文法はまだtinyexpression言語のすべての機能をカバーしていない。いくつかの構成要素 -- 外部Javaメソッド呼び出し、文字列スライシング（`$msg[0:3]`）、一部の高度な文字列メソッド -- はレガシーパーサーによって処理され、以前のバックエンドにフォールバックする。共存モデル（P4パーサーとレガシーフォールバック）は機能するが複雑さを追加する。P4フォールバックロギング（セクション6.5）はどの式がレガシーバックエンドにフォールバックするかへのオブザーバビリティを提供する。

**ベンチマーク手法。** セクション5.2の性能ベンチマークはJMH（Java Microbenchmark Harness）ではなくカスタムベンチマークハーネス（`BackendSpeedComparisonTest`）を使用している。ウォームアップ反復とリピート測定を使用しているが、JMHはJITコンパイル、ガベージコレクション、プロセス分離に対してより厳密な制御を提供する。近似表記（~0.10 us/call）はこの制限を反映している。JMHベースのベンチマークを将来の改訂で追加する計画である。オーダーの関係（リフレクションからsealed-switchの1,400倍改善、sealed-switch対JITコンパイルの2.8倍オーバーヘッド）はJMH測定でも維持されると考えるが、正確な数値は変動する可能性がある。

### 6.4 LSP機能

#### 6.4.1 リファクタリングコードアクション

生成されたLSPサーバーは、統一された文法からIDEパイプラインの価値を実証するリファクタリングコードアクションを提供する：

**If/三項双方向変換。** `if/else`文と三項式（`(cond ? then : else)`）の両方が同じ`IfExpr` ASTノードにマッピングされるため、2つの形式間の変換はLSPレベルで自明である：コードアクションはターゲット形式のシリアライゼーションを使用して共有ASTノードから表面構文を再構築する。開発者は`if`式を選択して三項に変換でき、またその逆も単一のコードアクションで可能。この変換の正確性は共有AST表現によって保証される -- 2つの形式間に意味的な差異はない。

**Ifチェーンからmatch変換。** `if/else if/else`式のチェーンは、条件がパターン（同じ変数を異なる値と比較）に従う場合、`match`式に変換できる。コードアクションはAST内でこのパターンを検出し、比較変数とケース値を抽出し、等価な`match`式を生成する。この変換はケース数が3〜4を超える場合の可読性に有用である。

これらのコードアクションは文法アノテーションから生成され、手書きのLSPロジックを必要としない。これらは統一パイプラインの主要な利点を示す：従来のLSP実装では数百行の手書きコードを必要とするリファクタリング操作が、文法構造から自動的に導出される。

#### 6.4.2 FormulaInfo Phase 1（v5で新規）

FormulaInfo LSP Phase 1はLSPサーバーに3つのメタデータ駆動機能を追加する：

**メタデータ補完。** FormulaInfoは数式名、説明、著者、バージョン、依存関係などの数式レベルメタデータを構造化アノテーションとして提供する。LSPサーバーはこれらのメタデータフィールドの補完候補を、`@catalog`およびプロジェクトレベルの設定から生成する。

**dependsOnバリデーション。** 数式が`dependsOn`アノテーションで他の数式への依存を宣言する場合、LSPサーバーは参照されたすべての数式が存在しアクセス可能であることを検証する。欠落または無効な依存は診断警告として報告され、壊れた依存チェーンの早期検出を可能にする。

**Go-to-definition。** LSPサーバーは`dependsOn`宣言の数式参照と、他の数式に解決される変数参照に対するgo-to-definitionをサポートする。Ctrl+Click（または等価なエディタジェスチャー）はターゲット数式の定義にナビゲートし、シームレスなクロス数式ナビゲーション体験を提供する。

これらの機能は同じメタデータ搬送パースツリーメカニズム（セクション3.4）によって駆動される：FormulaInfoメタデータは`ContainerParser<FormulaInfo>`インスタンスを介してパースツリーに埋め込まれ、単一パースパス中にLSPサーバーによって抽出される。

### 6.5 P4フォールバックロギング（v5で新規）

共存モデル -- P4パーサーがほとんどの式を処理するが未サポートの構成要素にはレガシーバックエンドにフォールバックする -- は移行進捗を追跡するためのオブザーバビリティを必要とする。P4フォールバックロギングは各フォールバックイベントを以下とともに記録する：

- フォールバックをトリガーした式テキスト。
- P4パース失敗理由（どの文法ルールがどの位置で失敗したか）。
- 式を処理したレガシーバックエンド。
- トレンド分析のためのタイムスタンプ。

このロギングデータは2つの目的に役立つ：(1) 最も一般的なフォールバックトリガーを特定し、P4文法拡張の優先順位付けを導く、(2) 移行進捗の定量的尺度を提供する（P4対レガシーで処理される本番式の割合）。目標はフォールバック率をゼロに削減し、その時点でレガシーバックエンドを退役させることである。

### 6.6 MatchedTokenParserの認識能力

`MatchedTokenParser`はunlaxerの認識能力を文脈自由言語を超えて拡張する（回文ケーススタディで実証、セクション5.3）。自然な質問は：MatchedTokenParserの認識能力の上限は何か？

`slice`操作に限定した場合 -- start、end、stepパラメータを使用して以前にマッチしたコンテンツのサブ範囲を抽出する -- 認識能力は少なくとも、有界長コンテンツ比較と再配置で特徴づけられる文脈依存言語のクラスに拡張される。回文言語`L = { w w^R | w in Sigma* }`とXMLタグペアリングはこのクラスの正規の例である。スライスベースのキャプチャ・アンド・リプレイで拡張されたPEGが認識する正確な言語クラスの形式的特徴づけは未解決の問題であり、将来の理論的研究の方向性である。

任意のJava関数による`effect`操作が許可される場合、`effect`関数がキャプチャされたコンテンツに対して任意の計算を実行できるため、認識能力は原理的にチューリング完全（Turing-complete）になる。これはパーサージェネレータ（例：ANTLRの埋め込みアクション、Yaccのアクションコード）のセマンティックアクションがパーサージェネレータをチューリング完全にしうるのと類似している。実務では、unlaxer文法で使用される`effect`操作は単純な変換（逆転、大小文字変換、部分文字列抽出）に限定され、理論的なチューリング完全性は実用上の懸念を提示しない。

### 6.7 妥当性への脅威

**内的妥当性。** 性能ベンチマークはJMHではなくカスタムテストハーネスを使用して実施された。ハーネスにはウォームアップ反復（5,000）と測定反復（50,000）が含まれるが、JVMフォーク分離、GC圧力、JITティアードコンパイルをJMHの厳密さで制御していない。近似表記（~0.10 us/call）はこの制限を反映している。加えて、Table 4の開発工数推定は著者のunlaxerと従来のアプローチ両方の経験に基づいており、フレームワークに精通していない開発者には一般化されない可能性がある。

**外的妥当性。** 評価は2つのケーススタディに基づく：tinyexpression（本番式エバリュエータ）と回文認識（文脈依存パターン）。両方ともフレームワークの著者が開発し、unlaxerの設計に深い知識を持つ。サードパーティによるDSL実装がフレームワークの汎用性と使いやすさのより強力な証拠を提供するだろう。工数削減の主張はケーススタディからの観察として解釈されるべきであり、普遍的な予測としてではない。

**構成概念妥当性。** コード行数が開発工数の主要な指標として使用され、時間推定で補完されている。LOCはコード品質、保守性、テストカバレッジを捕捉しない粗い指標である。「スクラッチ」のLOC推定は実際の実装ではなく予測である。

### 6.8 将来の課題

**JMHベンチマーク。** 性能ベンチマークを`@Benchmark`、`@Warmup(iterations=10, time=1s)`、`@Measurement(iterations=10, time=1s)`、`@Fork(3)`を使用したJMHに移行し、平均、標準偏差、99パーセンタイルレイテンシを報告する計画である。

**追加のケーススタディ。** サードパーティによるDSL実装を通じた外部検証が将来の評価の主要なターゲットである。合成文法ベンチマーク（ルール数、再帰深度、`@mapping`密度を変化）もスケーラビリティデータを提供するだろう。

**FormulaInfo Phase 2。** find-references（所与の数式に依存するすべての数式）、rename refactoring（数式リネーム時のすべてのdependsOn参照の更新）、依存グラフ可視化でFormulaInfoを拡張する。

**多言語コード生成。** UBNF文法からTypeScript、Python、Rustソースを生成することで、JVMエコシステムを超えてフレームワークの適用可能性を拡張する。

**圏論的形式化。** PropagationStopper階層の圏論的定式化 -- パーサー状態の適切な圏における自己準同型としてストッパーを特徴づける -- は将来の理論的研究の興味深い方向性であるが、セクション3.6の操作的意味論はストッパーの振る舞いについての実用的推論に十分であることに留意する。

**トランザクション意味論。** ParseContextのbegin/commit/rollbackトランザクションモデルの操作的意味論の形式化は、セクション3.6のPropagationStopper意味論を補完し、パーサーの状態管理のより完全な形式的説明を提供するだろう。

---

## 7. 結論

unlaxer-parserを提示した。これはJava 21フレームワークであり、単一のUBNF文法仕様から6つの相互関連するアーティファクト -- パーサー、AST型、パースツリーからASTへのマッパー、エバリュエータスケルトン、LSPサーバー、DAPサーバー -- を生成する。本フレームワークはDSL開発の根本的な問題に対処する：互いに整合を保たなければならない複数の密結合したサブシステムを構築・保守する必要性。

4つの主要貢献はこの統一生成における特定の課題に対処する：

1. **伝播制御**は、4つの伝播ストッパークラスの階層を通じて、2つの直交する次元（`TokenKind`と`invertMatch`）上で動作する、パーシングモードのコンビネータツリーを通じた伝播に対する細粒度の合成的制御を提供する。形式的な操作的意味論（セクション3.6）を提供し、ストッパー階層の代数的性質を実証し、Readerモナドの`local`との正確な対応（セクション3.7）を示した。この貢献の価値は、モナディック抽象化自体 -- よく知られている -- にあるのではなく、パーサーコンビネータの特定の設計パターンとしての同定、Javaにおけるファーストクラスのチューリング完全なAPIとしての実現、およびコード生成パイプラインへの統合にある。

2. **メタデータ搬送パースツリー**は`ContainerParser<T>`を通じて、エラーメッセージと補完候補を単一パースパス中にパースツリーに直接埋め込むことを可能にする。これはWriterモナドの`tell`操作に対応する。実用的な利益は、LSP機能がASTと同じパースパスから導出され、整合性が保証されることである。

3. **エバリュエータ向けGeneration Gap Pattern**は、Java 21のsealed interfaceと網羅的switch式と組み合わせることで、コンパイラによる完全性保証を提供する。文法が新しいASTノード型を追加すると、コンパイラは対応する評価メソッドを開発者が実装するまで手書きエバリュエータを拒否する。

4. **MatchedTokenParser**はフレームワークの認識能力を文脈自由言語を超えて拡張し、コンビネータレベルで回文認識とXMLタグペアリングを可能にする。Macro PEG [Mizushima 2016] に着想を得て、MatchedTokenParserは合成可能なslice、effect、利便性操作によるキャプチャ・アンド・リプレイ意味論を提供する。

加えて、宣言的評価仕様のための**`@eval`アノテーション**（5種類の生成評価付き）、**Design-Gap Exploration（DGE）**手法（10セッション、201以上のギャップ発見）、エラーリカバリのための**SyncPointRecoveryParser**は、DSL開発実践への重要な方法論的・工学的貢献を表す。

評価は実用的なインパクトを実証する。金融トランザクション処理のUDFとして**月間10^9トランザクション**を処理する本番式エバリュエータであるtinyexpressionは、Boolean 3レベル演算子階層、14の数学関数、三項式（二重括弧排除のためのArgumentExpressionを含む）、無制限のStringメソッドドットチェーン、二重形式のString predicate、128機能パリティインベントリにより大幅に拡張された。スクラッチ実装と比較して保守可能コードの**13倍の削減**（~15,000から~1,170行）を達成する。性能ベンチマークはリフレクションベースからsealed-switch評価への**1,400倍の改善**を示し、sealed-switchエバリュエータはJITコンパイルバイトコードの**2.8倍**以内で動作する。10回のDGEセッションは201以上の仕様-実装ギャップを発見し、DSL開発における体系的ギャップ探索の価値を実証した。SyncPointRecoveryParserは文法で宣言的に指定されるANTLR匹敵のエラーリカバリを提供する。IncrementalParseCacheは本番サイズの数式に対してLSPサーバーの99%超のキャッシュヒット率を達成する。FormulaInfo Phase 1はメタデータ補完、依存バリデーション、go-to-definitionを提供する。**445のtinyexpressionテストと550以上のunlaxerテスト**（合計995以上）の統合テストスイートがすべてグリーンであり、包括的な回帰カバレッジを提供する。

本フレームワークはMITライセンスのもとオープンソースソフトウェアとして利用可能であり、Maven Centralに`org.unlaxer:unlaxer-common`および`org.unlaxer:unlaxer-dsl`として公開されている。

---

## 参考文献

[1] Bettini, L. 2016. *Implementing Domain-Specific Languages with Xtext and Xtend*. 2nd Edition. Packt Publishing.

[2] Brunel, M., Clem, M., Hlywa, T., Creager, P., and Gonzalez, A. 2023. tree-sitter: An incremental parsing system for programming tools. In *Proceedings of the ACM SIGPLAN International Conference on Software Language Engineering (SLE '23)*.

[3] Erdweg, S., Storm, T., Volter, M., Boersma, M., Bosman, R., Cook, W. R., Gerber, A., Hulshout, A., Kelly, S., Loh, A., Konat, G. D. P., Molina, P. J., Palatnik, M., Poetzsch-Heffter, A., Schindler, K., Schindler, T., Solmi, R., Vergu, V., Visser, E., van der Vlist, K., Wachsmuth, G. H., and van der Woning, J. 2013. The State of the Art in Language Workbenches. In *Software Language Engineering (SLE '13)*, pp. 197--217.

[4] Ford, B. 2004. Parsing Expression Grammars: A recognition-based syntactic foundation. In *Proceedings of the 31st ACM SIGPLAN-SIGACT Symposium on Principles of Programming Languages (POPL '04)*, pp. 111--122. ACM.

[5] Gamma, E., Helm, R., Johnson, R., and Vlissides, J. 1994. *Design Patterns: Elements of Reusable Object-Oriented Software*. Addison-Wesley.

[6] Hutton, G. and Meijer, E. 1998. Monadic Parsing in Haskell. *Journal of Functional Programming* 8, 4, pp. 437--444.

[7] Ierusalimschy, R. 2009. A text pattern-matching tool based on Parsing Expression Grammars. *Software: Practice and Experience* 39, 3, pp. 221--258.

[8] Johnson, S. C. 1975. Yacc: Yet Another Compiler-Compiler. *AT&T Bell Laboratories Technical Report*.

[9] Kats, L. C. L. and Visser, E. 2010. The Spoofax Language Workbench: Rules for Declarative Specification of Languages and IDEs. In *Proceedings of the ACM International Conference on Object-Oriented Programming, Systems, Languages, and Applications (OOPSLA '10)*, pp. 444--463. ACM.

[10] Leijen, D. and Meijer, E. 2001. Parsec: Direct Style Monadic Parser Combinators For The Real World. *Technical Report UU-CS-2001-35*, Department of Computer Science, Universiteit Utrecht.

[11] Might, M., Darais, D., and Spiewak, D. 2011. Parsing with Derivatives: A Functional Pearl. In *Proceedings of the 16th ACM SIGPLAN International Conference on Functional Programming (ICFP '11)*, pp. 189--195. ACM.

[12] Microsoft. 2016a. Language Server Protocol Specification. https://microsoft.github.io/language-server-protocol/

[13] Microsoft. 2016b. Debug Adapter Protocol Specification. https://microsoft.github.io/debug-adapter-protocol/

[14] Mizushima, K. 2016. Macro PEG: PEG with macro-like rules. Blog post and implementation. https://github.com/kmizu/macro_peg

[15] Mizushima, K., Maeda, A., and Yamaguchi, Y. 2010. Packrat parsers can handle practical grammars in mostly constant space. In *PASTE '10: Proceedings of the ACM SIGPLAN-SIGSOFT Workshop on Program Analysis for Software Tools and Engineering*, pp. 29--36. ACM.

[16] Parr, T. and Fisher, K. 2011. LL(*): the foundation of the ANTLR parser generator. In *Proceedings of the 32nd ACM SIGPLAN Conference on Programming Language Design and Implementation (PLDI '11)*, pp. 425--436. ACM.

[17] Parr, T. 2013. *The Definitive ANTLR 4 Reference*. Pragmatic Bookshelf.

[18] Redziejowski, R. R. 2007. Parsing Expression Grammars: A Recognition-Based Syntactic Foundation. *Fundamenta Informaticae* 85, 1-4, pp. 413--431.

[19] Swierstra, S. D. 2009. Combinator Parsing: A Short Tutorial. In *Language Engineering and Rigorous Software Development*, Lecture Notes in Computer Science 5520, pp. 252--300. Springer.

[20] Vlissides, J. 1996. Generation Gap. In *Pattern Languages of Program Design 3*, Addison-Wesley, pp. 85--101.

[21] Volter, M., Stahl, T., Bettin, J., Haase, A., and Helsen, S. 2006. *Model-Driven Software Development: Technology, Engineering, Management*. John Wiley & Sons.

---

## 付録A: 完全なTinyCalcの例

以下の最小限のUBNF文法とエバリュエータは完全な生成パイプラインを実証する：

**文法（TinyCalc.ubnf）:**

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc

  token NUMBER = NumberParser
  token EOF    = EndOfSourceParser

  @root
  Formula ::= Expression EOF ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { AddOp @op Term @right } ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { MulOp @op Factor @right } ;

  Factor ::= NUMBER | '(' Expression ')' ;

  AddOp ::= '+' | '-' ;
  MulOp ::= '*' | '/' ;
}
```

**生成されたsealed AST（TinyCalcAST.java）:**

```java
public sealed interface TinyCalcAST permits TinyCalcAST.BinaryExpr {
    record BinaryExpr(BinaryExpr left, List<String> op, List<BinaryExpr> right)
        implements TinyCalcAST {}
}
```

**手書きエバリュエータ（TinyCalcEvaluatorImpl.java）:**

```java
public class TinyCalcEvaluatorImpl extends TinyCalcEvaluator<Double> {
    @Override
    protected Double evalBinaryExpr(TinyCalcAST.BinaryExpr node) {
        if (node.left() == null && node.op().size() == 1) {
            return Double.parseDouble(node.op().get(0));  // leaf
        }
        double result = eval(node.left());
        for (int i = 0; i < node.op().size(); i++) {
            double r = eval(node.right().get(i));
            result = switch (node.op().get(i)) {
                case "+" -> result + r;
                case "-" -> result - r;
                case "*" -> result * r;
                case "/" -> result / r;
                default -> throw new IllegalArgumentException();
            };
        }
        return result;
    }
}
```

この35行の文法と17行のエバリュエータが、パーサー、AST、マッパー、エバリュエータ、LSPサーバー、DAPサーバーを備えた完全な計算機を生成する。

---

## 付録B: 伝播ストッパー決定行列

| ストッパー | 子へのTokenKind | 子へのinvertMatch | ユースケース |
|---------|-------------------|---------------------|----------|
| *(なし)* | 親の値 | 親の値 | デフォルト伝播 |
| `AllPropagationStopper` | `consumed` | `false` | サブ式の全パーシングモードをリセット |
| `DoConsumePropagationStopper` | `consumed` | 親の値 | matchOnlyコンテキスト内で消費を強制 |
| `InvertMatchPropagationStopper` | 親の値 | `false` | NOT意味論のサブパーサーへの伝播を防止 |
| `NotPropagatableSource` | 親の値 | `!親の値` | 反転フラグを反転して論理NOTを実装 |

---

## ナビゲーション

[← インデックスに戻る](../INDEX.md)

| 論文 (JA) | 査読 |
|-----------|------|
| [← v4 論文](../v4/from-grammar-to-ide.ja.md) | [v4 査読](../v4/review-dialogue-v4.ja.md) |
| **v5 — 現在** | [v5 査読](./review-dialogue-v5.ja.md) |
