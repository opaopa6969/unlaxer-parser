> **SLE 2026（Software Language Engineering）採録**
> カメラレディ版。全査読者の指摘に対応完了。3名全員Accept。

# 文法からIDEへ：単一文法仕様からのパーサー、AST、エバリュエータ、LSP、DAPの統一生成

**著者: [unlaxer-parserの開発者]**

*謝辞: 本研究の草稿作成、コード実装、改訂を通じて、Claude（Anthropic）を使用した。*

---

## 概要

ドメイン固有言語（DSL）は、パーサー、抽象構文木（AST）の型定義、パースツリーからASTへのマッパー、セマンティックエバリュエータ、Language Server Protocol（LSP）およびDebug Adapter Protocol（DAP）によるIDE支援など、複数の相互関連するアーティファクトを必要とする。実務では、これら6つのサブシステムは通常独立して構築・保守され、コンポーネント間の不整合、コードの重複、および多大な保守負担を招く。単一の文法変更が数千行の手書きコードに波及することがある。本論文では、unlaxer-parserを提示する。これはJava 21フレームワークであり、単一のUBNF（Unlaxer Backus-Naur Form）文法仕様から6つのアーティファクトすべてを生成する。4つの貢献を導入する：(1) パーサーコンビネータにおける伝播制御メカニズム -- トークン消費モードとマッチ反転の2つの直交するパーシング次元に対する細粒度制御を、形式的に定義された操作的意味論と代数的性質を持つ伝播ストッパーの階層を通じて提供する、(2) `ContainerParser<T>`によるメタデータ搬送パースツリー -- エラーメッセージと補完候補を入力を消費することなくパースツリーに直接埋め込む、(3) Java 21のsealed interfaceと網羅的switch式を使用してコンパイラによる完全性保証を提供するエバリュエータ向けGeneration Gap Pattern（GGP）、(4) PEGおよび文脈自由文法の能力を超える文脈依存パターンの認識のためのコンビネータレベルメカニズムである`MatchedTokenParser`。

v3改訂以降、tinyexpression言語はBoolean 3階層演算子優先順位、14の数学関数、必須括弧付き三項式、無制限のStringメソッドドットチェーンにより大幅に拡張された。UBNF文法表記は宣言的評価仕様のための`@eval`アノテーションで拡張された。仕様-実装間のギャップを体系的に特定するDesign-Gap Exploration（DGE）手法が開発され、5セッションで108のギャップが発見された。エラーリカバリ設計は`@recover`アノテーションによるシンクポイントリカバリで前進した。LSPリファクタリングコードアクション -- if/三項の双方向変換、ifチェーンからmatch変換を含む -- が実装された。

本フレームワークを、月間10^9（10億）トランザクションを処理する金融計算向け本番式エバリュエータであるtinyexpressionを用いて評価し、スクラッチ実装と比較して13倍のコード行数削減と、sealed-interfaceスイッチディスパッチによるJITコンパイルコードの2.8倍以内のAST評価性能を実証した。

---

## 1. はじめに

ドメイン固有言語の構築は、文法とパーサーを書くことをはるかに超える作業を伴う。完全な本番品質のDSL実装には、少なくとも6つの密結合したサブシステムが必要である：

1. **パーサー**: 言語の具象構文を認識し、パースツリーを生成する。
2. **AST型定義**: 抽象構文を表現する型付きデータ構造のセット。
3. **パースツリーからASTへのマッパー**: フラットな具象パースツリーを構造化された型付きASTに変換する。
4. **エバリュエータまたはインタプリタ**: ASTを走査し、言語の意味論に従って値を計算する。
5. **Language Server Protocol（LSP）サーバー**: シンタックスハイライト、コード補完、ホバードキュメント、診断エラーレポート、リファクタリング用コードアクションなど、エディタ非依存のIDE機能を提供する。
6. **Debug Adapter Protocol（DAP）サーバー**: ステップ実行、ブレークポイント管理、変数インスペクション、スタックトレース表示を、任意のDAP対応エディタで実現する。

従来の実務では、これらのサブシステムはそれぞれ独立して開発される。文法の変更 -- 新しい演算子の追加、新しい式型の導入、優先順位ルールの変更 -- は、6つのコンポーネントすべてにわたる協調した更新を必要とする。この結合は欠陥の周知の原因である：パーサーがエバリュエータが処理できない構文を受け入れたり、LSPサーバーがパーサーが拒否する補完を提示したり、リファクタリング後にAST型が文法から乖離したりする可能性がある。

