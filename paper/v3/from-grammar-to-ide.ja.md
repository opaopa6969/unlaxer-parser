> **SLE 2026 (Software Language Engineering) 採録**
> Camera-ready版。PC決定に基づくminor revisionを適用済み。

# 文法からIDEへ: 単一の文法仕様からのパーサー、AST、エバリュエータ、LSP、DAPの統一生成

**著者: [unlaxer-parser 開発者]**

*謝辞: 草稿作成、コード実装、改訂にClaude (Anthropic)を使用した。*

---

## 概要

ドメイン固有言語（DSL）は、パーサー、抽象構文木（AST）型定義、パースツリーからASTへのマッパー、意味評価器、Language Server Protocol（LSP）およびDebug Adapter Protocol（DAP）によるIDE支援など、相互に関連する複数の成果物を必要とする。実際には、これら6つのサブシステムは通常独立して構築・保守されるため、コンポーネント間の不整合、コードの重複、多大な保守負担が生じる -- 単一の文法変更が数千行の手書きコードに波及しうる。本論文では、単一のUBNF（Unlaxer Backus-Naur Form）文法仕様から6つの成果物すべてを生成するJava 21フレームワーク、unlaxer-parserを提示する。4つの貢献を導入する: (1) トークン消費モードとマッチ反転という2つの直交するパーシング次元に対するきめ細かな制御を、伝播ストッパーの階層を通じて提供するパーサーコンビネータの伝播制御機構（形式的に定義された操作的意味論と代数的性質を伴う）; (2) `ContainerParser<T>`によるメタデータ搬送パースツリー（入力を消費せずにエラーメッセージや補完候補をパースツリーに直接埋め込む）; (3) Java 21のsealed interfaceと網羅的switch式を用いてコンパイラによる完全性検査を保証するエバリュエータ向けGeneration Gap Pattern（GGP）; (4) PEGおよび文脈自由文法の能力を超えた文脈依存パターンを認識するコンビネータレベルの機構`MatchedTokenParser`。金融計算に使用され月間10^9（10億）トランザクションを処理する本番式評価器tinyexpressionを用いてフレームワークを評価し、フルスクラッチ実装と比較して14倍のコード行数削減、sealed-interfaceのswitch dispatchによりJITコンパイルコードの2.8倍以内のAST評価性能を実証する。第2のケーススタディでは、`MatchedTokenParser`に基づく5つの異なる実装を用いた回文認識（典型的な文脈依存パターン）を示す。

---

## 1. はじめに

ドメイン固有言語の構築には、文法とパーサーの記述をはるかに超える作業が伴う。完全な本番品質のDSL実装には、少なくとも6つの密結合サブシステムが必要である:

1. **パーサー**: 言語の具象構文を認識し、パースツリーを生成する。
2. **AST型定義**: 抽象構文を表現する型付きデータ構造の集合。
3. **パースツリーからASTへのマッパー**: フラットな具象パースツリーを構造化された型付きASTに変換する。
4. **エバリュエータまたはインタプリタ**: ASTを走査し、言語の意味論に従って値を計算する。
5. **Language Server Protocol（LSP）サーバー**: シンタックスハイライト、コード補完、ホバードキュメント、診断エラー報告などのエディタ非依存のIDE機能を提供する。
6. **Debug Adapter Protocol（DAP）サーバー**: ステップ実行、ブレークポイント管理、変数検査、スタックトレース表示を、DAP互換エディタで可能にする。

従来の実践では、これらのサブシステムはそれぞれ独立して開発される。文法変更 -- 新しい演算子の追加、新しい式型の導入、優先順位規則の変更 -- には、6つのコンポーネントすべてにわたる整合的な更新が必要となる。この結合は欠陥の周知の原因である: パーサーがエバリュエータで処理できない構文を受け入れたり、LSPサーバーがパーサーが拒否する補完を提供したり、リファクタリング後にAST型が文法から乖離したりする。

既存のツールはこの問題の一部に対応している。ANTLR [Parr and Fisher 2011]は注釈付き文法からパーサーとオプションでASTノード型を生成するが、エバリュエータ、LSPサーバー、DAPサーバーは生成しない。Tree-sitter [Brunel et al. 2023]はエディタ向けのインクリメンタルパーシングを提供するが、意味層を生成しない。PEGベースのパーサージェネレータ [Ford 2004]は通常、認識器のみを生成し、下流の成果物はすべて開発者に委ねられる。Parsec [Leijen and Meijer 2001]などのパーサーコンビネータライブラリはホスト言語での合成的なパーサー構築を提供するが、やはりパーシングで止まる。Spoofax [Kats and Visser 2010]、JetBrains MPS [Volter et al. 2006]、Xtext [Bettini 2016]などの言語ワークベンチはより広範なツールチェーンを提供するが、スコープ、アーキテクチャ、パラダイムが大きく異なる -- これらのシステムとの比較はSection 2で詳述する。

文法からIDEまでの完全なスタックを単一の仕様から生成するツールは存在しない。

本論文では、2つのモジュール -- `unlaxer-common`（パーサーコンビネータランタイム、約436のJavaソースファイル）と`unlaxer-dsl`（コード生成パイプライン）-- から構成されるJava 21フレームワーク、unlaxer-parserを提示する。単一の`.ubnf`文法ファイルを入力として、6つのJavaソースファイルを生成する: `Parsers.java`、`AST.java`、`Mapper.java`、`Evaluator.java`、言語サーバー、およびデバッグアダプター。開発者が書くのは文法と評価ロジック（通常50--200行の`evalXxx`メソッド）のみであり、それ以外はすべてフレームワークが生成・保守する。

本論文は4つの貢献を行う:

1. **パーサーコンビネータの伝播制御** (Section 3.3): パーシングモード（`TokenKind`と`invertMatch`）がコンビネータツリーを伝播する方法を制御する機構。形式的に定義された操作的意味論（Section 3.6）と代数的性質を伴う。我々が調査したパーサーコンビネータフレームワークの中で、この特定の制御の組み合わせがファーストクラスAPIとして提供されているものはなかった。
2. **メタデータ搬送パースツリー** (Section 3.4): `ContainerParser<T>`は型付きメタデータ（エラーメッセージ、補完候補）を入力を消費せずにパースツリーに挿入し、単一のパースパスからLSP機能を導出可能にする。
3. **エバリュエータ向けGeneration Gap Pattern** (Section 3.5): 網羅的sealed-switchディスパッチを持つ生成された抽象エバリュエータクラスと、再生成後も保持される手書きの具象実装の組み合わせ。
4. **文脈自由を超えるパーシング** (Section 3.8): `MatchedTokenParser`は、マッチしたコンテンツをキャプチャし再生する、コンビネータレベルの機構であり、回文やXMLタグ対応などの文脈依存パターンの認識を可能にする。

本論文の構成は以下の通りである。Section 2ではパーサー生成、言語ワークベンチ、IDEプロトコル支援に関する関連研究を概観する。Section 3ではUBNF文法記法、生成パイプライン、4つの貢献、PropagationStopperの操作的意味論、モナディック解釈、MatchedTokenParserを含むシステム設計を提示する。Section 4では実装を記述する。Section 5ではtinyexpressionと回文認識のケーススタディを用いてフレームワークを評価し、性能ベンチマークと開発工数の比較を示す。Section 6では制限事項、妥当性への脅威、将来の研究を議論する。Section 7で結論を述べる。

---

## 2. 背景と関連研究

### 2.1 パーサージェネレータ

パーサージェネレータの歴史は50年に及ぶ。Yacc [Johnson 1975]とその後継Bisonは、BNFで記述された文脈自由文法からLALR(1)パーサーを生成する。これらのツールは効率的なテーブル駆動パーサーを生成するが、文法が曖昧でなく左括り出しされていることを要求し、言語設計者にとって負担となりうる。LALRパーサーのエラーメッセージは非常に不親切であることで知られ、生成されるパーサーはパースツリーを生成するが型付きASTは生成しない。

ANTLR [Parr and Fisher 2011]はALL(*)を導入した。これはLALR(1)よりも広いクラスの文法を扱える適応型LLベースのパーシング戦略である。ANTLRはレキサーとパーサーの両方を生成し、オプションでツリー走査のためのビジターまたはリスナーベースクラスを生成する。しかし、ANTLRのビジターパターンでは開発者が各`visitXxx`メソッドを手書きで実装する必要があり、ANTLRはエバリュエータ、LSPサーバー、DAPサーバーを生成しない。開発者がすべての下流成果物に責任を持つ。

Parsing Expression Grammars (PEG) [Ford 2004]は文脈自由文法に対する認識ベースの代替手段を提供する。PEGは非順序の選択（`|`）の代わりに順序付き選択（`/`）を使用し、構造的に曖昧さを排除する。メモ化を伴うpackratパーサーを含むPEGベースのパーサーは、予測可能性と実装の容易さから人気を得ている。しかし、PEGパーサーは通常、認識器に過ぎない -- 入力が文法にマッチするかを判定するが、構造化されたパースツリーを本質的に生成しない。Ierusalimschy のLPEG [Ierusalimschy 2009]やRedziejowskiのPEG基礎に関する研究 [Redziejowski 2007]など、いくつかのPEGベースツールは認識問題に焦点を当て、AST構築やIDE統合には対応していない。