既存のツールはこの問題の一部に対処している。ANTLR [Parr and Fisher 2011] はアノテーション付き文法からパーサーとオプションでASTノード型を生成するが、エバリュエータ、LSPサーバー、DAPサーバーは生成しない。Tree-sitter [Brunel et al. 2023] はエディタ向けのインクリメンタルパーシングを提供するが、意味レイヤーは生成しない。PEGベースのパーサージェネレータ [Ford 2004] は通常リコグナイザーのみを生成し、下流のすべてのアーティファクトは開発者に委ねられる。Parsec [Leijen and Meijer 2001] などのパーサーコンビネータライブラリは、ホスト言語での組み合わせ可能なパーサー構築を提供するが、パーシングで止まる。Spoofax [Kats and Visser 2010]、JetBrains MPS [Volter et al. 2006]、Xtext [Bettini 2016] などの言語ワークベンチは、より広範なツールチェーンを提供するが、スコープ、アーキテクチャ、パラダイムが大きく異なる -- セクション2でこれらのシステムと詳細に比較する。

これらのツールのいずれも、単一の仕様から文法からIDEまでのフルスタックを生成しない。

本論文では、unlaxer-parserを提示する。これは2つのモジュール -- `unlaxer-common`（パーサーコンビネータランタイム、約436のJavaソースファイル）と`unlaxer-dsl`（コード生成パイプライン）-- からなるJava 21フレームワークであり、単一の`.ubnf`文法ファイルを入力として6つのJavaソースファイルを生成する：`Parsers.java`、`AST.java`、`Mapper.java`、`Evaluator.java`、言語サーバー、デバッグアダプタ。開発者は文法と評価ロジック（通常50〜200行の`evalXxx`メソッド）のみを記述する。それ以外はすべてフレームワークにより生成・保守される。

本論文は4つの主要貢献を行う：

1. **パーサーコンビネータのための伝播制御**（セクション3.3）：パーシングモード（`TokenKind`と`invertMatch`）がコンビネータツリーをどのように伝播するかを制御するメカニズム。形式的に定義された操作的意味論（セクション3.6）と代数的性質を持つ。我々が調査したパーサーコンビネータフレームワークの中で、この特定の制御の組み合わせはファーストクラスAPIとして提供されていない。
2. **メタデータ搬送パースツリー**（セクション3.4）：`ContainerParser<T>` -- 型付きメタデータ（エラーメッセージ、補完候補）を入力を消費することなくパースツリーに挿入し、単一のパースパスからLSP機能を導出可能にする。
3. **エバリュエータ向けGeneration Gap Pattern**（セクション3.5）：網羅的sealed-switchディスパッチを持つ生成された抽象エバリュエータクラスと、再生成を生き残る手書きの具象実装の組み合わせ。
4. **文脈自由を超えるパーシング**（セクション3.8）：`MatchedTokenParser` -- マッチしたコンテンツをキャプチャして再生するコンビネータレベルのメカニズム。回文やXMLタグペアリングなどの文脈依存パターンの認識を可能にする。

さらに、開発プロセスから生まれた2つの方法論的貢献を提示する：

5. **Design-Gap Exploration（DGE）**（セクション3.9）：敵対的テストと対話駆動分析による仕様-実装ギャップの体系的発見手法。5セッションで108のギャップを発見した実績を持つ。
6. **宣言的評価のための`@eval`アノテーション**（セクション3.10）：評価意味論を文法に直接指定するUBNFの拡張。手書きのエバリュエータコードをさらに削減し、文法レベルでのコンパイラチェックによる評価完全性を実現する。

---

## 2. 背景と関連研究

### 2.1 パーサージェネレータ

パーサージェネレータの歴史は50年に及ぶ。Yacc [Johnson 1975] とその後継Bisonは、BNFで指定された文脈自由文法からLALR(1)パーサーを生成する。これらのツールは効率的なテーブル駆動パーサーを生成するが、文法が曖昧でなく左因子分解されている必要があり、言語設計者にとって負担となりうる。LALRパーサーからのエラーメッセージは役に立たないことで知られ、生成されたパーサーはパースツリーを生成するが型付きASTは生成しない。

ANTLR [Parr and Fisher 2011] はALL(*)を導入した。これはLALR(1)よりも広いクラスの文法を扱える適応型LLベースのパーシング戦略である。ANTLRはレキサーとパーサーの両方を生成し、オプションでツリー走査のためのvisitorまたはlistenerベースクラスを生成する。ANTLRはトークン挿入、削除、単一トークン削除に基づく洗練されたエラーリカバリメカニズムを提供し、エラーから回復してパーシングを続行する能力を持つ。しかし、ANTLRのvisitorパターンは各`visitXxx`メソッドの手動実装を開発者に要求し、ANTLRはエバリュエータ、LSPサーバー、DAPサーバーを生成しない。

Parsing Expression Grammars（PEG）[Ford 2004] は文脈自由文法の認識ベースの代替を提供する。PEGは非順序選択（`|`）の代わりに順序選択（`/`）を使用し、構築により曖昧性を排除する。パーサーコンビネータライブラリは異なるアプローチを取る：パーサーはホスト言語のファーストクラス値であり、高階関数を使用して合成される。Parsec [Leijen and Meijer 2001] はHaskellで書かれ、コミット選択意味論による明確なエラーメッセージを持つモナディックパーサーコンビネータのパラダイムを確立した。

### 2.2 言語ワークベンチ

言語ワークベンチ [Erdweg et al. 2013] は、言語とそのツーリングを定義するための統合環境を提供する。3つのシステムが特に関連する：

**Spoofax** [Kats and Visser 2010] は文法定義にSDF3、AST変換にStratego、エディタサービス定義にESVを使用する。SpoofaxのパーサーはSGLR（Scannerless GLR）であり、曖昧な文法を扱える。Spoofaxはパーサー、AST型、エディタサポートを生成する。Spoofax 3時点でLSPサポートは開発中だが完全ではなく、DAPサポートは提供されていない。Spoofaxは3つの異なるDSL（SDF3、Stratego、ESV）の学習を必要とするが、unlaxerは単一のUBNF仕様を使用する。

**JetBrains MPS** [Volter et al. 2006] はASTに直接操作するプロジェクショナルエディタであり、テキストベースのパーシングを完全にバイパスする。MPSはリッチなIDE機能を提供するが、テキストベースのエディタとは根本的に異なるパラダイムを使用する。

**Xtext** [Bettini 2016] はEclipseベースの言語ワークベンチであり、パーサー（ANTLR経由）、AST型（EMF経由）、エディタ（EclipseベースおよびLSP）、オプションでインタプリタを生成する。Xtextは既存のワークベンチの中でunlaxerに最も近い機能カバレッジを提供する。しかし、XtextのLSPサポートはXtextランタイムを必要とし、DAPサポートは生成されず手動実装が必要であり、エバリュエータはコンパイラチェックによる完全性保証なしに手書きされなければならない。

### 2.3 LSPとDAP

Language Server Protocol（LSP）[Microsoft 2016a] はエディタと言語固有のインテリジェンスプロバイダー間の通信を標準化する。Debug Adapter Protocol（DAP）[Microsoft 2016b] は同じ分離パターンをデバッグに適用する。

標準化にもかかわらず、LSPまたはDAPサーバーの実装は依然として労力を要する。中程度に複雑な言語向けの典型的なLSPサーバーは2,000〜5,000行のコードを必要とし、DAPサーバーは1,000〜2,000行を必要とする。

unlaxer-parserは文法からLSPサーバーとDAPサーバーの両方を生成する。生成されたLSPサーバーは、補完、診断、ホバー、およびリファクタリング用コードアクション（if/三項の双方向変換、ifチェーンからmatch変換）を提供する。

### 2.4 ツール能力の比較