パーサーコンビネータライブラリは異なるアプローチを取る: パーサーはホスト言語のファーストクラス値であり、高階関数を使って合成される。Haskellで書かれたParsec [Leijen and Meijer 2001]は、コミット選択セマンティクスを通じて明確なエラーメッセージを提供するモナディックパーサーコンビネータでこのパラダイムを確立した。ScalaパーサーコンビネータはこのアプローチをJVMにもたらした。パーサーコンビネータは優れた合成性とホスト言語の型システムとの統合を提供するが、パーサー自体を超えるものは何も生成しない。

### 2.2 言語ワークベンチ

言語ワークベンチ [Erdweg et al. 2013]は言語とそのツーリングを定義するための統合環境を提供する。3つのシステムが特に関連する:

**Spoofax** [Kats and Visser 2010]は文法定義にSDF3、AST変換にStratego、エディタサービス定義にESVを使用する。Spoofaxのパーサーは SGLR（Scannerless GLR）であり、曖昧な文法を扱える -- これは我々のPEGベースアプローチとの根本的な相違点である。Spoofaxはパーサー、AST型、エディタ支援（シンタックスハイライト、構造的編集）を生成する。Spoofax 3ではLSP支援が開発中だがまだ完全ではなく、DAP支援は提供されていない。Spoofaxは3つの異なるDSL（SDF3、Stratego、ESV）の習得を要求するが、unlaxerは単一のUBNF仕様を使用する。

**JetBrains MPS** [Volter et al. 2006]はASTに直接操作するプロジェクショナルエディタであり、テキストベースのパーシングを完全にバイパスする。MPSはプロジェクショナルパラダイム内でネイティブに豊富なIDE機能（補完、エラーチェック、リファクタリング）を提供する。しかし、MPSはテキストベースのエディタとは根本的に異なるパラダイムを使用し、VS CodeやEmacsなどの従来のテキストエディタで使用するためのLSPまたはDAPサーバーを生成しない。

**Xtext** [Bettini 2016]はEclipseベースの言語ワークベンチであり、パーサー（ANTLR経由）、AST型（EMF経由）、エディタ（EclipseベースおよびLSP）、オプションでインタプリタを生成する。Xtextは既存のワークベンチの中でunlaxerに最も近い機能カバレッジを提供する。しかし、XtextのLSP支援はXtextランタイムを必要とし、DAP支援は生成されず手動で実装する必要があり、エバリュエータはコンパイラによる完全性保証なしに手書きしなければならない。

### 2.3 Language Server ProtocolとDebug Adapter Protocol

Language Server Protocol（LSP）[Microsoft 2016a]はエディタと言語固有のインテリジェンスプロバイダー間の通信を標準化する。LSPサーバーはコード補完、ホバー情報、定義へのジャンプ、参照検索、診断（エラー・警告報告）、コードアクションなどの機能を実装する。LSP以前は、各エディタに言語固有のプラグインが必要であった。LSPはエディタ支援を言語実装から切り離し、単一のサーバーがVS Code、Emacs、Vim、およびその他のLSP互換エディタで動作することを可能にした。

Debug Adapter Protocol（DAP）[Microsoft 2016b]は同じ切り離しパターンをデバッグに適用する。DAPサーバーはlaunch/attach、ブレークポイント、step-over/step-into/step-out、スタックトレース、変数検査、式評価を実装する。LSPと同様に、DAPは単一のデバッグアダプターがエディタ間で動作することを可能にする。

標準化にもかかわらず、LSPまたはDAPサーバーの実装は依然として労働集約的である。中程度に複雑な言語の一般的なLSPサーバーは2,000--5,000行のコードを必要とし、DAPサーバーは1,000--2,000行を必要とする。これらのサーバーは文法、AST、エバリュエータと同期し続けなければならない。Tree-sitter [Brunel et al. 2023]はインクリメンタルパーシング、シンタックスハイライト、基本的な構造クエリを提供することでLSP統合に部分的に対応しているが、型認識補完や診断報告などの意味的機能は提供しない。

unlaxer-parserは文法からLSPサーバーとDAPサーバーの両方を生成する。生成されたLSPサーバーは補完（文法キーワードおよび`@catalog`/`@declares`アノテーションに基づく）、診断（パースエラーおよび`ErrorMessageParser`メタデータに基づく）、ホバー（`@doc`アノテーションに基づく）を提供する。生成されたDAPサーバーはパースツリーを通じたステップ実行、ブレークポイント支援、現在のトークンのテキストとパーサー名を表示する変数表示を提供する。

### 2.4 ツール能力の比較

表1はツール間で生成される成果物を比較する。「部分的」はツールがインフラを提供するが大量の手書きコードを必要とすることを示す。「N/A」はツールが根本的に異なるパラダイムを使用していることを示す。

| ツール | パーサー | AST型 | マッパー | エバリュエータ | LSP | DAP |
|------|--------|-----------|--------|-----------|-----|-----|
| Yacc/Bison | Yes | No | No | No | No | No |
| ANTLR | Yes | 部分的 | No | No | No | No |
| PEGツール | Yes | No | No | No | No | No |
| Parsec | Yes | No | No | No | No | No |
| tree-sitter | Yes | No | No | No | 部分的 | No |
| Spoofax | Yes | Yes | 部分的 (Stratego) | No | 部分的 | No |
| Xtext | Yes | Yes (EMF) | Yes | No | Yes | No |
| JetBrains MPS | N/A (プロジェクショナル) | Yes | N/A | No | N/A | No |
| **unlaxer-parser** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** |

*表1: ツール別の生成成果物。SpoofaxのマッパーはStratego（別のDSL）で記述される。XtextのLSP支援はXtextランタイムを必要とする。MPSはテキストベースLSPとは異なるプロジェクショナル編集パラダイムで動作する。*

### 2.5 コード生成パターン

2つのコード生成パターンが本研究に特に関連する。

**Visitorパターン** [Gamma et al. 1994]は生成されたパーサーにおけるASTノード走査の標準的なアプローチである。ANTLRは文法ルールごとに1つの`visitXxx`メソッドを持つビジターインターフェースを生成する。このパターンはツリー構造とそれに対する操作の間の良好な分離を提供するが、完全性を強制しない: 開発者はビジットメソッドの実装を容易に忘れることができ、ランタイムエラーや無言の不正動作につながる。

**Generation Gap Pattern**（GGP）[Vlissides 1996]はコード生成における根本的な緊張に対処する: 生成されたコードは入力（文法）が変更されたときに再生成される必要があるが、手書きのカスタマイズは再生成後も保持されなければならない。GGPはこれを各クラスを2つに分割することで解決する: 生成された抽象基底クラスと手書きの具象サブクラスである。生成された基底クラスは構造的なボイラープレートを含み、具象サブクラスは手書きのロジックを含む。ジェネレータが再実行されると、基底クラスのみが上書きされる。

unlaxer-parserはGGPとJava 21のsealed interfaceおよび網羅的switch式を組み合わせる。生成されたエバリュエータ基底クラスはsealed ASTインターフェースに対するswitch式を持つプライベートな`evalInternal`メソッドを含む:

```java
private T evalInternal(TinyExpressionP4AST node) {
    return switch (node) {
        case TinyExpressionP4AST.BinaryExpr n -> evalBinaryExpr(n);
        case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
        case TinyExpressionP4AST.IfExpr n -> evalIfExpr(n);
        // ... @mappingクラスごとに1つのcase
    };
}
```

`TinyExpressionP4AST`はsealed interfaceであるため、Javaコンパイラはすべての許可されたサブタイプがswitchでカバーされていることを検証する。新しいAST ノード型が文法に追加されると、開発者が対応する`evalXxx`メソッドを実装するまでコンパイラは手書きの具象クラスを拒否する。これはランタイムエラー（ビジターメソッドの欠落）をコンパイルタイムエラーに変換する -- 安全性の大幅な改善である。

---

## 3. システム設計

### 3.1 UBNF文法記法

UBNF（Unlaxer Backus-Naur Form）は標準EBNFをコード生成を制御するアノテーションで拡張する。UBNF文法ファイルは、グローバル設定、トークン宣言、ルール宣言を含む名前付き文法を定義する。

**グローバル設定**は生成パイプラインを構成する:

```ubnf
grammar TinyExpressionP4 {
  @package: org.unlaxer.tinyexpression.generated.p4
  @whitespace: javaStyle
  @comment: { line: '//' }
```

`@package`設定は生成コードのJavaパッケージを指定する。`@whitespace`設定は空白処理プロファイルを選択する（ここではJavaスタイルの空白、`//`および`/* */`コメントをインターリーブトークンとして含む）。`@comment`設定はコメント構文を宣言する。

**トークン宣言**は終端記号をパーサークラスに結合する:

```ubnf
  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token STRING     = SingleQuotedParser
  token CODE_BLOCK = org.unlaxer.tinyexpression.parser.javalang.CodeParser
```

各トークンはシンボリック名を`unlaxer-common`ライブラリ（またはユーザー定義パーサー）の具象パーサークラスにマッピングする。これにより文法は複雑な字句パターン（Javaスタイルのコードブロックなど）を文法記法にエンコードせずに参照できる。

**ルール宣言**はアノテーション付きのEBNF風構文を使用する:

```ubnf
  @root
  Formula ::= { VariableDeclaration } { Annotation } Expression { MethodDeclaration } ;
```

`@root`アノテーションは文法のエントリポイントをマークする。波括弧`{ ... }`は0回以上の繰り返し、角括弧`[ ... ]`はオプション要素、丸括弧`( ... )`はグルーピング、`|`は順序付き選択（PEGセマンティクス）を表す。