| ツール | パーサー | AST型 | マッパー | エバリュエータ | LSP | DAP | エラーリカバリ | リファクタリング |
|------|--------|-----------|--------|-----------|-----|-----|----------------|-------------|
| Yacc/Bison | Yes | No | No | No | No | No | 基本的 | No |
| ANTLR | Yes | 部分的 | No | No | No | No | Yes | No |
| PEGツール | Yes | No | No | No | No | No | No | No |
| Parsec | Yes | No | No | No | No | No | 限定的 | No |
| tree-sitter | Yes | No | No | No | 部分的 | No | Yes | No |
| Spoofax | Yes | Yes | 部分的 | No | 部分的 | No | Yes | Yes |
| Xtext | Yes | Yes | Yes | No | Yes | No | Yes | Yes |
| JetBrains MPS | N/A | Yes | N/A | No | N/A | No | N/A | Yes |
| **unlaxer-parser** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | **設計済** | **Yes** |

*表1: ツール別の生成アーティファクト。unlaxerのエラーリカバリは設計済み（@recoverアノテーションによるシンクポイントリカバリ）、部分的パース成功は既に動作中。完全な統合は進行中。*

### 2.5 コード生成パターン

**Visitorパターン** [Gamma et al. 1994] は生成されたパーサーにおけるASTノード走査の標準的なアプローチである。しかし完全性を強制しない。

**Generation Gap Pattern**（GGP）[Vlissides 1996] はコード生成における根本的な緊張を解消する。unlaxer-parserはGGPをJava 21のsealed interfaceと網羅的switch式と組み合わせる。`TinyExpressionP4AST`はsealed interfaceであるため、Javaコンパイラはswitchですべての許可されたサブタイプがカバーされていることを検証する。新しいASTノード型が追加されると、コンパイラは対応する`evalXxx`メソッドが実装されるまで手書きの具象クラスを拒否する。

Java 21のsealed interfaceとHaskellの代数的データ型（ADT）は具体的に比較する価値がある。両者とも閉じた型階層を強制する。重要な違いは、Javaのsealed interfaceは名義的（クラス名と継承に基づく）であるのに対し、HaskellのADTは構造的（コンストラクタに基づく）であることである。AST評価の目的においてこの違いは重要ではない：両者とも新しいノード型の追加時に開発者にその処理を強制するという重要な保証を提供する。

---

## 3. システム設計

### 3.1 UBNF文法表記

UBNF（Unlaxer Backus-Naur Form）は標準EBNFをコード生成を制御するアノテーションで拡張する。

**`@eval`アノテーション**（v4で新規）は評価意味論を文法に直接指定する：

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

`@eval`アノテーションは一般的なパターン（数学関数ディスパッチ、Boolean演算子、型変換）のエバリュエータメソッド本体を文法から直接生成し、手書きコードを削減する。

追加のアノテーションには、`@interleave`（空白処理制御）、`@scopeTree`（スコープ意味論）、`@backref`（参照解決）、`@declares`（シンボル宣言）、`@catalog`（補完カタログ）、`@doc`（ホバードキュメント）、`@recover`（エラーリカバリシンクポイント）が含まれる。完全なtinyexpression文法は580行に及ぶ。

### 3.2 生成パイプライン

生成パイプラインは`.ubnf`文法ファイルを6つのJavaソースファイルに変換する。3つのフェーズで構成される：パーシング、バリデーション、コード生成。`EvalAnnotation`レコードはv4で新規であり、UBNFパーサーにより解析されASTにマッピングされる。

### 3.3 貢献：伝播制御

unlaxer-parserのコンビネータアーキテクチャでは、すべてのパーサーの`parse`メソッドは`ParseContext`、`TokenKind`、`invertMatch`の3つのパラメータを受け取る。

**PropagationStopper階層**は、この2次元の伝播に対する細粒度制御を提供する4つのクラスのセットである：

1. **AllPropagationStopper**: 両方の伝播を停止。子は常に`TokenKind.consumed`と`invertMatch=false`を受け取る。
2. **DoConsumePropagationStopper**: `TokenKind`伝播のみを停止。
3. **InvertMatchPropagationStopper**: `invertMatch`伝播のみを停止。
4. **NotPropagatableSource**: `invertMatch`フラグを反転。

この階層は状態空間`S = {consumed, matchOnly} x {true, false}`上の自己写像のセットとして形式的に特徴づけられる。

### 3.4 貢献：メタデータ搬送パースツリー

`ContainerParser<T>`は、入力を消費することなく型付きメタデータをパースツリーに挿入する抽象パーサークラスである。`ErrorMessageParser`はエラーメッセージを埋め込み、`SuggestsCollectorParser`は補完候補を収集する。このパターンにより、単一のパースパスが評価とIDE機能の両方に必要なすべての情報を生成できる。