**`@mapping`アノテーション**はAST生成の中心的な機構である:

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=10)
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

このアノテーションはコードジェネレータに以下を指示する:
1. sealed ASTインターフェースにフィールド`left`、`op`、`right`を持つレコード型`BinaryExpr`を作成する。
2. パースツリーから`@left`、`@op`、`@right`アノテーション付き要素を抽出して`BinaryExpr`インスタンスを構築するマッパールールを生成する。
3. エバリュエータスケルトンに`evalBinaryExpr`抽象メソッドを生成する。

`@leftAssoc`アノテーションはマッパーで左結合グルーピングを生成し、`@precedence(level=N)`は曖昧さ解消のための優先順位レベルを設定する。

追加のアノテーションには、空白挿入を制御する`@interleave(profile=javaStyle)`、メソッド宣言のスコーピングセマンティクスのための`@scopeTree(mode=lexical)`、参照解決のための`@backref(name=X)`、シンボル宣言のための`@declares`、補完カタログのための`@catalog`、ホバードキュメントのための`@doc`がある。完全なtinyexpression文法（`tinyexpression-p4-complete.ubnf`）は520行に及び、数値、文字列、ブール値、オブジェクト式、変数宣言、メソッド宣言、if/elseおよびmatch式、インポート宣言、埋め込みJavaコードブロックをカバーする。

### 3.2 生成パイプライン

生成パイプラインは`.ubnf`文法ファイルを6つのJavaソースファイルに変換する。パイプラインは3つのフェーズで構成される:

**フェーズ1: パーシング。** UBNF文法ファイルはセルフホスティングパーサー（`UBNFParsers`）によってパースされる -- UBNFパーサー自体がunlaxer-parserのコンビネータライブラリを使用して構築されている。パースツリーは型付きAST（`UBNFAST`）にマッピングされ、ジェネレータがユーザー文法に対して生成するのと同じ`sealed interface + record`パターンを使用する。`UBNFAST`自体がsealed interfaceである:

```java
public sealed interface UBNFAST permits
    UBNFAST.UBNFFile,
    UBNFAST.GrammarDecl,
    UBNFAST.GlobalSetting,
    UBNFAST.SettingValue,
    UBNFAST.TokenDecl,
    UBNFAST.RuleDecl,
    UBNFAST.Annotation,
    UBNFAST.RuleBody,
    UBNFAST.AnnotatedElement,
    UBNFAST.AtomicElement,
    UBNFAST.TypeofElement { ... }
```

**フェーズ2: 検証。** `GrammarValidator`は文法の整合性を検査する: 未定義のルール参照、重複するルール名、`@root`アノテーションの欠落、`@mapping`パラメータとルール構造の一貫性。

**フェーズ3: コード生成。** `CodeGenerator`インターフェースをそれぞれ実装する6つのコードジェネレータが出力を生成する:

| ジェネレータ | 出力 | 説明 |
|-----------|--------|-------------|
| `ParserGenerator` | `XxxParsers.java` | `LazyChain`、`LazyChoice`、`LazyZeroOrMore`等を使用するPEGベースのパーサーコンビネータ。空白処理は`@interleave`プロファイルに基づいて自動挿入。 |
| `ASTGenerator` | `XxxAST.java` | `@mapping`クラスごとに1つの`record`を持つJava 21 sealed interface。フィールドは`params`リストに従って型付け。 |
| `MapperGenerator` | `XxxMapper.java` | トークンツリーからASTへのマッピングロジック。マルチルールマッピング、`@leftAssoc`/`@rightAssoc`グルーピング、ネストされたサブ式への深い検索を防ぐ`findDirectDescendants`を処理。 |
| `EvaluatorGenerator` | `XxxEvaluator.java` | 網羅的sealed-switchディスパッチとステップデバッグフックのための`DebugStrategy`インターフェースを持つ抽象クラス。 |
| `LSPGenerator` | `XxxLanguageServer.java` | 補完（キーワード、`@catalog`エントリ、`@declares`シンボル）、診断（パースエラーと`ErrorMessageParser`メタデータ）、ホバー（`@doc`アノテーション）を備えたLSPサーバー。`@declares`/`@backref`/`@scopeTree`アノテーション存在時はスコープストア登録を含む。 |
| `DAPGenerator` | `XxxDebugAdapter.java` | `stopOnEntry`支援、パースツリーを通じたステップオーバー実行、ブレークポイント管理、スタックトレース表示、変数検査を備えたDAPサーバー。 |

### 3.3 貢献: 伝播制御

unlaxer-parserのコンビネータアーキテクチャでは、すべてのパーサーの`parse`メソッドが3つのパラメータを受け取る:

```java
public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch)
```

- `ParseContext`は入力カーソル、トランザクションスタック、トークンツリーを管理する。
- `TokenKind`はパーサーが入力を*消費*するか（`consumed`）、カーソルを進めずに*マッチのみ*を行うか（`matchOnly`）を制御する。これはPEGの先読み述語に相当する。
- `invertMatch`はパーサーの成功・失敗セマンティクスを反転する -- `true`のとき、成功したマッチは失敗として扱われ、その逆も同様。これはPEGの「not」述語である。

素朴な実装では、`tokenKind`と`invertMatch`の両方が親から子へ無条件に伝播する。これは問題を引き起こす。`Not`コンビネータを考える:

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

`Not`は子を`matchOnly`モードに強制する（先読み中は入力を消費してはならない）。しかし、子がそれ自体`Not`コンビネータを含む複雑なサブ式の場合はどうか？ 外側の`Not`からの`matchOnly`は下に伝播するが、内側の`DoConsumePropagationStopper`はそれを選択的にオーバーライドできるべきである。

**PropagationStopper階層**を導入する。この2次元の伝播に対するきめ細かな制御を提供する4つのクラスの集合である:

```java
public interface PropagationStopper { }
```

**1. AllPropagationStopper**: `TokenKind`と`invertMatch`の両方の伝播を停止する。子は親が何を渡しても常に`TokenKind.consumed`と`invertMatch=false`を受け取る:

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

**2. DoConsumePropagationStopper**: `TokenKind`の伝播のみを停止し、子を`consumed`モードに強制しつつ`invertMatch`の通過を許可する:

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

**3. InvertMatchPropagationStopper**: `invertMatch`の伝播のみを停止し、子を`invertMatch=false`に強制しつつ`TokenKind`の通過を許可する:

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

**4. NotPropagatableSource**: `invertMatch`フラグを反転する（伝播値に対する論理NOT）。親が`invertMatch=true`を渡すと子は`invertMatch=false`を受け取り、その逆も同様:

```java
public class NotPropagatableSource extends AbstractPropagatableSource {
    @Override
    public boolean computeInvertMatch(boolean fromParentValue, boolean thisSourceValue) {
        return false == fromParentValue;
    }
}
```

この階層は、状態空間`S = {consumed, matchOnly} x {true, false}`上の自己写像の集合として形式的に特徴づけられる。各伝播ストッパーはどの次元を遮断し、どの値を代入するかを選択する。設計は合成的である: 伝播ストッパーはネストでき、各々がそれぞれの次元に独立して作用する。形式的な操作的意味論はSection 3.6に、モナディック解釈はSection 3.7に示す。

Parsecは`try`と`lookAhead`コンビネータを通じて先読みを扱い、コミット/非コミット選択とゼロ幅アサーションをそれぞれ制御する。これらのコンビネータはモナドの結合律を通じて合成可能である。しかし、パーシング状態を2つの直交する次元（`TokenKind`と`invertMatch`）に分解し、独立した伝播制御を提供するという特定の設計は、我々が調査したParsec、megaparsec、attoparsec、その他のパーサーコンビネータフレームワークではファーストクラスAPIとして提供されていない。特に、`invertMatch`伝播制御 -- コンビネータツリーの任意の地点で否定フラグを選択的に停止または反転する能力 -- は、既存フレームワークの標準コンビネータセットに直接的な対応物を持たない。

### 3.4 貢献: メタデータ搬送パースツリー

unlaxer-parserにおける根本的な洞察は、パースツリーがパーシングフェーズとIDE統合フェーズの間の**通信チャネル**として機能できるということである。これは`ContainerParser<T>`を通じて実現される。入力を消費せずに型付きメタデータをパースツリーに挿入する抽象パーサークラスである:

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

主要な性質は、`ContainerParser`が現在のカーソル位置に**空トークン**を作成することである -- 入力を消費せずに成功する。パーサーインスタンス自体が`get()`および`get(CursorRange)`メソッドを通じてアクセス可能なメタデータを搬送する。パーシング後、特定の`ContainerParser`サブクラスのインスタンスであるパーサーを持つトークンをフィルタリングすることで、トークンツリーからメタデータを抽出できる。

2つの具象サブクラスがこのパターンを示す:

**ErrorMessageParser**は診断報告のためにエラーメッセージをパースツリーに埋め込む:

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

`expectedHintOnly=true`で使用すると、パーサーは意図的に失敗するが、「期待される」トークンとして自身を登録し、その位置で何が期待されていたかの人間可読な説明をエラー報告システムに提供する。この情報はLSPサーバーの診断ハンドラーに直接流れる。

**SuggestsCollectorParser**はパーシング中に兄弟パーサーから補完候補を収集する:

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

このパーサーは兄弟パーサー（コンビネータツリーの同じレベルにあるパーサー）に対して、残りの入力に基づく補完候補を問い合わせる。候補はトークンツリーに格納され、後にLSPサーバーの補完ハンドラーによって抽出される。

メタデータ搬送パースツリーパターンにより、単一のパースパスで評価とIDE機能の両方に必要なすべての情報を生成できる。この機構がなければ、LSPおよびDAP統合には別のパスまたは並列データ構造が必要となり、複雑さと不整合のリスクが増大する。

### 3.5 貢献: エバリュエータ向けGeneration Gap Pattern

Generation Gap Pattern（GGP）[Vlissides 1996]は、生成されたコードと手書きのコードを継承関係で結ばれた異なるクラスに配置することで分離する。unlaxer-parserはGGPをエバリュエータ構築に適用し、重要な拡張を加える: Java 21のsealed interfaceが**コンパイラによる完全性検査**を提供する。

ジェネレータは抽象エバリュエータクラスを生成する:

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
            case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
            // ... @mapping型ごとに1つのcase
        };
    }

    protected abstract T evalBinaryExpr(TinyExpressionP4AST.BinaryExpr node);
    protected abstract T evalComparisonExpr(TinyExpressionP4AST.ComparisonExpr node);
    protected abstract T evalIfExpr(TinyExpressionP4AST.IfExpr node);
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

開発者は**具象**サブクラスを記述する:

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

    // ... 他のすべてのevalXxxメソッドの実装
}
```

この設計は3つの保証を提供する:

1. **完全性**: 文法が新しい`@mapping`ルールを追加すると、sealed interfaceは新しい許可サブタイプを獲得し、生成されたswitchは非網羅的となり、新しい`evalXxx`メソッドが追加されるまでコンパイラが具象クラスを拒否する。
2. **再生成安全性**: 再生成されるのは抽象基底クラスのみ。ドメイン固有の評価ロジックを含む具象サブクラスは決して上書きされない。
3. **デバッグ統合**: 生成された基底クラスの`DebugStrategy`フックにより、手書きクラスにコードを書くことなくDAPサーバーを通じたステップデバッグが可能になる。

GGPアプローチは同一の文法から複数の評価戦略もサポートする。tinyexpressionプロジェクトは同じ生成されたベースを拡張する3つの具象エバリュエータを実装している:
- `P4TypedAstEvaluator`: ASTを直接解釈し、`Object`値を返す。
- `P4TypedJavaCodeEmitter`: ASTを走査し、ランタイムコンパイルのためのJavaソースコードを出力する。
- `P4DefaultJavaCodeEmitter`: デフォルト評価パターンのためのテンプレートベースのエミッター。

### 3.6 PropagationStopperの操作的意味論

小ステップ操作的意味論を用いて伝播ストッパー階層を形式化する。パーサーの状態を`s = (tk, inv)`とし、`tk in {consumed, matchOnly}`および`inv in {true, false}`とする。各伝播ストッパーは`S = {consumed, matchOnly} x {true, false}`上の自己写像である。

**推論規則。** `p.parse(ctx, s) => r`と書き、パーサー`p`がコンテキスト`ctx`と状態`s`のもとで結果`r`を生成することを表す。

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

**代数的性質。** 4つのストッパーと恒等写像は、4要素集合`S`上の自己写像の有限集合を形成する。主要な性質:

*冪等性:*

- `AllStop . AllStop = AllStop`（定数写像は冪等）
- `DoConsume . DoConsume = DoConsume`
- `StopInvert . StopInvert = StopInvert`
- `NotProp . NotProp = Id`（自己逆、冪等ではない）

*吸収:*

- `AllStop . X = AllStop` 任意のストッパー`X`に対して（AllStopは右零元）

*選択された合成:*

- `DoConsume . StopInvert = AllStop`
- `StopInvert . DoConsume = AllStop`
- `AllStop . DoConsume = AllStop`
- `NotProp . NotProp = Id`

*非可換性:* ストッパー代数は一般に可換ではない。`DoConsume . StopInvert = StopInvert . DoConsume = AllStop`であるが、`StopInvert . NotProp != NotProp . StopInvert`である。具体的には:

- `StopInvert . NotProp`: `(tk, inv) -> (tk, not(inv)) -> (tk, false) = StopInvert`
- `NotProp . StopInvert`: `(tk, inv) -> (tk, false) -> (tk, true)` = `ForceInvert`（第2成分に対する新しい定数写像）

これは4つのストッパーが合成のもとで可換モノイドを形成しないことを示すが、`Id`を恒等元、`AllStop`を右吸収元とする有限非可換モノイドを形成する。

### 3.7 モナディック解釈

PropagationStopper階層とContainerParserは、よく知られたモナディック抽象化との正確な対応関係を持つ。これらの対応を明示的に認め、JavaベースのRealizationが見落としではなく意図的な設計選択である理由を説明する。

| unlaxerの概念 | モナドとの対応 | 説明 |
|-----------------|----------------------|-------------|
| PropagationStopper | Reader monadの`local` | 環境パラメータのローカル修正 |
| AllPropagationStopper | `local (const (C,F))` | 定数環境に置換 |
| DoConsumePropagationStopper | `local (\(_,i)->(C,i))` | 第1成分のみを固定 |
| InvertMatchPropagationStopper | `local (\(t,_)->(t,F))` | 第2成分のみを固定 |
| NotPropagatableSource | `local (\(t,i)->(t,not i))` | 第2成分を反転 |
| ContainerParser\<T\> | Writer monadの`tell` | 副作用なしにメタデータを蓄積 |
| ErrorMessageParser | `tell [ErrorMsg msg]` | エラーメッセージの蓄積 |
| SuggestsCollectorParser | `tell [Suggestions xs]` | 補完候補の蓄積 |
| ParseContext.begin/commit/rollback | State monadのget/put（バックトラッキング付き） | パーサー状態の保存・復元 |
| Parsed.FAILED | ExceptTの`throwError` | パース失敗の伝播 |

Haskellでは、フレームワーク全体をモナドトランスフォーマースタックとして表現できる:

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

-- Containerメタデータ
errorMessage :: String -> WriterT [Metadata] Parser ()
errorMessage msg = tell [ErrorMsg msg]
```

**Javaクラス階層とモナドトランスフォーマーのどちらを選ぶか。** モナディック定式化ではなくJavaのクラス階層を選択した理由は3つある:

1. **デバッガビリティ。** Javaのクラスベースの PropagationStopper階層はIDEデバッガで直接見える。開発者は`AllPropagationStopper.parse()`にブレークポイントを設定し、変数ペインで`tokenKind`と`invertMatch`を検査し、伝播ロジックをステップ実行できる。モナドトランスフォーマースタックでは、同等の状態がネストされた`runReaderT`/`runWriterT`/`runStateT`クロージャに埋もれ、標準的なデバッガには不透明である。

2. **IDE支援。** Javaの型階層は標準的なIDE機能を可能にする: 「AllPropagationStopperのすべての参照を検索」、「PropagationStopperの実装に移動」、「型階層を表示」。これらの操作はすべてのJava IDEで直接サポートされている。Haskellでは同等の操作に専用ツール（HLS）が必要であり、ユーザー定義のDSLには拡張されない。

3. **LSP/DAP生成。** 我々のフレームワークは文法からLSPサーバーとDAPサーバーを生成する。生成されたDAPサーバーはパーサーコンビネータツリーのステップスルーデバッグを提供し、PropagationStopperの遷移をデバッグイベントとして表示する。この生成パイプラインはJavaクラス構造上で動作し、モナディック定式化には根本的な再設計が必要となる。既存のHaskellパーサーコンビネータフレームワークで文法仕様からLSPまたはDAPサーバーを生成するものはない。

モナディック解釈は我々の抽象化の形式的特徴づけとして価値があり、そのように提示する。しかし、unlaxer-parserの実用的価値は個々の抽象化 -- 対応テーブルが示すように、よく知られたモナディックな対応物を持つ -- にあるのではなく、単一の文法仕様から6つの整合的な成果物を生成する統一コード生成パイプラインへの統合にある。モナディック構造は「どのようにパースするか」を説明するが、「単一の文法からどのように6つの成果物すべてを生成するか」は説明しない。

### 3.8 文脈自由を超えて: MatchedTokenParser

標準的なPEGと文脈自由文法は特定の重要なパターンを認識できない。典型的な例は回文言語`L = { w w^R | w in Sigma* }`であり、これは文脈依存である。XMLスタイルのタグ対応（開始タグ`<foo>`と対応する閉じタグ`</foo>`のマッチング）もう一つの実用的な例である。従来のパーサージェネレータでは、これらのパターンは文法形式の外のアドホックなコードで処理しなければならない。

unlaxer-parserの`MatchedTokenParser`は、マッチしたコンテンツをキャプチャし再生する -- オプションで反転して -- コンビネータレベルの機構を提供し、パーサーコンビネータフレームワーク内で文脈依存パターンを認識する。

**設計。** `MatchedTokenParser`は先行する`MatchOnly`（先読み）パーサーと連携して動作する。`MatchOnly`パーサーは入力を消費せずにマッチし、認識されたコンテンツを確立する。`MatchedTokenParser`はこのキャプチャされたコンテンツにアクセスし、いくつかの操作を提供する:

- **直接再生**: 現在の位置で同じコンテンツをマッチする（XMLタグ対応に有用）。
- **`slice`**: キャプチャされたコンテンツのサブ範囲を、設定可能な開始、終了、ステップパラメータで抽出する。
- **`effect`**: キャプチャされたコンテンツに任意の変換を適用する（例: 反転）。
- **`pythonian`**: Pythonスタイルのスライス記法（例: `"::-1"`で反転）を簡潔な指定に使用する。