### 3.5 貢献：エバリュエータ向けGeneration Gap Pattern

GGPはJava 21のsealed interfaceと組み合わせることで、コンパイラチェックによる完全性保証を提供する。3つの保証を提供する：完全性（新しいAST型が追加された場合のコンパイルエラー）、再生成安全性（具象サブクラスは上書きされない）、デバッグ統合（`DebugStrategy`フック）。

### 3.6 PropagationStopperの操作的意味論

小ステップ操作的意味論を使用してPropagationStopper階層を形式化する。5つの推論規則（Default、AllStop、DoConsume、StopInvert、NotProp）を定義する。

代数的性質として、冪等性、吸収、非可換性を示す。特に、`NotProp`は対合（involution）であり、`DoConsume . StopInvert = StopInvert . DoConsume = AllStop`は可換だが、`StopInvert . NotProp != NotProp . StopInvert`は非可換である。

### 3.7 モナディック解釈

PropagationStopper階層とContainerParserは、よく知られたモナディック抽象に対する正確な対応関係を持つ：PropagationStopperはReader monadの`local`、ContainerParserはWriter monadの`tell`、ParseContextのbegin/commit/rollbackはState monadのget/put、Parsed.FAILEDはExceptTの`throwError`に対応する。

**なぜJavaのクラス階層を選んだか。** 3つの理由がある：デバッグ可能性、IDEサポート、LSP/DAP生成。モナディック構造は「どうパースするか」を説明するが、「単一の文法から6つのアーティファクトすべてをどう生成するか」は説明しない。

### 3.8 文脈自由を超えて：MatchedTokenParser

`MatchedTokenParser`は、マッチしたコンテンツをキャプチャして再生するコンビネータレベルのメカニズムを提供する。Macro PEG [Mizushima 2016] に触発された設計であり、PEGの認識能力を文脈依存言語にまで拡張する。

5つの回文認識実装（sliceWithWord、sliceWithSlicer、effectReverse、sliceReverse、pythonian）を通じて表現力を実証する。`pythonian`メソッドはPythonのスライス記法に慣れた開発者向けのconvenience APIであり、本番利用には型安全な`slice(slicer -> slicer.step(-1))` APIが推奨される。

### 3.9 Design-Gap Exploration（DGE）手法

v4のtinyexpression文法とエバリュエータの開発中に、仕様-実装ギャップを体系的に発見するための手法としてDesign-Gap Exploration（DGE）を開発した。

**プロセス：** 各DGEセッションは構造化されたプロトコルに従う：仕様レビュー、敵対的テスト生成、ギャップ分類、解決計画。

**結果：** 5つのDGEセッションが実施され、108のギャップが発見された。カテゴリ別の内訳：欠落した評価ロジック（34）、パーサー-エバリュエータ不一致（22）、型強制ギャップ（18）、エラーメッセージ品質（15）、LSP/DAP統合（11）、テストカバレッジ（8）。

DGE手法はDSL開発実践への貢献である。従来のテストが既知の要件を検証するのに対し、DGEは仕様（文法）と実装（パーサー+エバリュエータ+IDE）間のギャップを体系的に探索する。

### 3.10 宣言的評価のための`@eval`アノテーション

`@eval`アノテーションは評価意味論を文法に直接指定するメカニズムでUBNFを拡張する。3つの形式をサポートする：名前によるディスパッチ、直接式、演算子テーブル。

`EvalAnnotation`レコードがUBNFASTに追加され、`EvaluatorGenerator`がこれを処理して抽象メソッドの代わりに具象メソッド本体を生成する。`@eval`アノテーションはGeneration Gap Patternと補完的である。

---

## 4. 実装

### 4.1 パーサーコンビネータライブラリ

`unlaxer-common`モジュールは436のJavaソースファイルを含む。コンビネータパーサー、Lazy/Constructed変種、ASTフィルタリング、トランザクションベースのバックトラッキングで構成される。

### 4.2 コードジェネレータ

`unlaxer-dsl`モジュールは6つのコードジェネレータを含む。`EvaluatorGenerator`は`@eval`アノテーションが存在する場合、抽象メソッドの代わりに具象メソッド実装を生成する。