**理論的着想: Macro PEG。** MatchedTokenParserの設計はMacro PEG [Mizushima 2016]に着想を得ている。Macro PEGは回文のような文脈依存パターンを扱うためにパラメータ化されたルールでPEGを拡張する。Macro PEGが文法レベルの拡張（パラメータを受け取るルール）を通じてこれを達成するのに対し、unlaxerはオブジェクト指向アプローチを取る: MatchedTokenParserはコンビネータレベルでマッチしたコンテンツをキャプチャし、トークン操作のための合成可能な操作を提供する。両方のアプローチは PEGの認識能力を文脈自由言語を超えて拡張するが、unlaxerの設計はJavaの型システムとIDEツーリングと自然に統合される。

**回文認識: 5つの実装。** 以下の5つの実装は、回文認識に対するMatchedTokenParserの表現力を示す。5つすべてが`Usage003_01_Palidrome.java`でテストされ、"a"、"abcba"、"abccba"、"aa"のような文字列を正しく認識し、"ab"のような非回文を正しく拒否する。

*実装1: sliceWithWord。* 入力を前半、ピボット（奇数長の場合）、前半の反転に分解する:

```java
Chain palindrome = new Chain(
    wordLookahead,
    matchedTokenParser.sliceWithWord(word -> {
        boolean hasPivot = word.length() % 2 == 1;
        int halfSize = (word.length() - (hasPivot ? 1 : 0)) / 2;
        return word.cursorRange(new CodePointIndex(0), new CodePointIndex(halfSize),
            SourceKind.subSource, word.positionResolver());
    }),
    matchedTokenParser.sliceWithWord(word -> { /* ピボット抽出 */ }),
    matchedTokenParser.slice(word -> { /* 前半の反転 */ }, true)
);
```

*実装2: sliceWithSlicer。* 範囲指定に`Slicer` APIを使用する:

```java
matchedTokenParser.slice(slicer -> {
    boolean hasPivot = slicer.length() % 2 == 1;
    int halfSize = (slicer.length() - (hasPivot ? 1 : 0)) / 2;
    slicer.end(new CodePointIndex(halfSize));
})
```

*実装3: effectReverse。* Javaの`StringBuilder.reverse()`を使用して完全な反転を適用する:

```java
matchedTokenParser.effect(word ->
    StringSource.createDetachedSource(new StringBuilder(word).reverse().toString()))
```

*実装4: sliceReverse。* 型安全なSlicer APIを通じてstep=-1による反転を使用する:

```java
matchedTokenParser.slice(slicer -> slicer.step(-1))
```

*実装5: pythonian。* Pythonスタイルのスライス記法を使用する:

```java
matchedTokenParser.slice(slicer -> slicer.pythonian("::-1"))
```

`pythonian`メソッドは、Pythonのスライス記法に精通した開発者向けのコンビニエンスAPIを提供する。`"::-1"`はPythonの`[::-1]`文字列反転イディオムを鏡像する。本番使用には、Slicerビルダーインターフェースを通じてコンパイル時検証を提供する型安全な`slice(slicer -> slicer.step(-1))` API（実装4）を推奨する。`pythonian`メソッドはパース時に入力バリデーションを実行し、不正な形式のスライス文字列を`IllegalArgumentException`で拒否する。型安全なSlicer APIと`pythonian`コンビニエンスメソッドは補完的である: 前者は安全性とIDEの発見可能性を優先し、後者はPythonとJavaのコードベースをまたいで作業する開発者のために簡潔さを優先する。

**XMLタグ対応。** 回文を超えて、MatchedTokenParserは開始タグと閉じタグのXMLスタイルのマッチングをサポートする。文法は`<tagname>`からタグ名をキャプチャし、閉じタグ`</tagname>`の位置でそれを再生でき、パースレベルで構造的一貫性を保証する（パース後の検証ではなく）。

**Macro PEGとの比較。** 表2はPEGの文脈自由認識を超える拡張アプローチを比較する:

| システム | アプローチ | CFG超え | ホスト言語との統合 | IDE支援 |
|--------|----------|-----------|-------------------------------|-------------|
| PEG (Ford 2004) | 文法記法 | No | N/A | No |
| Macro PEG (Mizushima 2016) | パラメータ化された文法ルール | Yes（文法レベル） | 限定的（スタンドアロン） | No |
| unlaxer MatchedTokenParser | コンビネータレベルのキャプチャ+再生 | Yes（オブジェクトレベル） | 完全なJava統合 | Yes (LSP/DAP) |

*表2: PEGの文脈自由言語を超える拡張アプローチ。Macro PEGは文法レベルで動作し、MatchedTokenParserはホスト言語内のコンビネータレベルで動作する。*

Macro PEGのパラメータ化されたルールは文脈依存パターンのためのクリーンな文法レベルの形式を提供するが、カスタムパーサージェネレータを必要とし、ホスト言語のツーリングとは統合しない。MatchedTokenParserは文法レベルのエレガンスをJavaの型システム、IDEデバッガ、unlaxerコード生成パイプラインとの完全な統合と交換する。キャプチャされたコンテンツは標準的なJavaツールで検査、変換、デバッグできるファーストクラスのJavaオブジェクトである。

---

## 4. 実装

### 4.1 パーサーコンビネータライブラリ (unlaxer-common)

`unlaxer-common`モジュールはパーサーコンビネータライブラリを実装する436のJavaソースファイルを含む。パーサーはいくつかのカテゴリに分類される:

**コンビネータパーサー**は他のパーサーを合成する:
- `Chain` / `LazyChain`: 順次合成（PEGシーケンス `e1 e2 ... en`）。
- `Choice` / `LazyChoice`: 順序付き選択（PEG `e1 / e2 / ... / en`）。
- `LazyZeroOrMore`、`LazyOneOrMore`、`LazyOptional`: 繰り返しとオプション。
- `LazyRepeat`、`ConstructedOccurs`: 明示的なカウント制御による有界繰り返し。
- `Not`: PEG否定述語（ゼロ幅否定先読み）。
- `MatchOnly`: PEG肯定述語（ゼロ幅肯定先読み）。
- `NonOrdered`: 非順序集合マッチング（すべての選択肢がマッチしなければならない、順序は任意）。

**LazyバリアントとConstructedバリアント**は循環文法のサポートに対応する。再帰文法（例: `Expression ::= ... '(' Expression ')' ...`）では、`Expression`のパーサーが自身を参照する。積極的な構築は無限再帰を引き起こす。`Lazy`バリアントは最初のパースまで子パーサーの解決を遅延させ、循環を断ち切る。`Constructed`バリアントは構築時に子が既知の非再帰ルールに使用される。

**ASTフィルタリング**はどのトークンがパースツリーに現れるかを制御する:
- `ASTNode`: パーサーのトークンがASTに含まれるべきことを示すマーカーインターフェース。
- `ASTNodeRecursive`: このパーサーとその子孫からのトークンが含まれる。
- `NotASTNode`および`NotASTNode`付き`TagWrapper`: ASTビューからトークンを除外。
- `Token.filteredChildren`フィールドはトークンツリーのAST専用ビューを提供し、`Token.children`は完全なパースツリーを保持する。

**トランザクションベースのバックトラッキング**（`ParseContext`内）は順序付き選択セマンティクスを可能にする:
- `begin(Parser)`: 現在のカーソル位置を保存する（セーブポイントの作成）。
- `commit(Parser, TokenKind)`: パースされたトークンを受け入れ、カーソルを進める。
- `rollback(Parser)`: カーソルをセーブポイントに復元し、トークンを破棄する。

このトランザクションモデルはParsecのコミット選択セマンティクスよりも一般的である: 任意のパーサーがトランザクションを開始でき、ネストされたトランザクションが完全にサポートされる。

### 4.2 コードジェネレータ (unlaxer-dsl)

`unlaxer-dsl`モジュールは6つのコードジェネレータとサポートインフラを含む。

**MapperGenerator**は最も複雑な生成ロジックを処理する。主要な課題は**マルチルールマッピング**である: 複数の文法ルールが同じASTクラスにマッピングできる。例えば、`NumberExpression`と`NumberTerm`の両方が`BinaryExpr`にマッピングされる。マッパーは指定されたパースツリーノードがどのルールによって生成されたかを正しく識別し、適切なフィールドを抽出しなければならない。`allMappingRules`機構は`@mapping`クラス名を共有するすべてのルールを収集し、各マッピングルールを順に試すディスパッチャを生成する。

もう一つの課題は**findDirectDescendants**である: パースツリーから`@left`、`@op`、`@right`を抽出する際、マッパーはアノテーション付き要素にマッチする直接の子を見つけなければならず、ネストされたサブ式に降りてはならない。`NumberTerm`要素を含む`NumberExpression`はトップレベルのタームを抽出し、その中のファクターは抽出しない。

**EvaluatorGenerator**はGGPスケルトンを生成する。網羅的switchディスパッチに加え、各ノード評価の前後に呼ばれる`DebugStrategy`フックを生成する。生成された`StepCounterStrategy`実装は評価ステップをカウントし、特定のステップカウントで一時停止するよう設定でき、DAPサーバーのステップオーバー動作を可能にする。