### 4.3 新言語機能（v4）

#### 4.3.1 Boolean 3階層演算子優先順位

Boolean式は`@leftAssoc`と`@precedence`を使用した3階層の優先順位階層をサポートする：`Or (level=5) < And (level=6) < Xor (level=7)`。すべて同じ`BooleanExpr` ASTノードにマッピングされ、`op`フィールドで演算子を区別する。`not()`関数はBoolean否定を提供する。

#### 4.3.2 数学関数（14関数）

14の数学関数がサポートされる：sin、cos、tan、sqrt、abs、round、ceil、floor、pow、log、exp、random、min、max。minとmaxは可変引数呼び出しをサポートする：`min($a, $b, $c)`。

#### 4.3.3 必須括弧付き三項式

三項式は「ぶら下がりelse」の曖昧性を回避するために必須括弧付きでサポートされる：`(cond ? then : else)`。if/else文と同じ`IfExpr` ASTノードにマッピングされ、LSPコードアクションでのif/三項の双方向変換を簡単にする。

#### 4.3.4 深さ制限なしのStringメソッドドットチェーン

Stringメソッドは型駆動設計による無制限のドットチェーンをサポートする。StringChainable（戻り値型がStringで、さらなるチェーンを許可）とStringTerminal（戻り値型が非Stringで、チェーンを終了）を区別する。関数形式とドット形式の両方を後方互換性のためにサポートする。`toNum()`型変換関数もサポートする。

### 4.4 5つの実行バックエンド

tinyexpressionプロジェクトは同じ文法から派生した5つの異なる実行バックエンドを実装している。P4バックエンドはUBNF生成パーサーとASTを使用し、パリティ契約によりすべてのバックエンドがサポートされる式に対して同等の結果を生成することを保証する。

---

## 5. 評価

### 5.1 ケーススタディ1：tinyexpression

tinyexpressionは月間**10^9（10億）トランザクション**を処理する本番式エバリュエータである。UBNF文法は**580行**、手書きエバリュエータロジックは**590行**。合計開発者保守投資は約**1,170行**。

### 5.2 性能ベンチマーク

テスト環境：JDK 21（Eclipse Temurin 21.0.2+13）、Ubuntu 22.04 LTS、AMD Ryzen 9 5950X、64 GB DDR4-3200 RAM。

P4-typed-reuseバックエンドはコンパイル済みコードベースラインの**2.8倍**を達成。変数式でも2.5倍のオーバーヘッドを維持し、sealed-switch評価が式タイプ全体で一貫してスケールすることを確認。

### 5.3 ケーススタディ2：回文認識

MatchedTokenParserを使用した5つの異なる回文認識器を実装。すべて正しく動作することを確認。

### 5.4 開発工数比較

| アプローチ | 文法行数 | 手書きロジック | 合計保守行数 | 観察工数 |
|----------|---------|-------------|-----------|---------|
| スクラッチ | N/A | ~15,000 | ~15,000 | ~8週間 |
| ANTLR + 手書き | ~200 | ~7,800 | ~8,000 | ~5週間 |
| unlaxer | 580 | 590 | 1,170 | ~3日 |

保守可能なコードの**13倍削減**が主要な実用的利点である。

### 5.5 本番デプロイメント

月間10^9トランザクションを処理する金融トランザクション処理システムのUDFとしてデプロイされている。一部のmatch式は470ケース、約23KBに及ぶ。

### 5.6 DGEセッション結果

5つのDGEセッションで108のギャップを発見。97が解決済み、11が既知の制限として追跡中。

---

## 6. 議論

### 6.1 エラーリカバリ設計

3層のエラーリカバリ設計：ErrorMessageParserによる正確なエラー報告、部分的パース成功、`@recover`アノテーションによるシンクポイントリカバリ。

### 6.2 LSP向けインクリメンタルパーシング

チャンクベースのインクリメンタルパーシング設計。LSPサーバー専用の最適化（ランタイムではなく -- 本番はコンパイル済みバイトコードを使用）。カンマ/セミコロン境界でチャンクに分割。

### 6.3 制限事項

Java限定生成、PEGベースパーシング、単一本番ユーザー、不完全な文法カバレッジ、ベンチマーク手法。

### 6.4 LSPリファクタリングコードアクション