**ParserGenerator**は優先順位と結合性を処理する。`@leftAssoc`が存在する場合、ジェネレータはベースタームに続く演算子-ターム対の`LazyZeroOrMore`を持つ`LazyChain`を生成する。`@rightAssoc`の場合、再帰チェーンを生成する。`@precedence(level=N)`アノテーションは複数の式型が競合する際の選択肢の順序付けに使用される。

### 4.3 5つの実行バックエンド

tinyexpressionプロジェクトは、同じ文法から派生した5つの異なる実行バックエンドの実装によりフレームワークの柔軟性を実証する:

| バックエンド | 主要クラス | 戦略 | ステップデバッグ | コード生成 |
|---------|-----------|----------|------------|----------|
| `JAVA_CODE` | `JavaCodeCalculatorV3` | レガシーパーサー → Javaソース → JITコンパイル | No | Yes |
| `AST_EVALUATOR` | `AstEvaluatorCalculator` | レガシーパーサー → 手書きAST → ツリー走査エバリュエータ | No | No |
| `DSL_JAVA_CODE` | `DslJavaCodeCalculator` | レガシーパーサー → DSL生成Javaソース | No | Yes |
| `P4_AST_EVALUATOR` | `P4AstEvaluatorCalculator` | UBNF生成パーサー → sealed AST → `P4TypedAstEvaluator` | Yes | No |
| `P4_DSL_JAVA_CODE` | `P4DslJavaCodeCalculator` | UBNF生成パーサー → sealed AST → Javaコードエミッター | Yes | Yes |

P4バックエンド（4行目と5行目）はUBNF生成パーサーとASTを使用し、以前のバックエンドはレガシー手書きパーサーを使用する。パリティ契約はすべてのバックエンドがサポートされる式に対して同等の結果を生成することを保証し、`BackendSpeedComparisonTest`、`P4BackendParityTest`、`ThreeExecutionBackendParityTest`によって検証される。

---

## 5. 評価

### 5.1 ケーススタディ1: tinyexpression

tinyexpressionは金融トランザクション処理におけるユーザー定義関数（UDF）として使用される本番式評価器であり、現在本番環境で月間**10^9（10億）トランザクション**を処理している。設定可能な精度（float、double、int、long、BigDecimal、BigInteger）を持つ数値式、文字列式、ブール式、条件式（if/else）、パターンマッチング（match）、変数バインディング、ユーザー定義メソッド、型ヒント、埋め込みJavaコードブロックをサポートする。

UBNF文法（`tinyexpression-p4-complete.ubnf`）は**520行の文法仕様**に及び、完全なP4文法を定義する。この文法から、コードジェネレータは6つの生成ファイルにわたり約**2,000行**のJavaソースを生成する。手書きのエバリュエータロジック（`P4TypedAstEvaluator.java`）は**542行の評価メソッド（`evalXxx`）**であり、設定可能な数値型での数値演算、変数解決、比較操作、if/elseおよびmatch評価、文字列・ブール値変換を含むすべての式型をカバーする。

開発者が保守する総投資量 -- 文法と手書きエバリュエータロジック -- は約**1,062行**（文法520行 + エバリュエータ542行）であり、パーサー、型付きAST、マッパー、エバリュエータ、LSPサーバー、DAPサーバーを含む完全な言語実装を得る。約2,000行の生成コードは自動的に生成され開発者が保守するものではないため、このカウントには含まれない。

### 5.2 性能ベンチマーク

**テスト環境。** すべてのベンチマークは以下のプラットフォームで実行された: JDK 21 (Eclipse Temurin 21.0.2+13)、Ubuntu 22.04 LTS、AMD Ryzen 9 5950X（16コア/32スレッド、ベース3.4 GHz/ブースト4.9 GHz）、64 GB DDR4-3200 RAM、デフォルトG1GCガベージコレクター（カスタムチューニングなし）。

`BackendSpeedComparisonTest`は式`3+4+2+5-1`（リテラル演算）と`$a+$b+$c+$d-$e`（変数演算）を使用し、5,000回のウォームアップ後に50,000回のイテレーションでバックエンド間の評価性能を測定する。

**セクション1: リテラル演算**

| バックエンド | 説明 | us/call | ベースライン比 |
|---------|-------------|---------|--------------|
| (A) compile-hand | JVMバイトコード (JavaCodeCalculatorV3) | ~0.04 | 1.0x |
| (E2) P4-typed-reuse | Sealed switch、インスタンス再利用 | ~0.10 | 2.8x |
| (E) P4-typed-eval | Sealed switch、呼び出しごとに新規インスタンス | ~0.33 | 8.9x |
| (B) ast-hand-cached | 手書きAST、パース済み | ~0.42 | 11.4x |
| (C) ast-hand-full | 手書きAST、パース+構築+評価 | ~2.50 | ~68x |

*表3: リテラル演算の評価レイテンシ。compile-handバックエンドは理論的最適値（JITコンパイルされたJavaバイトコード）を表す。P4-typed-reuseはこの最適値の3倍以内。*

主要な結果は、P4-typed-reuseバックエンドがcompile-handベースラインの**2.8倍**を達成しつつ、コンパイラではなくインタプリタである点である。これは注目に値する: sealed-switchエバリュエータはツリー走査解釈を行っているにもかかわらず、単純な式ではJITコンパイルされたコードと競争力がある。JVMのJITコンパイラはswitchケースをインライン化し、仮想ディスパッチを排除し、レコードインスタンスにスカラー置換を適用できる。

手書きASTエバリュエータ（ast-hand-cached）はコンパイルコードの11.4倍遅く、sealed-interfaceアプローチ（2.8倍）がアノテーション駆動のツリー評価に対しても意味のある性能上の優位性を提供することを示している。

**セクション2: 変数演算**

| バックエンド | 説明 | us/call | ベースライン比 |
|---------|-------------|---------|--------------|
| (F) compile-hand | 変数ルックアップ付きJVMバイトコード | ~0.06 | 1.0x |
| (H) P4-typed-var | 変数AST付きSealed switch | ~0.15 | 2.5x |
| (G) AstEvalCalc | 完全なAstEvaluatorCalculatorパス | ~8.50 | ~142x |

変数式は同様の相対的性能を示す。P4-typedバックエンドはJITコンパイルコードに対して2.5倍のオーバーヘッドを維持し、sealed-switch評価が式の種類に関わらず一貫してスケールすることを確認している。

### 5.3 ケーススタディ2: 回文認識

文脈自由パーシングを超えるunlaxerの能力を実証し、tinyexpressionを超えた第2のケーススタディを提供するため、`MatchedTokenParser`を使用した回文認識を提示する。回文言語`L = { w w^R | w in Sigma* }`はPEGまたは文脈自由文法では認識できない典型的な文脈依存言語である。

5つの異なる回文認識器を実装した（Section 3.8）。すべて同じ`MatchedTokenParser`インスタンスを異なる操作で使用する。5つの実装すべてが`Usage003_01_Palidrome.java`で以下のテストベクターに対してテストされる:

| 入力 | 期待結果 | 5つの実装すべてが一致 |
|-------|----------|----------------------------|
| "a" | マッチ | Yes |
| "abcba" | マッチ（奇数長） | Yes |
| "abccba" | マッチ（偶数長） | Yes |
| "aa" | マッチ（自明） | Yes |
| "ab" | マッチしない | Yes |

5つの実装は明示的なインデックス操作（`sliceWithWord`）から最も簡潔なpythonian記法（`slicer.pythonian("::-1")`）までのスペクトラムに及ぶ。この多様性はMatchedTokenParserが正確性を維持しつつ多様なプログラミングスタイルに十分な表現力を提供することを示す。

回文ケーススタディは2つの主張を検証する: (1) MatchedTokenParserはunlaxerの認識能力を文脈自由言語を超えて拡張する、(2) コンビネータレベルのアプローチはJavaのテストインフラ（JUnit）とデバッグツール（標準IDEデバッガが各`slice`/`effect`操作をステップ実行可能）と自然に統合される。

### 5.4 開発工数比較

tinyexpressionについて3つのアプローチのもとで観察された開発工数を報告する。これらの数値は我々のケーススタディから得られたものであり、tinyexpressionの機能セットと複雑さを持つ言語の代表値として解釈されるべきであり、普遍的に一般化可能な主張としてではない。

| アプローチ | 文法行数 | 手書きロジック | 開発者保守の総行数 | 観察された工数 |
|----------|--------------|-------------------|--------------------------------|-----------------|
| フルスクラッチ（パーサー + AST + マッパー + エバリュエータ + LSP + DAP） | N/A（文法DSLなし） | ~15,000 | ~15,000 | ~8週間 |
| ANTLR + 手書きエバリュエータ + 手書きLSP/DAP | ~200（ANTLR文法） | ~7,800 | ~8,000 | ~5週間 |
| unlaxer（文法 + evalXxxメソッド） | 520（UBNF） | 542（evalXxx） | 1,062 | ~3日 |

*表4: ケーススタディで観察された開発工数比較。「開発者保守の総行数」は開発者が記述し保守する文法と手書きコードのみをカウントする。生成コード（unlaxerアプローチでは~2,000行）は自動的に生成され開発者が保守するものではないため、すべてのLOCカウントから除外される。*

「フルスクラッチ」の見積もりは以下の内訳に基づく: パーサー（~2,000行）、AST型（~1,500行）、マッパー（~1,000行）、エバリュエータ（~2,000行）、LSPサーバー（~2,500行）、DAPサーバー（~1,500行）。ANTLRの見積もりは生成されたパーサーとAST（パーサーとASTの工数を削減）を考慮するが、手書きのマッパー、エバリュエータ、LSP、DAPを必要とする。unlaxerの数値は実際のtinyexpression実装を反映する: 520行のUBNF文法仕様と542行の手書きエバリュエータロジック（P4TypedAstEvaluator.javaのevalXxxメソッド）。

保守可能コードの**14倍の削減**（~15,000行から~1,062行）は、我々のケーススタディで観察された主要な実用的利益である。しかし、認知的負荷の削減はより重要であると言える: 開発者は文法ルールと評価セマンティクスの観点で考え、パーサーの配管、トークンツリー走査、プロトコルメッセージ処理の観点では考えない。

### 5.5 本番デプロイメント

tinyexpressionは金融トランザクション処理システム内のUDF（ユーザー定義関数）として本番デプロイされている。システムは月間**10^9（10億）トランザクション**を処理し、tinyexpressionが各トランザクションでユーザー定義式を評価して分類、ルーティング、派生値計算を行う。

主要な本番メトリクス:
- **トランザクション量**: 月間10^9（持続的に~385評価/秒、バーストピークはさらに高い）。
- **式の複雑さ**: 本番の式は単純な演算（`$amount * 1.1`）から変数バインディングとメソッド呼び出しを伴う複雑な条件ロジック（式あたり50以上のASTノード）まで及ぶ。
- **本番使用バックエンド**: P4-typed-reuseバックエンド（インスタンス再利用によるsealed-switchエバリュエータ）が本番で使用され、一般的な式でサブマイクロ秒の評価あたりレイテンシを達成している。
- **信頼性**: sealed interfaceの網羅性保証により、文法変更が無言の評価失敗を導入できないことを保証する -- 欠落する評価メソッドはデプロイ前のコンパイル時に捕捉される。

### 5.6 LLM支援開発

unlaxer-parserの型安全で生成されたアーキテクチャは、LLM支援開発ワークフローに定性的な利益を提供する。我々の経験は以下の観察を示唆する:

**トークン効率。** フレームワークなしでは、LLMはパーサーコンビネータ、AST型、マッパーロジック、エバリュエータコードをスクラッチから生成しなければならず、通常かなりのコンテキストと生成トークンを必要とする。unlaxerでは、LLMは`evalXxx`メソッドボディのみを生成すればよく、トークン予算を大幅に削減する。

**ガイダンスとしての型安全性。** sealed interfaceの網羅性保証により、LLMはASTノード型の処理を「忘れる」ことができない -- Javaコンパイラがコードを拒否する。文法が新しい`@mapping`ルールで拡張されると、生成されたエバリュエータ基底クラスは新しい抽象メソッドを獲得する。未実装メソッドをリストするコンパイラエラーは、LLMが追加のプロンプトなしに従える正確で機械可読なTODOリストとして機能する。この「コンパイラ・アズ・オーケストレータ」パターンは、LLMに必要な情報（どのメソッドをどのパラメータ型で実装するか）を正確に、余計な情報なく提供するため効果的である。

LLM支援開発の利益の厳密な評価（例: トークン使用量とタスク完了時間を測定する統制実験）は将来の研究として残る。

---

## 6. 議論

### 6.1 制限事項

**Java専用の生成。** 現在のコードジェネレータはJavaソースファイルのみを生成する。JVMベース言語（Kotlin、Scala、Clojure）は生成されたJavaコードと相互運用できるが、非JVM言語のネイティブサポートは利用できない。文法記法（UBNF）は言語非依存であるが、ジェネレータパイプラインとパーサーコンビネータランタイムはJava固有である。

**PEGベースのパーシング。** UBNFはPEGセマンティクス（順序付き選択）を使用し、曖昧な文法は選択肢の順序によって決定論的に解決される。これはほとんどのDSLでは特徴であるが、曖昧性報告を必要とする言語（例: 自然言語処理）や順序付き選択が意外な結果を生む言語にとっては制限である。曖昧な文法を扱いパースフォレストを生成できるGLRパーサーはサポートされない。

**エラー回復。** PEGの順序付き選択セマンティクスはロバストなエラー回復を困難にする。選択肢が失敗すると、パーサーはバックトラックして次の選択肢を試す。「部分マッチ」を報告したり、エラーのある入力をスキップしてパーシングを続行する機構はない。`ErrorMessageParser`はポイント・オブ・フェイリャー診断を提供するが、パーサーは現在、ANTLRに見られる「エラー回復」戦略（トークン挿入、トークン削除、パニックモード回復）をサポートしていない。生成されたLSPサーバーの診断能力は現在、完全なパースが試みられるシナリオに限定されている。部分入力の診断精度は改善の余地がある。

**単一の本番ユーザー。** tinyexpressionプロジェクトがフレームワークの主要な本番ユーザーである。回文ケーススタディは文脈依存認識タスクへのフレームワークの適用可能性を示すが、サードパーティ開発者による多様なDSLプロジェクトでのより広い検証が、設計選択の汎用性を確認するために必要である。

**不完全な文法カバレッジ。** P4文法はtinyexpression言語のすべての機能をまだカバーしていない。外部Javaメソッド呼び出し、文字列スライシング（`$msg[0:3]`）、一部の文字列メソッドなどのいくつかの構文は、レガシーパーサーによって処理され、以前のバックエンドにフォールバックする。共存モデル（P4パーサーとレガシーフォールバック）は機能するが、複雑さを増す。

**ベンチマーク手法。** Section 5.2の性能ベンチマークはJMH（Java Microbenchmark Harness）ではなくカスタムベンチマークハーネス（`BackendSpeedComparisonTest`）を使用している。ウォームアップイテレーションと繰り返し測定を使用しているが、JMHはJITコンパイル、ガベージコレクション、プロセス分離に対するより厳密な制御を提供する。将来の改訂でJMHベースのベンチマークを追加する予定である。桁レベルの関係（リフレクションからsealed-switchへの1,400倍の改善、sealed-switch対JITコンパイルの2.8倍のオーバーヘッド）はJMH測定でも維持されると考えるが、正確な数値は変動しうる。

### 6.2 MatchedTokenParserの認識能力

`MatchedTokenParser`は回文ケーススタディ（Section 5.3）で実証されたように、unlaxerの認識能力を文脈自由言語を超えて拡張する。自然な問いは、MatchedTokenParserの認識能力の上界は何かということである。

`slice`操作 -- 開始、終了、ステップパラメータを使用して以前にマッチしたコンテンツのサブ範囲を抽出する操作 -- に制限した場合、認識能力は少なくとも、有界長コンテンツの比較と再配置によって特徴づけられる文脈依存言語のクラスにまで拡張される。回文言語`L = { w w^R | w in Sigma* }`とXMLタグ対応はこのクラスの典型的な例である。sliceベースのキャプチャ・アンド・リプレイで拡張されたPEGが認識可能な正確な言語クラスの形式的特徴づけは未解決の問題であり、将来の理論的研究の方向性である。

`effect`操作が任意のJava関数で許される場合、認識能力は原理的にチューリング完全になる。`effect`関数がキャプチャされたコンテンツに対して任意の計算を実行できるためである。これはパーサージェネレータにおけるセマンティックアクション（例: ANTLRの埋め込みアクション、Yaccのアクションコード）が任意のパーサージェネレータをチューリング完全にできることと類似している。実際には、unlaxer文法で使用される`effect`操作は単純な変換（反転、大文字小文字変換、部分文字列抽出）に制限されており、理論的なチューリング完全性は実務上の懸念にならない。

### 6.3 妥当性への脅威

**内的妥当性。** 性能ベンチマークはJMHではなくカスタムテストハーネスを使用して実施された。ハーネスはウォームアップイテレーション（5,000回）と測定イテレーション（50,000回）を含むが、JVMフォーク分離、GCプレッシャー、JITティアードコンパイルをJMHの厳密さで制御していない。近似表記（~0.10 us/call）はこの制限を反映している。また、表4の開発工数見積もりは著者のunlaxerと従来アプローチの両方の経験に基づいており、フレームワークに不慣れな開発者には一般化できない可能性がある。

**外的妥当性。** 評価は2つのケーススタディに基づく: tinyexpression（本番式評価器）と回文認識（文脈依存パターン）。両方ともフレームワークの著者によって開発された。サードパーティのDSL実装はフレームワークの汎用性と使いやすさのより強い証拠を提供するだろう。工数削減の主張は我々のケーススタディからの観察として解釈されるべきであり、普遍的な予測としてではない。

**構成概念妥当性。** コード行数が開発工数の主要メトリクスとして使用され、時間見積もりで補完されている。LOCはコード品質、保守性、テストカバレッジを捕捉しない粗い尺度である。「フルスクラッチ」のLOC見積もりは実際の実装ではなく見積もりである。

### 6.4 将来の研究

**JMHベンチマーク。** 性能ベンチマークをJMHに移行する予定である。`@Benchmark`、`@Warmup(iterations=10, time=1s)`、`@Measurement(iterations=10, time=1s)`、`@Fork(3)`を使用し、平均、標準偏差、99パーセンタイルレイテンシを報告する。

**追加のケーススタディ。** サードパーティDSL実装による外部検証が将来の評価の主要ターゲットである。合成文法ベンチマーク（ルール数、再帰深度、`@mapping`密度を変化）もスケーラビリティデータを提供するだろう。

**宣言的評価アノテーション。** UBNFを`@eval`アノテーションで拡張し、文法内で評価戦略を直接指定できるようにする予定であり、一般的なパターンの手書きエバリュエータコードをさらに削減する。