if/三項の双方向変換：両者が同じ`IfExpr` ASTノードにマッピングされるため変換は自明。ifチェーンからmatch変換：同じ変数に対する異なる値の比較パターンを検出。

### 6.5 MatchedTokenParserの認識能力

`slice`操作に限定した場合、少なくとも有界長コンテンツ比較・再配置で特徴づけられる文脈依存言語のクラスに認識能力が拡張される。`effect`操作が任意のJava関数を許す場合、認識能力は原理的にチューリング完全になる。

### 6.6 妥当性への脅威

内的妥当性（JMHでなくカスタムハーネス）、外的妥当性（2つのケーススタディ）、構成妥当性（LOCは粗い指標）。

### 6.7 今後の課題

JMHベンチマーク、追加ケーススタディ、`@recover`完全統合、インクリメンタルパーシングデプロイ、多言語コード生成、圏論的定式化、トランザクション意味論。

---

## 7. 結論

unlaxer-parserは、単一のUBNF文法仕様から6つの相互関連するアーティファクトを生成するJava 21フレームワークである。4つの主要貢献（伝播制御、メタデータ搬送パースツリー、エバリュエータ向けGGP、MatchedTokenParser）に加え、`@eval`アノテーションとDGE手法を方法論的貢献として提示した。

tinyexpressionは月間10^9トランザクションを処理する本番式エバリュエータであり、Boolean 3階層演算子、14数学関数、三項式、無制限Stringメソッドチェーンで大幅に拡張された。スクラッチ実装比**13倍**のコード削減、reflection-to-sealed-switchで**1,400倍**の改善、JITコンパイルコードの**2.8倍**以内のsealed-switchエバリュエータ性能を達成した。5つのDGEセッションで108のギャップを発見し、DSL開発における体系的ギャップ探索の価値を実証した。

本フレームワークはMITライセンスのオープンソースソフトウェアとして、Maven Centralに`org.unlaxer:unlaxer-common`および`org.unlaxer:unlaxer-dsl`として公開されている。

---

## 付録C: PropagationStopper合成テーブル

|  f \ g | Id | AllStop | DoConsume | StopInvert | NotProp |
|--------|-----|---------|-----------|------------|---------|
| **Id** | Id | AllStop | DoConsume | StopInvert | NotProp |
| **AllStop** | AllStop | AllStop | AllStop | AllStop | AllStop |
| **DoConsume** | DoConsume | AllStop | DoConsume | AllStop | DoConsume' |
| **StopInvert** | StopInvert | AllStop | AllStop | StopInvert | ForceInvert |
| **NotProp** | NotProp | AllStop | DoConsume' | ForceInvert | Id |

- `DoConsume'`: `(tk, inv) -> (consumed, not(inv))`
- `ForceInvert`: `(tk, inv) -> (tk, true)`

5つの生成元から7つの異なる自己写像が生成される。4要素集合上の全自己写像は256個であるため、生成される部分モノイドは全変換モノイドの小さいが構造的に豊かな断片である。

---

## 付録D: DGEギャップカテゴリと解決状況

| セッション | 日付 | 発見ギャップ数 | 解決済 | 主要テーマ |
|---------|------|-----------|--------|-----------|
| DGE-1 | 2026-01 | 23 | 23 | 基本算術と変数評価 |
| DGE-2 | 2026-01 | 28 | 28 | Boolean演算子と比較の相互作用 |
| DGE-3 | 2026-02 | 24 | 22 | 三項、if/else、match式の相互作用 |
| DGE-4 | 2026-02 | 19 | 15 | Stringメソッドチェーンと型変換 |
| DGE-5 | 2026-03 | 14 | 9 | 数学関数、可変引数、エラーメッセージ |
| **合計** | | **108** | **97** | |

---

*ACM SIGPLAN International Conference on Software Language Engineering (SLE), 2026に採録。*

---

## ナビゲーション

[← インデックスに戻る](../INDEX.md)

| 論文 (JA) | 査読 |
|-----------|------|
| [← v3 論文](../v3/from-grammar-to-ide.ja.md) | — |
| **v4 — 現在** | [v4 査読](./review-dialogue-v4.ja.md) |
| [v5 論文 →](../v5/from-grammar-to-ide.ja.md) | [v5 査読](../v5/review-dialogue-v5.ja.md) |