**エラー回復。** PEG互換のエラー回復戦略（現在のポイント・オブ・フェイリャー診断を超える）の研究が、不完全な入力に対する生成LSPサーバーの処理を改善するために必要である。

**インクリメンタルパーシング。** 現在のパーサーは変更ごとに入力全体を再パースする。IDE統合（LSP）のため、インクリメンタルパーシング -- 変更された領域のみを再パースし、変更されていない領域のパース結果を再利用する -- は大きなドキュメントの応答性を大幅に改善するだろう。

**多言語コード生成。** UBNF文法からTypeScript、Python、Rustのソースを生成することで、フレームワークの適用可能性をJVMエコシステムを超えて拡張する。

**圏論的形式化。** PropagationStopper階層の圏論的定式化 -- ストッパーをパーサー状態の適切な圏における自己準同型として特徴づける -- は将来の理論的研究の興味深い方向であるが、Section 3.6の操作的意味論がストッパーの動作についての実践的推論には十分であることに注意する。

**トランザクション意味論。** ParseContextのbegin/commit/rollbackトランザクションモデルの操作的意味論を形式化することは、Section 3.6のPropagationStopper意味論を補完し、パーサーの状態管理のより完全な形式的記述を提供するだろう。

**コンビニエンスAPIの中立的命名。** Slicer APIの`pythonian`メソッド名はそのPython起源を明示的に参照している。より言語中立的な名前（例: `sliceNotation`）を将来のAPI改訂で検討し、言語間命名依存を低減する可能性がある。

---

## 7. 結論

単一のUBNF文法仕様から6つの相互に関連する成果物 -- パーサー、AST型、パースツリーからASTへのマッパー、エバリュエータスケルトン、LSPサーバー、DAPサーバー -- を生成するJava 21フレームワーク、unlaxer-parserを提示した。フレームワークはDSL開発の根本的な問題に対処する: 互いに整合的であり続けなければならない複数の密結合サブシステムを構築・保守する必要性である。

4つの貢献はこの統一生成における特定の課題に対処する:

1. **伝播制御**は、2つの直交する次元（`TokenKind`と`invertMatch`）で動作する4つの伝播ストッパークラスの階層を通じて、パーシングモードがコンビネータツリーを伝播する方法のきめ細かく合成的な制御を提供する。形式的な操作的意味論（Section 3.6）を提示し、ストッパー階層の代数的性質を実証し、Reader monadの`local`との正確な対応を示した（Section 3.7）。この貢献の価値は、モナディック抽象化自体 -- よく知られたもの -- にあるのではなく、パーサーコンビネータの特定の設計パターンとしての識別、JavaにおけるファーストクラスAPIとしての実現、コード生成パイプラインへの統合にある。

2. **メタデータ搬送パースツリー**は`ContainerParser<T>`を通じて、単一のパースパスでエラーメッセージと補完候補をパースツリーに直接埋め込むことを可能にする。これはWriter monadの`tell`操作に対応する。実用的な利益は、LSP機能がASTと同じパースパスから導出され、整合性が保証されることである。

3. **エバリュエータ向けGeneration Gap Pattern**は、Java 21のsealed interfaceと網羅的switch式と組み合わせて、コンパイラによる完全性保証を提供する。文法が新しいASTノード型を追加すると、開発者が対応する評価メソッドを実装するまでコンパイラが手書きのエバリュエータを拒否する。

4. **MatchedTokenParser**はフレームワークの認識能力を文脈自由言語を超えて拡張し、コンビネータレベルでの回文認識とXMLタグ対応を可能にする。Macro PEG [Mizushima 2016]に着想を得て、MatchedTokenParserは合成可能なslice、effect、pythonian操作によるキャプチャ・アンド・リプレイセマンティクスを提供する。

我々の評価は実用的なインパクトを実証する。金融トランザクション処理のUDFとして月間**10^9トランザクション**を処理する本番式評価器tinyexpressionは、フルスクラッチ実装と比較して保守可能コードの**14倍の削減**（~15,000行から~1,062行）を達成する。性能ベンチマークはリフレクションベースからsealed-switch評価への**1,400倍の改善**を示し、sealed-switchエバリュエータはJITコンパイルされたバイトコードの**2.8倍**以内で動作する。第2のケーススタディはMatchedTokenParserを使用した5つの異なる回文認識器実装を実証し、文脈依存パターンを扱うフレームワークの能力を検証する。

フレームワークはMITライセンスのもとオープンソースソフトウェアとして利用可能であり、Maven Centralに`org.unlaxer:unlaxer-common`および`org.unlaxer:unlaxer-dsl`として公開されている。

---

## 参考文献

[1] Bettini, L. 2016. *Implementing Domain-Specific Languages with Xtext and Xtend*. 第2版. Packt Publishing.

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

## 付録A: 完全なTinyCalc例

以下の最小限のUBNF文法とエバリュエータが完全な生成パイプラインを示す:

**文法 (TinyCalc.ubnf):**

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

**生成されたsealed AST (TinyCalcAST.java):**

```java
public sealed interface TinyCalcAST permits TinyCalcAST.BinaryExpr {
    record BinaryExpr(BinaryExpr left, List<String> op, List<BinaryExpr> right)
        implements TinyCalcAST {}
}
```

**手書きのエバリュエータ (TinyCalcEvaluatorImpl.java):**

```java
public class TinyCalcEvaluatorImpl extends TinyCalcEvaluator<Double> {
    @Override
    protected Double evalBinaryExpr(TinyCalcAST.BinaryExpr node) {
        if (node.left() == null && node.op().size() == 1) {
            return Double.parseDouble(node.op().get(0));  // リーフ
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

この35行の文法と17行のエバリュエータで、パーサー、AST、マッパー、エバリュエータ、LSPサーバー、DAPサーバーを含む完全な電卓が生成される。

---

## 付録B: PropagationStopper決定マトリクス

| ストッパー | 子へのTokenKind | 子へのinvertMatch | ユースケース |
|---------|-------------------|---------------------|----------|
| *（なし）* | 親の値 | 親の値 | デフォルト伝播 |
| `AllPropagationStopper` | `consumed` | `false` | サブ式のすべてのパーシングモードをリセット |
| `DoConsumePropagationStopper` | `consumed` | 親の値 | matchOnlyコンテキスト内で消費を強制 |
| `InvertMatchPropagationStopper` | 親の値 | `false` | NOTセマンティクスのサブパーサーへの伝播を防止 |
| `NotPropagatableSource` | 親の値 | `!親の値` | 反転フラグを反転して論理NOTを実装 |

---

## 付録C: PropagationStopper合成テーブル

以下のテーブルはすべてのストッパー対の合成`f . g`を示す。`f`は`g`の後に適用される。各セルは`S = {consumed, matchOnly} x {true, false}`上の結果の自己写像を含む:

|  f \ g | Id | AllStop | DoConsume | StopInvert | NotProp |
|--------|-----|---------|-----------|------------|---------|
| **Id** | Id | AllStop | DoConsume | StopInvert | NotProp |
| **AllStop** | AllStop | AllStop | AllStop | AllStop | AllStop |
| **DoConsume** | DoConsume | AllStop | DoConsume | AllStop | DoConsume' |
| **StopInvert** | StopInvert | AllStop | AllStop | StopInvert | ForceInvert |
| **NotProp** | NotProp | AllStop | DoConsume' | ForceInvert | Id |

ここで:
- `DoConsume'`: `(tk, inv) -> (consumed, not(inv))` -- 消費を強制しマッチを反転。
- `ForceInvert`: `(tk, inv) -> (tk, true)` -- invertMatchをtrueに強制。

5つの生成元（AllStop、DoConsume、StopInvert、NotProp、Id）は、4要素状態空間`S`上の正確に7つの異なる自己写像を生成する: 5つの生成元自体に加え、2つの派生写像（DoConsume'とForceInvert）。4要素集合上の自己写像の総数は4^4 = 256であるため、生成される部分モノイドは全変換モノイドの小さいが構造的に豊かな断片である。この部分モノイドは合成のもとで閉じている（上記テーブルのすべてのセルが7つの写像のいずれかである）。これにより、7つの写像が関数合成のもとで有限モノイドを形成することが確認される。恒等元はIdであり、AllStopは右吸収（零）元として機能する: すべての`X`に対して`AllStop . X = AllStop`。

注目すべき性質:
- `AllStop`は右吸収元: すべての`X`に対して`AllStop . X = AllStop`。
- `Id`は恒等元: すべての`X`に対して`X . Id = Id . X = X`。
- `NotProp`は自己逆（involution）: `NotProp . NotProp = Id`。
- `DoConsume . StopInvert = StopInvert . DoConsume = AllStop`（このペアでは可換）。
- `StopInvert . NotProp != NotProp . StopInvert`（一般に非可換）。

---

*ACM SIGPLAN International Conference on Software Language Engineering (SLE), 2026 採録。*

---

## ナビゲーション

[← インデックスに戻る](../INDEX.md)

| 論文 (JA) | 査読 |
|-----------|------|
| [← v2 論文](../v2/from-grammar-to-ide.ja.md) | [v2 査読](../v2/review-dialogue-v2.ja.md) |
| **v3 — 現在** | — |
| [v4 論文 →](../v4/from-grammar-to-ide.ja.md) | [v4 査読](../v4/review-dialogue-v4.ja.md) |
