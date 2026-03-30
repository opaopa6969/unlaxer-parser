# From Grammar to IDE: 単一の文法仕様からのパーサ、AST、エバリュエータ、LSP、DAPの統一生成

**著者: [unlaxer-parserの作者]**

*謝辞: 本論文の執筆、コード実装、および改訂にはClaude（Anthropic）を使用した。*

---

## 概要

ドメイン固有言語（DSL: Domain-Specific Language）の実装には、複数の相互関連するアーティファクトが必要である。すなわち、パーサ（parser）、抽象構文木（AST: Abstract Syntax Tree）の型定義、構文解析木からASTへのマッパ（mapper）、意味評価器（evaluator）、そしてLanguage Server Protocol（LSP）およびDebug Adapter Protocol（DAP）によるIDE支援である。実際には、これら6つのサブシステムは通常それぞれ独立に構築・保守されるため、コンポーネント間の不整合、コードの重複、および多大な保守負担を招く――たった1つの文法変更が、数千行の手書きコードに連鎖的に影響を及ぼしうるのである。本論文では、単一のUBNF（Unlaxer Backus-Naur Form）文法仕様から6つのアーティファクトすべてを生成するJava 21フレームワークであるunlaxer-parserを提示する。本論文の貢献は4つある。(1) パーサコンビネータに対する伝搬制御機構（propagation control mechanism）であり、トークン消費モード（token consumption mode）と一致反転（match inversion）という2つの直交する構文解析次元に対するきめ細かい制御を、形式的に定義された操作的意味論（operational semantics）と代数的性質を備えた伝搬ストッパ（propagation stopper）の階層を通じて提供するものである。(2) `ContainerParser<T>`によるメタデータ搬送構文解析木（metadata-carrying parse tree）であり、入力を消費することなく、エラーメッセージや補完候補を構文解析木に直接埋め込むものである。(3) エバリュエータのためのGeneration Gap Pattern（GGP）であり、Java 21のsealed interfaceと網羅的switch式（exhaustive switch expression）を用いてコンパイラによる網羅性保証を提供するものである。(4) `MatchedTokenParser`であり、PEGおよび文脈自由文法の能力を超える文脈依存パターンを認識するためのコンビネータレベルの機構である。本フレームワークの評価には、月間10^9（10億）件のトランザクションを処理する金融計算向け本番式評価器であるtinyexpressionを使用し、スクラッチ実装と比較してコード行数の14倍の削減、およびリフレクションベースからsealed-switchベースのAST評価への移行時の1400倍の性能改善を実証した。第2のケーススタディでは、`MatchedTokenParser`上に構築された5つの異なる実装を用いた回文（palindrome）認識――文脈依存パターンの典型例――を実証する。

---

## 1. はじめに

ドメイン固有言語の構築には、文法とパーサを書くこと以上のものが求められる。完全な本番品質のDSL実装には、少なくとも以下の6つの緊密に結合したサブシステムが必要である。

1. **パーサ（Parser）**: 言語の具象構文を認識し、構文解析木を生成する。
2. **AST型定義**: 抽象構文を表現する型付きデータ構造の集合である。
3. **構文解析木からASTへのマッパ**: 平坦な具象構文解析木を、構造化された型付きASTに変換する。
4. **エバリュエータまたはインタプリタ**: ASTを走査し、言語の意味論に従って値を計算する。
5. **Language Server Protocol（LSP）サーバ**: シンタックスハイライト、コード補完、ホバードキュメント、診断エラー報告などのエディタ非依存のIDE機能を提供する。
6. **Debug Adapter Protocol（DAP）サーバ**: ステップ実行、ブレークポイント管理、変数検査、スタックトレース表示を、DAP対応のあらゆるエディタで可能にする。

従来の実践では、これらのサブシステムはそれぞれ独立に開発される。文法の変更――新しい演算子の追加、新しい式型の導入、優先順位規則の変更――には、6つすべてのコンポーネントにわたる協調的な更新が必要となる。この結合は欠陥の原因としてよく知られている。パーサがエバリュエータで処理できない構文を受理する場合、LSPサーバがパーサが拒否する補完候補を提示する場合、あるいはリファクタリング後にAST型が文法から乖離する場合がありうる。

既存のツールはこの問題の一部に対処している。ANTLR [Parr and Fisher 2011]はアノテーション付き文法からパーサとオプションでASTノード型を生成するが、エバリュエータ、LSPサーバ、DAPサーバは生成しない。Tree-sitter [Brunel et al. 2023]はエディタ向けにインクリメンタル構文解析を提供するが、意味層は生成しない。PEGベースのパーサジェネレータ [Ford 2004]は通常、認識器（recognizer）のみを生成し、後続のすべてのアーティファクトは開発者に委ねられる。Parsec [Leijen and Meijer 2001]のようなパーサコンビネータライブラリは、ホスト言語における合成的なパーサ構築を提供するが、やはり構文解析までで止まる。Spoofax [Kats and Visser 2010]、JetBrains MPS [Volter et al. 2006]、Xtext [Bettini 2016]などの言語ワークベンチ（language workbench）はより広範なツールチェーンを提供するが、スコープ、アーキテクチャ、パラダイムが大きく異なる――これらのシステムとの詳細な比較は第2節で行う。

これらのツールのいずれも、単一の仕様から文法からIDEまでのフルスタックを生成するものではない。

本論文では、`unlaxer-common`（パーサコンビネータランタイム、約436のJavaソースファイル）と`unlaxer-dsl`（コード生成パイプライン）の2つのモジュールから構成されるJava 21フレームワークであるunlaxer-parserを提示する。本フレームワークは、単一の`.ubnf`文法ファイルを入力として受け取り、`Parsers.java`、`AST.java`、`Mapper.java`、`Evaluator.java`、言語サーバ、およびデバッグアダプタの6つのJavaソースファイルを生成する。開発者が書く必要があるのは文法と評価ロジック（通常50〜200行の`evalXxx`メソッド）のみであり、それ以外はすべてフレームワークが生成・保守する。

本論文の貢献は以下の4つである。

1. **パーサコンビネータの伝搬制御**（第3.3節）: パースモード（`TokenKind`と`invertMatch`）がコンビネータツリーをどのように伝搬するかを制御する機構であり、形式的に定義された操作的意味論（第3.6節）と代数的性質を備える。我々が調査したパーサコンビネータフレームワークの中で、この特定の制御の組み合わせがファーストクラスAPIとして提供されているものは見当たらなかった。
2. **メタデータ搬送構文解析木**（第3.4節）: `ContainerParser<T>`であり、入力を消費することなく型付きメタデータ（エラーメッセージ、補完候補）を構文解析木に挿入するパーサである。これにより、LSP機能を単一の構文解析パスから導出できるようになる。
3. **エバリュエータのためのGeneration Gap Pattern**（第3.5節）: sealed-switchディスパッチを備えた網羅的な生成済み抽象エバリュエータクラスと、再生成後も保持される手書きの具象実装を組み合わせたものである。
4. **文脈自由を超える構文解析**（第3.8節）: `MatchedTokenParser`であり、一致した内容をキャプチャし再生する（replay）コンビネータレベルの機構であり、回文やXMLタグ対応のような文脈依存パターンの認識を可能にする。

本論文の構成は以下の通りである。第2節では、パーサ生成、言語ワークベンチ、およびIDEプロトコル支援に関する関連研究を概説する。第3節では、UBNF文法記法、生成パイプライン、4つの貢献、PropagationStopperの操作的意味論、モナディック解釈、およびMatchedTokenParserを含むシステム設計を提示する。第4節では実装について述べる。第5節では、tinyexpressionおよび回文認識のケーススタディを用いてフレームワークを評価し、性能ベンチマークと開発工数の比較を提示する。第6節では、制限事項、妥当性への脅威、および今後の課題を議論する。第7節で結論を述べる。

---

## 2. 背景と関連研究

### 2.1 パーサジェネレータ

パーサジェネレータの歴史は半世紀に及ぶ。Yacc [Johnson 1975]とその後継であるBisonは、BNFで記述された文脈自由文法からLALR(1)パーサを生成する。これらのツールは効率的な表駆動パーサを生成するが、文法が曖昧でなく左ファクタリング済みであることを要求するため、言語設計者にとって負担が大きい場合がある。LALRパーサのエラーメッセージは不親切なことで知られており、生成されるパーサは構文解析木を生成するが型付きASTは生成しない。

ANTLR [Parr and Fisher 2011]はALL(*)を導入した。これはLALR(1)よりも広いクラスの文法を扱える適応型LLベースの構文解析戦略である。ANTLRはレキサとパーサの両方を生成し、オプションでツリー走査用のビジタまたはリスナのベースクラスを生成する。しかし、ANTLRのビジタパターンでは開発者が各`visitXxx`メソッドを手動で実装する必要があり、ANTLRはエバリュエータ、LSPサーバ、DAPサーバを生成しない。後続のすべてのアーティファクトは開発者の責任となる。

Parsing Expression Grammar（PEG）[Ford 2004]は、文脈自由文法に対する認識ベースの代替手段を提供する。PEGは順序なし選択（`|`）の代わりに順序付き選択（`/`）を使用し、構成的に曖昧性を排除する。メモ化を伴うPackratパーサを含むPEGベースのパーサは、その予測可能性と実装の容易さから人気を博している。しかし、PEGパーサは通常、認識器にすぎない――入力が文法に一致するかどうかを判定するが、本質的に構造化された構文解析木を生成しない。IerusalimschyのLPEG [Ierusalimschy 2009]やRedziejowskiのPEG基盤に関する研究 [Redziejowski 2007]を含むいくつかのPEGベースのツールは、認識問題に焦点を当てており、AST構築やIDE統合には対処していない。

パーサコンビネータライブラリは異なるアプローチをとる。パーサはホスト言語におけるファーストクラスの値であり、高階関数を用いて合成される。Haskellで記述されたParsec [Leijen and Meijer 2001]は、コミット選択意味論（committed-choice semantics）を通じて明確なエラーメッセージを提供するモナディックパーサコンビネータのパラダイムを確立した。ScalaパーサコンビネータはこのアプローチをJVMにもたらした。パーサコンビネータは優れた合成性とホスト言語の型システムとの統合を提供するが、パーサ自体以外のものは生成しない。

### 2.2 言語ワークベンチ

言語ワークベンチ [Erdweg et al. 2013]は、言語とそのツールを定義するための統合環境を提供する。3つのシステムが特に関連性が高い。

**Spoofax** [Kats and Visser 2010]は、文法定義にSDF3、AST変換にStratego、エディタサービス定義にESVを使用する。SpoofaxのパーサはSGLR（Scannerless GLR）であり、曖昧な文法を扱うことができる――これは我々のPEGベースのアプローチとの根本的な相違である。Spoofaxは、パーサ、AST型、およびエディタ支援（シンタックスハイライト、構造編集）を生成する。Spoofax 3の時点でLSP支援が開発中であるが、まだ完全ではなく、DAP支援は提供されていない。Spoofaxは3つの異なるDSL（SDF3、Stratego、ESV）の学習を必要とするのに対し、unlaxerは単一のUBNF仕様を使用する。

**JetBrains MPS** [Volter et al. 2006]は、テキストベースの構文解析を完全にバイパスし、ASTを直接操作するプロジェクショナルエディタ（projectional editor）である。MPSは、そのプロジェクショナルパラダイム内でネイティブにリッチなIDE機能（補完、エラーチェック、リファクタリング）を提供する。しかし、MPSはテキストベースのエディタとは根本的に異なるパラダイムを使用しており、VS CodeやEmacsのような従来のテキストエディタ向けのLSPサーバやDAPサーバを生成しない。

**Xtext** [Bettini 2016]は、Eclipseベースの言語ワークベンチであり、パーサ（ANTLRを介して）、AST型（EMFを介して）、エディタ（Eclipseベースおよび LSP）、およびオプションでインタプリタを生成する。Xtextは、既存のワークベンチの中でunlaxerに最も近い機能カバレッジを提供する。しかし、XtextのLSP支援にはXtextランタイムが必要であり、DAP支援は生成されず手動で実装する必要があり、エバリュエータはコンパイラによる網羅性保証なしに手書きで作成しなければならない。

### 2.3 Language Server ProtocolとDebug Adapter Protocol

Language Server Protocol（LSP）[Microsoft 2016a]は、エディタと言語固有のインテリジェンスプロバイダ間の通信を標準化したものである。LSPサーバは、コード補完、ホバー情報、定義への移動、参照の検索、診断（エラー・警告の報告）、およびコードアクションなどの機能を実装する。LSP以前は、各エディタに言語固有のプラグインが必要であった。LSPはエディタ支援を言語実装から切り離し、単一のサーバをVS Code、Emacs、Vim、およびその他のLSP対応エディタで利用可能にした。

Debug Adapter Protocol（DAP）[Microsoft 2016b]は、同じ分離パターンをデバッグに適用するものである。DAPサーバは、起動／アタッチ、ブレークポイント、ステップオーバー／ステップイン／ステップアウト、スタックトレース、変数検査、および式評価を実装する。LSPと同様に、DAPは単一のデバッグアダプタをエディタ横断的に利用可能にする。

標準化にもかかわらず、LSPサーバやDAPサーバの実装は依然として労力を要する。中程度に複雑な言語の典型的なLSPサーバには2,000〜5,000行のコードが必要であり、DAPサーバには1,000〜2,000行が必要である。これらのサーバは文法、AST、およびエバリュエータと同期を保つ必要がある。Tree-sitter [Brunel et al. 2023]は、インクリメンタル構文解析、シンタックスハイライト、および基本的な構造クエリを提供することでLSP統合に部分的に対処しているが、型を考慮した補完や診断報告などの意味機能は提供しない。

unlaxer-parserは、文法からLSPサーバとDAPサーバの両方を生成する。生成されたLSPサーバは、補完（文法キーワードおよび`@catalog`/`@declares`アノテーションに基づく）、診断（解析エラーおよび`ErrorMessageParser`メタデータに基づく）、およびホバー（`@doc`アノテーションに基づく）を提供する。生成されたDAPサーバは、構文解析木を通じたステップ実行、ブレークポイント支援、および現在のトークンのテキストとパーサ名を表示する変数表示を提供する。

### 2.4 ツール機能の比較

表1は、ツール間で生成されるアーティファクトを比較したものである。「部分的」はツールが基盤を提供するが相当量の手書きコードを必要とすることを示す。「N/A」はツールが根本的に異なるパラダイムを使用していることを示す。

| ツール | パーサ | AST型 | マッパ | エバリュエータ | LSP | DAP |
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

*表1: ツール別の生成アーティファクト。SpoofaxのマッパはStratego（別個のDSL）で記述される。XtextのLSP支援にはXtextランタイムが必要である。MPSはテキストベースのLSPとは異なるプロジェクショナル編集パラダイムで動作する。*

### 2.5 コード生成パターン

2つのコード生成パターンが本研究に特に関連する。

**Visitorパターン** [Gamma et al. 1994]は、生成済みパーサにおけるASTノード走査の標準的なアプローチである。ANTLRは、文法規則ごとに1つの`visitXxx`メソッドを持つビジタインタフェースを生成する。このパターンはツリー構造とそれに対する操作の間の良好な分離を提供するが、網羅性を強制しない。開発者がvisitメソッドの実装を忘れることが容易であり、実行時エラーや暗黙の不正な動作を引き起こしうる。

**Generation Gap Pattern**（GGP）[Vlissides 1996]は、コード生成における根本的な緊張関係に対処する。すなわち、生成コードは入力（文法）が変更されるたびに再生成される必要があるが、手書きのカスタマイズは再生成後も保持されなければならない。GGPは各クラスを2つに分割することでこれを解決する。生成された抽象基底クラスと手書きの具象サブクラスである。生成された基底クラスは構造的なボイラープレートを含み、具象サブクラスは手書きのロジックを含む。ジェネレータが再実行されると、基底クラスのみが上書きされる。

unlaxer-parserは、GGPとJava 21のsealed interfaceおよび網羅的switch式を組み合わせる。生成されたエバリュエータ基底クラスは、sealed ASTインタフェースに対するswitch式を持つプライベートな`evalInternal`メソッドを含む。

```java
private T evalInternal(TinyExpressionP4AST node) {
    return switch (node) {
        case TinyExpressionP4AST.BinaryExpr n -> evalBinaryExpr(n);
        case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
        case TinyExpressionP4AST.IfExpr n -> evalIfExpr(n);
        // ... one case per @mapping class
    };
}
```

`TinyExpressionP4AST`はsealed interfaceであるため、Javaコンパイラはすべての許可されたサブタイプがswitchでカバーされていることを検証する。文法に新しいASTノード型が追加されると、開発者が対応する`evalXxx`メソッドを実装するまで、コンパイラは手書きの具象クラスを拒否する。これは実行時エラー（不足するvisitorメソッド）をコンパイル時エラーに変換するものであり、安全性の大幅な向上である。

---

## 3. システム設計

### 3.1 UBNF文法記法

UBNF（Unlaxer Backus-Naur Form）は、コード生成を制御するアノテーションを用いて標準EBNFを拡張したものである。UBNFの文法ファイルは、グローバル設定、トークン宣言、および規則宣言を含む名前付き文法を定義する。

**グローバル設定**は生成パイプラインを構成する。

```ubnf
grammar TinyExpressionP4 {
  @package: org.unlaxer.tinyexpression.generated.p4
  @whitespace: javaStyle
  @comment: { line: '//' }
```

`@package`設定は生成コードのJavaパッケージを指定する。`@whitespace`設定は空白文字処理プロファイルを選択する（ここではJavaスタイルの空白文字で、`//`と`/* */`のコメントをインターリーブトークンとして含む）。`@comment`設定はコメント構文を宣言する。

**トークン宣言**は終端記号をパーサクラスにバインドする。

```ubnf
  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token STRING     = SingleQuotedParser
  token CODE_BLOCK = org.unlaxer.tinyexpression.parser.javalang.CodeParser
```

各トークンはシンボリック名を`unlaxer-common`ライブラリの具象パーサクラス（またはユーザ定義パーサ）にマッピングする。これにより、文法は複雑な字句パターン（Javaスタイルのコードブロックなど）を文法記法内にエンコードすることなく参照できる。

**規則宣言**はアノテーション付きのEBNFライクな構文を使用する。

```ubnf
  @root
  Formula ::= { VariableDeclaration } { Annotation } Expression { MethodDeclaration } ;
```

`@root`アノテーションは文法のエントリポイントを示す。波括弧`{ ... }`はゼロ回以上の繰り返し、角括弧`[ ... ]`はオプション要素、丸括弧`( ... )`はグルーピング、`|`は順序付き選択（PEG意味論）を示す。

**`@mapping`アノテーション**はAST生成の中核的機構である。

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=10)
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

このアノテーションはコードジェネレータに以下を指示する。
1. sealed ASTインタフェースにフィールド`left`、`op`、`right`を持つレコード型`BinaryExpr`を作成する。
2. 構文解析木から`@left`、`@op`、`@right`アノテーション付き要素を抽出し、`BinaryExpr`インスタンスを構築するマッパ規則を生成する。
3. エバリュエータスケルトンに`evalBinaryExpr`抽象メソッドを生成する。

`@leftAssoc`アノテーションはマッパで左結合のグルーピングを生成し、`@precedence(level=N)`は曖昧性解消のための優先順位レベルを確立する。

追加のアノテーションには、空白文字挿入の制御のための`@interleave(profile=javaStyle)`、メソッド宣言におけるスコープ意味論のための`@scopeTree(mode=lexical)`、参照解決のための`@backref(name=X)`、シンボル宣言のための`@declares`、補完カタログのための`@catalog`、ホバードキュメントのための`@doc`がある。完全なtinyexpression文法（`tinyexpression-p4-complete.ubnf`）は520行にわたり、数値式、文字列式、ブール式、オブジェクト式、変数宣言、メソッド宣言、if/else式とmatch式、インポート宣言、および埋め込みJavaコードブロックをカバーする。

### 3.2 生成パイプライン

生成パイプラインは`.ubnf`文法ファイルを6つのJavaソースファイルに変換する。パイプラインは3つのフェーズから構成される。

**フェーズ1: 構文解析。** UBNF文法ファイルは、自己ホスト型パーサ（`UBNFParsers`）によって構文解析される――UBNFパーサ自体がunlaxer-parserのコンビネータライブラリを使用して構築されている。構文解析木は、ジェネレータがユーザ文法に対して生成するのと同じ`sealed interface + record`パターンを用いて型付きAST（`UBNFAST`）にマッピングされる。`UBNFAST`自体がsealed interfaceである。

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

**フェーズ2: 検証。** `GrammarValidator`は文法の整形性を検査する。すなわち、未定義の規則参照、重複する規則名、欠落した`@root`アノテーション、および`@mapping`パラメータと規則構造の整合性である。

**フェーズ3: コード生成。** いずれも`CodeGenerator`インタフェースを実装する6つのコードジェネレータが出力を生成する。

| ジェネレータ | 出力 | 説明 |
|-----------|--------|-------------|
| `ParserGenerator` | `XxxParsers.java` | `LazyChain`、`LazyChoice`、`LazyZeroOrMore`等を使用するPEGベースのパーサコンビネータ。空白文字処理は`@interleave`プロファイルに基づいて自動挿入される。 |
| `ASTGenerator` | `XxxAST.java` | `@mapping`クラスごとに1つの`record`を持つJava 21 sealed interface。フィールドは`params`リストに従って型付けされる。 |
| `MapperGenerator` | `XxxMapper.java` | トークンツリーからASTへのマッピングロジック。複数規則マッピング（複数の文法規則が同じASTクラスにマッピングされる場合）、`@leftAssoc`/`@rightAssoc`グルーピング、およびネストされた部分式への深い検索を防止する`findDirectDescendants`を処理する。 |
| `EvaluatorGenerator` | `XxxEvaluator.java` | 網羅的sealed-switchディスパッチとステップデバッグフック用の`DebugStrategy`インタフェースを備えた抽象クラス。 |
| `LSPGenerator` | `XxxLanguageServer.java` | 補完（キーワード、`@catalog`エントリ、`@declares`シンボル）、診断（解析エラーおよび`ErrorMessageParser`メタデータ）、ホバー（`@doc`アノテーション）を備えたLSPサーバ。`@declares`/`@backref`/`@scopeTree`アノテーションが存在する場合、スコープストア登録を含む。 |
| `DAPGenerator` | `XxxDebugAdapter.java` | `stopOnEntry`支援、構文解析木を通じたステップオーバー実行、ブレークポイント管理、スタックトレース表示、および変数検査を備えたDAPサーバ。 |

### 3.3 貢献: 伝搬制御

unlaxer-parserのコンビネータアーキテクチャにおいて、すべてのパーサの`parse`メソッドは3つのパラメータを受け取る。

```java
public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch)
```

- `ParseContext`は入力カーソル、トランザクションスタック、およびトークンツリーを管理する。
- `TokenKind`は、パーサが入力を*消費する*（`consumed`）か、カーソルを進めずに*一致のみ行う*（`matchOnly`）かを制御する。これはPEGの先読み述語（lookahead predicate）に相当する。
- `invertMatch`はパーサの成功/失敗意味論を反転させる――`true`のとき、成功した一致が失敗として扱われ、その逆も同様である。これはPEGの「not」述語に相当する。

素朴な実装では、`tokenKind`と`invertMatch`の両方が親から子へ無条件に伝搬する。これは問題を引き起こす。`Not`コンビネータを考えてみよう。

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

`Not`はその子を`matchOnly`モードに強制する（先読み中に入力を消費してはならない）。しかし、子が`Not`コンビネータを内部に含む複雑な部分式である場合はどうなるか。外側の`Not`からの`matchOnly`は下方に伝搬するが、内側の`DoConsumePropagationStopper`がそれを選択的にオーバーライドできるべきである。

我々は**PropagationStopper階層**を導入する。これは、この2次元の伝搬に対するきめ細かい制御を提供する4つのクラスの集合である。

```java
public interface PropagationStopper { }
```

**1. AllPropagationStopper**: `TokenKind`と`invertMatch`の両方の伝搬を停止する。親が何を渡すかに関わらず、子は常に`TokenKind.consumed`と`invertMatch=false`を受け取る。

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

**2. DoConsumePropagationStopper**: `TokenKind`の伝搬のみを停止し、子を`consumed`モードに強制しつつ`invertMatch`は透過させる。

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

**3. InvertMatchPropagationStopper**: `invertMatch`の伝搬のみを停止し、子を`invertMatch=false`に強制しつつ`TokenKind`は透過させる。

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

**4. NotPropagatableSource**: `invertMatch`フラグを反転させる（伝搬された値に対する論理NOT）。親が`invertMatch=true`を渡すと子は`invertMatch=false`を受け取り、その逆も同様である。

```java
public class NotPropagatableSource extends AbstractPropagatableSource {
    @Override
    public boolean computeInvertMatch(boolean fromParentValue, boolean thisSourceValue) {
        return false == fromParentValue;
    }
}
```

この階層は、状態空間`S = {consumed, matchOnly} x {true, false}`上の自己写像の集合として形式的に特徴づけることができる。各伝搬ストッパは、どの次元をインターセプトし、どの値を代入するかを選択する。設計は合成的である。伝搬ストッパはネストでき、それぞれがその担当する次元に独立して作用する。形式的な操作的意味論は第3.6節で、モナディック解釈は第3.7節で与える。

Parsecは`try`と`lookAhead`のコンビネータを通じて先読みを処理する。これらはコミット済み/未コミットの選択とゼロ幅アサーションをそれぞれ制御する。これらのコンビネータはモナドの結合律を通じて合成可能である。しかし、構文解析状態を2つの直交する次元（`TokenKind`と`invertMatch`）に分解し、独立した伝搬制御を提供するという特定の設計は、Parsec、megaparsec、attoparsec、あるいは我々が調査した他のパーサコンビネータフレームワークにおいてファーストクラスAPIとして提供されていない。特に、`invertMatch`の伝搬制御――コンビネータツリーの任意の地点で否定フラグを選択的に停止または反転させる能力――には、既存フレームワークの標準コンビネータセットに直接的な対応物が存在しない。

### 3.4 貢献: メタデータ搬送構文解析木

unlaxer-parserにおける基本的な洞察は、構文解析木がパース段階とIDE統合段階の間の**通信チャネル**として機能しうるということである。これは`ContainerParser<T>`によって実現される。これは入力を消費せずに型付きメタデータを構文解析木に挿入する抽象パーサクラスである。

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

重要な性質は、`ContainerParser`が現在のカーソル位置に**空トークン**を作成すること――入力を消費せずに成功することである。パーサインスタンス自体が`get()`および`get(CursorRange)`メソッドを通じてアクセス可能なメタデータを保持する。構文解析後、メタデータはトークンツリーから、パーサが特定の`ContainerParser`サブクラスのインスタンスであるトークンをフィルタリングすることで抽出できる。

2つの具象サブクラスがこのパターンを実証する。

**ErrorMessageParser**は診断報告のためにエラーメッセージを構文解析木に埋め込む。

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

`expectedHintOnly=true`で使用する場合、パーサは意図的に失敗するが、自身を「期待される」トークンとして登録し、その位置で何が期待されていたかについての人間が読める説明をエラー報告システムに提供する。この情報はLSPサーバの診断ハンドラに直接流れる。

**SuggestsCollectorParser**は構文解析中に兄弟パーサから補完候補を収集する。

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

このパーサはコンビネータツリーの同じレベルにある兄弟パーサに対して、残りの入力に基づく補完候補を問い合わせる。候補はトークンツリーに格納され、後にLSPサーバの補完ハンドラによって抽出される。

メタデータ搬送構文解析木パターンにより、単一の構文解析パスで評価とIDE機能の両方に必要なすべての情報を生成できる。この機構がなければ、LSPおよびDAP統合には別個のパスまたは並行データ構造が必要となり、複雑性と不整合のリスクが増大する。

### 3.5 貢献: エバリュエータのためのGeneration Gap Pattern

Generation Gap Pattern（GGP）[Vlissides 1996]は、生成コードと手書きコードを継承関係で結ばれた異なるクラスに配置することで分離する。unlaxer-parserは、GGPをエバリュエータ構築に適用し、決定的な拡張を加える。すなわち、Java 21のsealed interfaceが**コンパイラによる網羅性検査**を提供するのである。

ジェネレータは抽象エバリュエータクラスを生成する。

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
            // ... one case per @mapping type
        };
    }

    protected abstract T evalBinaryExpr(TinyExpressionP4AST.BinaryExpr node);
    protected abstract T evalComparisonExpr(TinyExpressionP4AST.ComparisonExpr node);
    protected abstract T evalIfExpr(TinyExpressionP4AST.IfExpr node);
    // ... one abstract method per @mapping class

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

開発者は次に**具象**サブクラスを記述する。

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

    // ... implementations for all other evalXxx methods
}
```

この設計は3つの保証を提供する。

1. **網羅性**: 文法に新しい`@mapping`規則が追加されると、sealed interfaceに新しい許可サブタイプが加わり、生成されたswitchが非網羅的となり、新しい`evalXxx`メソッドが追加されるまでコンパイラが具象クラスを拒否する。
2. **再生成安全性**: 再生成されるのは抽象基底クラスのみである。すべてのドメイン固有の評価ロジックを含む具象サブクラスは上書きされない。
3. **デバッグ統合**: 生成された基底クラスの`DebugStrategy`フックにより、手書きクラスにコードを追加することなくDAPサーバを通じたステップデバッグが可能になる。

GGPアプローチは、同一の文法から複数の評価戦略もサポートする。tinyexpressionプロジェクトは、同一の生成基底を拡張する3つの具象エバリュエータを実装している。
- `P4TypedAstEvaluator`: ASTを直接解釈し、`Object`値を返す。
- `P4TypedJavaCodeEmitter`: ASTを走査し、ランタイムコンパイル用のJavaソースコードを出力する。
- `P4DefaultJavaCodeEmitter`: デフォルトの評価パターンのためのテンプレートベースのエミッタである。

### 3.6 PropagationStopperの操作的意味論

伝搬ストッパ階層を小ステップ操作的意味論（small-step operational semantics）を用いて形式化する。パーサ状態を`s = (tk, inv)`とする。ここで`tk in {consumed, matchOnly}`かつ`inv in {true, false}`である。各伝搬ストッパは`S = {consumed, matchOnly} x {true, false}`上の自己写像である。

**推論規則。** パーサ`p`がコンテキスト`ctx`と状態`s`を与えられたとき結果`r`を生成することを`p.parse(ctx, s) => r`と記す。

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

**代数的性質。** 4つのストッパと恒等写像は、4元集合`S`上の自己写像の有限集合を形成する。主要な性質は以下の通りである。

*べき等性:*

- `AllStop . AllStop = AllStop`（定数写像はべき等）
- `DoConsume . DoConsume = DoConsume`
- `StopInvert . StopInvert = StopInvert`
- `NotProp . NotProp = Id`（対合であり、べき等ではない）

*吸収:*

- `AllStop . X = AllStop`（任意のストッパ`X`に対して）（AllStopは右零元）

*合成の例:*

- `DoConsume . StopInvert = AllStop`
- `StopInvert . DoConsume = AllStop`
- `AllStop . DoConsume = AllStop`
- `NotProp . NotProp = Id`

*非可換性:* ストッパ代数は一般に非可換である。`DoConsume . StopInvert = StopInvert . DoConsume = AllStop`であるが、`StopInvert . NotProp != NotProp . StopInvert`である。具体的には:

- `StopInvert . NotProp`: `(tk, inv) -> (tk, not(inv)) -> (tk, false) = StopInvert`
- `NotProp . StopInvert`: `(tk, inv) -> (tk, false) -> (tk, true)` = `ForceInvert`（第2成分上の新しい定数写像）

このことは、4つのストッパが合成に関して可換モノイドを形成しないが、`Id`を単位元、`AllStop`を右吸収元とする有限非可換モノイドを形成することを示している。

### 3.7 モナディック解釈

PropagationStopper階層とContainerParserは、よく知られたモナディック抽象に対する正確な対応関係を持つ。我々はこれらの対応関係を明示的に認め、JavaベースのクラスHierarchyによる実現が見落としではなく意図的な設計選択である理由を説明する。

| unlaxerの概念 | モナディック対応 | 説明 |
|-----------------|----------------------|-------------|
| PropagationStopper | Readerモナドの`local` | 環境パラメータの局所的変更 |
| AllPropagationStopper | `local (const (C,F))` | 定数環境で置換 |
| DoConsumePropagationStopper | `local (\(_,i)->(C,i))` | 第1成分のみ固定 |
| InvertMatchPropagationStopper | `local (\(t,_)->(t,F))` | 第2成分のみ固定 |
| NotPropagatableSource | `local (\(t,i)->(t,not i))` | 第2成分を反転 |
| ContainerParser\<T\> | Writerモナドの`tell` | 副作用なくメタデータを蓄積 |
| ErrorMessageParser | `tell [ErrorMsg msg]` | エラーメッセージを蓄積 |
| SuggestsCollectorParser | `tell [Suggestions xs]` | 補完候補を蓄積 |
| ParseContext.begin/commit/rollback | バックトラッキング付きStateモナドのget/put | パーサ状態の保存/復元 |
| Parsed.FAILED | ExceptTの`throwError` | 構文解析の失敗を伝搬 |

Haskellでは、フレームワーク全体をモナドトランスフォーマスタックとして表現できる。

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

**モナドトランスフォーマではなくJavaクラス階層を選択した理由。** 我々がモナディック定式化ではなくJavaのクラス階層を選択したのは、以下の3つの理由による。

1. **デバッグ容易性。** JavaのクラスベースのPropagationStopper階層は、IDEデバッガで直接的に可視化できる。開発者は`AllPropagationStopper.parse()`にブレークポイントを設定し、変数ペインで`tokenKind`と`invertMatch`を検査し、伝搬ロジックをステップ実行できる。モナドトランスフォーマスタックでは、等価な状態はネストされた`runReaderT`/`runWriterT`/`runStateT`クロージャに埋もれており、標準的なデバッガからは不透明である。

2. **IDE支援。** Javaの型階層は標準的なIDE機能を可能にする。「AllPropagationStopperへのすべての参照を検索」、「PropagationStopperの実装に移動」、「型階層を表示」。これらの操作はすべてのJava IDEで直接サポートされている。Haskellでは、等価な操作には専門的なツール（HLS）が必要であり、ユーザ定義のDSLには拡張されない。

3. **LSP/DAP生成。** 我々のフレームワークは文法からLSPサーバとDAPサーバを生成する。生成されたDAPサーバは、PropagationStopperの遷移をデバッグイベントとして表示しながら、パーサコンビネータツリーのステップスルーデバッグを提供する。この生成パイプラインはJavaのクラス構造に対して動作するものであり、モナディック定式化に対しては根本的な再設計が必要となる。既存のHaskellパーサコンビネータフレームワークで、文法仕様からLSPサーバやDAPサーバを生成するものは存在しない。

モナディック解釈は我々の抽象の形式的特徴づけとして価値があり、我々はそれをそのように提示する。しかし、unlaxer-parserの実用的価値は、個々の抽象にあるのではなく――対応表が示す通り、それらにはよく知られたモナディック対応物がある――単一の文法仕様から6つの整合的なアーティファクトを生成する統一コード生成パイプラインへの統合にある。モナディック構造は「どう構文解析するか」を説明するが、「単一の文法から6つすべてのアーティファクトをどう生成するか」は説明しない。

### 3.8 文脈自由を超えて: MatchedTokenParser

標準的なPEGおよび文脈自由文法では、特定の重要なパターンを認識できない。典型的な例は回文言語`L = { w w^R | w in Sigma* }`であり、これは文脈依存言語（context-sensitive language）である。XMLスタイルのタグ対応（開始タグ`<foo>`に対応する終了タグ`</foo>`の一致）も、もう1つの実用的な例である。従来のパーサジェネレータでは、これらのパターンは文法形式論の外でアドホックなコードによって処理される必要がある。

unlaxer-parserの`MatchedTokenParser`は、一致した内容をキャプチャし、オプションで逆順にして再生する（replay）コンビネータレベルの機構を提供し、パーサコンビネータフレームワーク内で文脈依存パターンを認識可能にする。

**設計。** `MatchedTokenParser`は、先行する`MatchOnly`（先読み）パーサと連携して動作する。`MatchOnly`パーサは入力を消費せずに一致し、どの内容が認識されたかを確立する。その後、`MatchedTokenParser`がこのキャプチャされた内容にアクセスし、いくつかの操作を提供する。

- **直接再生（Direct replay）**: 現在の位置で同じ内容を一致させる（XMLタグ対応に有用）。
- **`slice`**: 設定可能なstart、end、stepパラメータを用いて、キャプチャされた内容の部分範囲を抽出する。
- **`effect`**: キャプチャされた内容に任意の変換を適用する（例: 逆順にする）。
- **`pythonian`**: Pythonスタイルのスライス記法（例: 逆順のための`"::-1"`）を使用して簡潔な仕様を可能にする。

**理論的着想: Macro PEG。** MatchedTokenParserの設計は、Macro PEG [Mizushima 2016]に着想を得たものである。Macro PEGは、回文のような文脈依存パターンを処理するためにパラメータ付き規則でPEGを拡張する。Macro PEGが文法レベルの拡張（パラメータを受け取る規則）を通じてこれを実現するのに対し、unlaxerはオブジェクト指向的アプローチをとる。MatchedTokenParserはコンビネータレベルで一致した内容をキャプチャし、トークン操作のための合成可能な操作を提供する。両方のアプローチはPEGの認識能力を文脈自由言語を超えて拡張するが、unlaxerの設計はJavaの型システムとIDEツールに自然に統合される。

**回文認識: 5つの実装。** 以下の5つの実装は、回文認識に対するMatchedTokenParserの表現力を実証する。5つすべてが`Usage003_01_Palidrome.java`でテストされており、"a"、"abcba"、"abccba"、"aa"のような文字列を正しく認識し、"ab"のような非回文を正しく拒否する。

*実装1: sliceWithWord。* 入力を前半、ピボット（奇数長の場合）、および前半の逆順に分解する。

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

*実装2: sliceWithSlicer。* 範囲指定に`Slicer` APIを使用する。

```java
matchedTokenParser.slice(slicer -> {
    boolean hasPivot = slicer.length() % 2 == 1;
    int halfSize = (slicer.length() - (hasPivot ? 1 : 0)) / 2;
    slicer.end(new CodePointIndex(halfSize));
})
```

*実装3: effectReverse。* Javaの`StringBuilder.reverse()`を使用して完全な逆順変換を適用する。

```java
matchedTokenParser.effect(word ->
    StringSource.createDetachedSource(new StringBuilder(word).reverse().toString()))
```

*実装4: sliceReverse。* 逆順のためにstep=-1を使用する。

```java
matchedTokenParser.slice(slicer -> slicer.step(-1))
```

*実装5: pythonian。* Pythonスタイルのスライス記法を使用する。

```java
matchedTokenParser.slice(slicer -> slicer.pythonian("::-1"))
```

pythonian構文`"::-1"`はPythonの`[::-1]`文字列逆順イディオムを直接反映しており、開発者にとって簡潔で馴染みのある記法を提供する。

**XMLタグ対応。** 回文以外にも、MatchedTokenParserはXMLスタイルの開始タグと終了タグの一致をサポートする。文法は`<tagname>`からタグ名をキャプチャし、終了タグ`</tagname>`の位置でそれを再生することで、構文解析後の検証ではなくパーサレベルで構造的一貫性を保証できる。

**Macro PEGとの比較。** 表2は、PEGを文脈自由認識の先へ拡張するアプローチを比較したものである。

| システム | アプローチ | 文脈自由文法を超える | ホスト言語との統合 | IDE支援 |
|--------|----------|-----------|-------------------------------|-------------|
| PEG (Ford 2004) | 文法記法 | No | N/A | No |
| Macro PEG (Mizushima 2016) | パラメータ付き文法規則 | Yes（文法レベル） | 限定的（スタンドアロン） | No |
| unlaxer MatchedTokenParser | コンビネータレベルのキャプチャ+再生 | Yes（オブジェクトレベル） | 完全なJava統合 | Yes (LSP/DAP) |

*表2: PEGを文脈自由言語を超えて拡張するアプローチ。Macro PEGは文法レベルで動作し、MatchedTokenParserはホスト言語内のコンビネータレベルで動作する。*

Macro PEGのパラメータ付き規則は、文脈依存パターンに対する簡潔な文法レベルの形式論を提供するが、カスタムパーサジェネレータを必要とし、ホスト言語のツールとは統合されない。MatchedTokenParserは文法レベルの簡潔さと引き換えに、Javaの型システム、IDEデバッガ、およびunlaxerのコード生成パイプラインとの完全な統合を得る。キャプチャされた内容はファーストクラスのJavaオブジェクトであり、標準的なJavaツールを使用して検査、変換、デバッグできる。

---

## 4. 実装

### 4.1 パーサコンビネータライブラリ（unlaxer-common）

`unlaxer-common`モジュールは、パーサコンビネータライブラリを実装する436のJavaソースファイルを含む。パーサはいくつかのカテゴリに分類される。

**コンビネータパーサ**は他のパーサを合成する。
- `Chain` / `LazyChain`: 逐次合成（PEGの列`e1 e2 ... en`）。
- `Choice` / `LazyChoice`: 順序付き選択（PEGの`e1 / e2 / ... / en`）。
- `LazyZeroOrMore`、`LazyOneOrMore`、`LazyOptional`: 繰り返しとオプション。
- `LazyRepeat`、`ConstructedOccurs`: 明示的なカウント制御による有界繰り返し。
- `Not`: PEGのnot述語（ゼロ幅否定先読み）。
- `MatchOnly`: PEGのand述語（ゼロ幅肯定先読み）。
- `NonOrdered`: 順不同集合一致（すべての選択肢が任意の順序で一致する必要がある）。

**LazyバリアントとConstructedバリアント**は循環的文法のサポートに対処する。再帰的文法（例: `Expression ::= ... '(' Expression ')' ...`）では、`Expression`のパーサは自身を参照する。即時構築（eager construction）は無限再帰を引き起こす。`Lazy`バリアントは子パーサの解決を最初の構文解析まで遅延させ、循環を断ち切る。`Constructed`バリアントは、構築時に子が既知である非再帰的規則に使用される。

**ASTフィルタリング**は構文解析木に現れるトークンを制御する。
- `ASTNode`: パーサのトークンがASTに含まれるべきであることを示すマーカインタフェース。
- `ASTNodeRecursive`: このパーサとその子孫からのトークンがASTに含まれる。
- `NotASTNode`および`NotASTNode`付き`TagWrapper`: ASTビューからトークンを除外する。
- `Token.filteredChildren`フィールドはトークンツリーのAST専用ビューを提供し、`Token.children`は完全な構文解析木を保持する。

**トランザクションベースのバックトラッキング**は`ParseContext`において順序付き選択意味論を可能にする。
- `begin(Parser)`: 現在のカーソル位置を保存する（セーブポイントの作成）。
- `commit(Parser, TokenKind)`: 解析済みトークンを受理し、カーソルを進める。
- `rollback(Parser)`: セーブポイントにカーソルを復元し、トークンを破棄する。

このトランザクションモデルはParsecのコミット選択意味論よりも一般的である。任意のパーサがトランザクションを開始でき、ネストされたトランザクションも完全にサポートされる。

### 4.2 コードジェネレータ（unlaxer-dsl）

`unlaxer-dsl`モジュールは6つのコードジェネレータと支援基盤を含む。

**MapperGenerator**は最も複雑な生成ロジックを処理する。主要な課題は**複数規則マッピング**（multi-rule mapping）である。複数の文法規則が同じASTクラスにマッピングされる場合がある。例えば、`NumberExpression`と`NumberTerm`の両方が`BinaryExpr`にマッピングされる。マッパは、特定の構文解析木ノードがどの規則から生成されたかを正しく識別し、適切なフィールドを抽出する必要がある。`allMappingRules`機構は`@mapping`クラス名を共有するすべての規則を収集し、各マッピング規則を順に試行するディスパッチャを生成する。

もう1つの課題は**findDirectDescendants**である。構文解析木から`@left`、`@op`、`@right`を抽出する際、マッパはアノテーション付き要素に一致する直接の子を、ネストされた部分式に降りることなく見つける必要がある。`NumberTerm`要素を含む`NumberExpression`では、トップレベルの項を抽出すべきであり、それらの内部の因子を抽出すべきではない。

**EvaluatorGenerator**はGGPスケルトンを生成する。網羅的switchディスパッチに加えて、各ノード評価の前後に呼び出される`DebugStrategy`フックを生成する。生成された`StepCounterStrategy`実装は評価ステップを計数し、特定のステップ数で一時停止するように構成でき、DAPサーバのステップオーバー動作を可能にする。

**ParserGenerator**は優先順位と結合性を処理する。`@leftAssoc`が存在する場合、ジェネレータは基底項の後に演算子-項ペアの`LazyZeroOrMore`が続く`LazyChain`を生成する。`@rightAssoc`の場合は再帰的なチェーンを生成する。`@precedence(level=N)`アノテーションは、複数の式型が競合する際の選択肢の順序付けに使用される。

### 4.3 5つの実行バックエンド

tinyexpressionプロジェクトは、同一の文法から派生した5つの異なる実行バックエンドを実装することで、フレームワークの柔軟性を実証する。

| バックエンド | キークラス | 戦略 | ステップデバッグ | コード生成 |
|---------|-----------|----------|------------|----------|
| `JAVA_CODE` | `JavaCodeCalculatorV3` | レガシーパーサからJavaソースへ、JITコンパイル | No | Yes |
| `AST_EVALUATOR` | `AstEvaluatorCalculator` | レガシーパーサから手書きASTへ、ツリーウォーキングエバリュエータ | No | No |
| `DSL_JAVA_CODE` | `DslJavaCodeCalculator` | レガシーパーサからDSL生成Javaソースへ | No | Yes |
| `P4_AST_EVALUATOR` | `P4AstEvaluatorCalculator` | UBNF生成パーサからsealed ASTへ、`P4TypedAstEvaluator` | Yes | No |
| `P4_DSL_JAVA_CODE` | `P4DslJavaCodeCalculator` | UBNF生成パーサからsealed ASTへ、Javaコードエミッタ | Yes | Yes |

P4バックエンド（第4行、第5行）はUBNF生成パーサとASTを使用するのに対し、以前のバックエンドはレガシーの手書きパーサを使用する。パリティ契約（parity contract）により、すべてのバックエンドがサポートされる式に対して等価な結果を生成することが保証され、`BackendSpeedComparisonTest`、`P4BackendParityTest`、および`ThreeExecutionBackendParityTest`によって検証される。

---

## 5. 評価

### 5.1 ケーススタディ1: tinyexpression

tinyexpressionは、金融トランザクション処理におけるユーザ定義関数（UDF: User-Defined Function）として使用される本番式評価器であり、現在本番環境で月間**10^9（10億）件のトランザクション**を処理している。設定可能な精度（float、double、int、long、BigDecimal、BigInteger）による数値式、文字列式、ブール式、条件式（if/else）、パターンマッチング（match）、変数束縛、ユーザ定義メソッド、型ヒント、および埋め込みJavaコードブロックをサポートする。

UBNF文法（`tinyexpression-p4-complete.ubnf`）は**520行**にわたり、完全なP4文法を定義する。この文法から、コードジェネレータは6つの生成ファイルにわたり約**2,000行**のJavaソースを生成する。手書きのエバリュエータロジック（`P4TypedAstEvaluator.java`）は**542行**であり、設定可能な数値型による数値演算、変数解決、比較演算、if/elseとmatchの評価、および文字列/ブールの型変換を含むすべての式型をカバーする。

総投資量――文法と手書きのエバリュエータロジック――は約**1,062行**であり、パーサ、型付きAST、マッパ、エバリュエータ、LSPサーバ、およびDAPサーバを備えた完全な言語実装を生み出す。

### 5.2 性能ベンチマーク

`BackendSpeedComparisonTest`は、5,000回のウォームアップ反復後の50,000回の反復を用いて、式`3+4+2+5-1`（リテラル演算）および`$a+$b+$c+$d-$e`（変数演算）によるバックエンド間の評価性能を測定する。

**セクション1: リテラル演算**

| バックエンド | 説明 | us/call | ベースラインとの比較 |
|---------|-------------|---------|--------------|
| (A) compile-hand | JVMバイトコード（JavaCodeCalculatorV3） | ~0.04 | 1.0x |
| (E2) P4-typed-reuse | Sealed switch、インスタンス再利用 | ~0.10 | 2.8x |
| (E) P4-typed-eval | Sealed switch、呼び出しごとに新規インスタンス | ~0.33 | 8.9x |
| (B) ast-hand-cached | 手書きAST、事前解析済み | ~0.42 | 11.4x |
| (C) ast-hand-full | 手書きAST、解析+構築+評価 | ~2.50 | ~68x |
| (D) P4-reflection | P4マッパ+リフレクションベースエバリュエータ | ~143.53 | 3,901x |

*表3: リテラル演算の評価レイテンシ。compile-handバックエンドは理論的最適値（JITコンパイルされたJavaバイトコード）を表す。P4-typed-reuseはこの最適値の3倍以内である。*

最も注目すべき結果は、P4-reflection（初期のリフレクションベースエバリュエータ、~143 us/call）とP4-typed-reuse（sealed-switchエバリュエータ、~0.10 us/call）の間の**1,400倍の改善**である。この改善は、`java.lang.reflect.Method.invoke()`を、sealed interfaceに対する生成済み網羅的switch式に置き換えることによって完全にもたらされる。JVMのJITコンパイラはswitchケースをインライン化し、仮想ディスパッチを除去し、recordインスタンスにスカラ置換を適用できる。

P4-typed-reuseバックエンドは、コンパイラではなくインタプリタでありながら、compile-handベースラインの**2.8倍**を達成する。これは注目に値する。sealed-switchエバリュエータは、ツリーウォーキング解釈を実行しているにもかかわらず、単純な式に対してJITコンパイルされたコードと競合力のある性能を持つ。

**セクション2: 変数演算**

| バックエンド | 説明 | us/call | ベースラインとの比較 |
|---------|-------------|---------|--------------|
| (F) compile-hand | 変数検索付きJVMバイトコード | ~0.06 | 1.0x |
| (H) P4-typed-var | 変数AST付きSealed switch | ~0.15 | 2.5x |
| (G) AstEvalCalc | 完全なAstEvaluatorCalculatorパス | ~8.50 | ~142x |

変数式も同様の相対的性能を示す。P4-typedバックエンドはJITコンパイルされたコードに対して2.5倍のオーバーヘッドを維持するのに対し、レガシーASTエバリュエータパスはリフレクションと複数のフォールバック層により2桁遅い。

### 5.3 ケーススタディ2: 回文認識

unlaxerの文脈自由構文解析を超える能力を実証し、tinyexpression以外の第2のケーススタディを提供するために、`MatchedTokenParser`を用いた回文認識を提示する。回文言語`L = { w w^R | w in Sigma* }`は、PEGや文脈自由文法では認識できない典型的な文脈依存言語である。

同一の`MatchedTokenParser`インスタンスに対して異なる操作を用いた5つの異なる回文認識器を実装した（第3.8節）。5つの実装すべてが`Usage003_01_Palidrome.java`において以下のテストベクトルに対してテストされている。

| 入力 | 期待結果 | 5つの実装すべてが一致 |
|-------|----------|----------------------------|
| "a" | 一致 | Yes |
| "abcba" | 一致（奇数長） | Yes |
| "abccba" | 一致（偶数長） | Yes |
| "aa" | 一致（自明） | Yes |
| "ab" | 不一致 | Yes |

5つの実装は、明示的なインデックス操作（`sliceWithWord`）から、最も簡潔なpythonian記法（`slicer.pythonian("::-1")`）までの範囲にわたる。この多様性は、MatchedTokenParserが正確性を維持しながら様々なプログラミングスタイルに対して十分な表現力を提供することを示している。

回文のケーススタディは2つの主張を検証する。(1) MatchedTokenParserはunlaxerの認識能力を文脈自由言語を超えて拡張すること、(2) コンビネータレベルのアプローチがJavaのテスト基盤（JUnit）およびデバッグツール（標準IDEデバッガが各`slice`/`effect`操作をステップ実行できる）と自然に統合されること。

### 5.4 開発工数の比較

3つのアプローチにおけるtinyexpressionの観測された開発工数を報告する。これらの数値は我々のケーススタディから得られたものであり、tinyexpressionの機能セットと複雑さを持つ言語の代表的なものとして解釈されるべきであり、普遍的に一般化可能な主張としてではない。

| アプローチ | コード行数 | 観測された工数 |
|----------|---------------|-----------------|
| スクラッチ実装（パーサ+AST+マッパ+エバリュエータ+LSP+DAP） | ~15,000 | ~8週間 |
| ANTLR+手書きエバリュエータ+手書きLSP/DAP | ~8,000 | ~5週間 |
| unlaxer（文法+evalXxxメソッド） | ~1,062 | ~3日間 |

*表4: ケーススタディで観測された開発工数の比較。コード行数には文法、生成コード（参考のみ――開発者が保守するものではない）、および手書きロジックが含まれる。*

「スクラッチ実装」の見積もりは以下の内訳に基づく。パーサ（~2,000行）、AST型（~1,500行）、マッパ（~1,000行）、エバリュエータ（~2,000行）、LSPサーバ（~2,500行）、DAPサーバ（~1,500行）。ANTLRの見積もりは、生成されたパーサとAST（パーサとASTの工数を削減）を考慮するが、手書きのマッパ、エバリュエータ、LSP、およびDAPを必要とする。unlaxerの数値は実際のtinyexpression実装を反映する。すなわち、520行の文法と542行のエバリュエータロジックである。

保守可能なコードの**14倍の削減**（~15,000行から~1,062行）が、我々のケーススタディで観測された主要な実用的利点である。しかし、認知的負荷の削減はおそらくより重要である。開発者は文法規則と評価意味論の観点で思考し、パーサの配管、トークンツリーの走査、プロトコルメッセージの処理の観点では思考しない。

### 5.5 本番デプロイメント

tinyexpressionは、金融トランザクション処理システム内のUDF（ユーザ定義関数）として本番環境にデプロイされている。このシステムは月間**10^9（10億）件のトランザクション**を処理し、tinyexpressionは分類、ルーティング、および派生値計算のために各トランザクションに対してユーザ定義式を評価する。

主要な本番メトリクス:
- **トランザクション量**: 10^9/月（持続的に~385評価/秒、バーストピークは大幅に高い）。
- **式の複雑さ**: 本番の式は単純な演算（`$amount * 1.1`）から、変数束縛とメソッド呼び出しを含む複雑な条件ロジック（式あたり50以上のASTノード）まで多岐にわたる。
- **本番で使用されるバックエンド**: P4-typed-reuseバックエンド（インスタンス再利用付きsealed-switchエバリュエータ）が本番で使用されており、典型的な式に対してサブマイクロ秒の評価レイテンシを達成している。
- **信頼性**: sealed interfaceの網羅性保証により、文法変更がサイレントな評価失敗を引き起こすことはない――不足する評価メソッドはデプロイ前にコンパイル時に検出される。

### 5.6 LLM支援開発

unlaxer-parserの型安全で生成ベースのアーキテクチャは、LLM支援開発ワークフローに対して定性的な利点を提供する。我々の経験は以下の観察を示唆する。

**トークン効率。** フレームワークなしでは、LLMはパーサコンビネータ、AST型、マッパロジック、およびエバリュエータコードをスクラッチから生成する必要があり、通常、相当なコンテキストと生成トークンを必要とする。unlaxerを使用する場合、LLMは`evalXxx`メソッドの本体のみを生成すればよく、トークン予算を大幅に削減する。

**ガイダンスとしての型安全性。** sealed interfaceの網羅性保証により、LLMがASTノード型の処理を「忘れる」ことはありえない――Javaコンパイラがコードを拒否するのである。文法が新しい`@mapping`規則で拡張されると、生成されたエバリュエータ基底クラスに新しい抽象メソッドが追加される。未実装メソッドを列挙するコンパイラエラーは、追加のプロンプトなしにLLMが従うことのできる正確で機械可読なTODOリストとして機能する。この「コンパイラをオーケストレータとして使う」パターンが有効なのは、LLMに必要な情報（どのメソッドを実装するか、どのパラメータ型で）を過不足なく提供するためである。

LLM支援開発の利点の厳密な評価（例: トークン使用量とタスク完了時間を測定する対照実験）は今後の課題として残されていることを付記する。

---

## 6. 議論

### 6.1 制限事項

**Java専用の生成。** 現在のコードジェネレータはJavaソースファイルのみを生成する。JVMベースの言語（Kotlin、Scala、Clojure）は生成されたJavaコードと相互運用できるが、非JVM言語のネイティブサポートは利用できない。文法記法（UBNF）は言語非依存であるが、ジェネレータパイプラインとパーサコンビネータランタイムはJava固有である。

**PEGベースの構文解析。** UBNFはPEG意味論（順序付き選択）を使用するため、曖昧な文法は選択肢の順序によって決定論的に解決される。これはほとんどのDSLにとっては特徴であるが、曖昧性報告を必要とする言語（例: 自然言語処理）や、順序付き選択が予想外の結果を生む言語にとっては制限である。曖昧な文法を扱い構文解析フォレストを生成できるGLRパーサはサポートされていない。

**エラー回復。** PEGの順序付き選択意味論は、堅牢なエラー回復を困難にする。選択肢の代替が失敗すると、パーサはバックトラックして次の代替を試行する。「部分一致」を報告したり、エラーのある入力をスキップして構文解析を継続する機構は存在しない。`ErrorMessageParser`は失敗地点の診断を提供するが、パーサは現在、ANTLRに見られる「エラー回復」戦略（トークン挿入、トークン削除、パニックモード回復）をサポートしていない。生成されたLSPサーバの診断機能は、現在、完全な構文解析が試みられるシナリオに限定されており、部分入力に対する診断精度は改善の余地がある。

**単一の本番ユーザ。** tinyexpressionプロジェクトがフレームワークの主要な本番ユーザである。回文のケーススタディは文脈依存認識タスクへのフレームワークの適用可能性を示しているが、我々の設計選択の汎用性を確認するには、サードパーティ開発者による多様なDSLプロジェクトにわたる広範な検証が必要である。

**不完全な文法カバレッジ。** P4文法はtinyexpression言語のすべての機能をまだカバーしていない。外部Javaメソッド呼び出し、文字列スライシング（`$msg[0:3]`）、および一部の文字列メソッドなどのいくつかの構文はレガシーパーサによって処理され、以前のバックエンドにフォールバックする。共存モデル（P4パーサとレガシーフォールバック）は機能しているが、複雑さを増す。

**ベンチマーク手法。** 第5.2節の性能ベンチマークは、JMH（Java Microbenchmark Harness）ではなくカスタムベンチマークハーネス（`BackendSpeedComparisonTest`）を使用している。ウォームアップ反復と繰り返し測定を使用しているが、JMHが提供するJITコンパイル、ガベージコレクション、およびプロセス分離に対するより厳密な制御は行っていない。JMHベースのベンチマークを今後の改訂で追加する予定である。桁数レベルの関係（リフレクションからsealed-switchへの1,400倍の改善、sealed-switchとJITコンパイルの2.8倍のオーバーヘッド）はJMH測定下でも維持されると考えるが、正確な数値は変動しうる。

### 6.2 妥当性への脅威

**内的妥当性。** 性能ベンチマークはJMHではなくカスタムテストハーネスを使用して実施された。ハーネスにはウォームアップ反復（5,000回）と測定反復（50,000回）が含まれるが、JMHの厳密さでJVMフォーク分離、GC圧力、JIT段階コンパイルを制御していない。近似表記（~0.10 us/call）はこの制限を反映している。加えて、表4の開発工数見積もりはunlaxerと従来のアプローチの両方に対する著者の経験に基づいており、フレームワークに精通していない開発者には一般化できない可能性がある。

**外的妥当性。** 評価はtinyexpression（本番式評価器）と回文認識（文脈依存パターン）の2つのケーススタディに基づいている。両方ともunlaxerの設計に深い知識を持つフレームワーク作者によって開発された。サードパーティのDSL実装は、フレームワークの汎用性と使いやすさのより強い証拠を提供するであろう。工数削減の主張は、我々のケーススタディからの観察として解釈されるべきであり、普遍的な予測としてではない。

**構成概念妥当性。** 開発工数の主要なメトリクスとしてコード行数を使用し、時間見積もりで補足している。LOCはコード品質、保守性、テストカバレッジを捉えない粗い尺度である。「スクラッチ実装」のLOC見積もりは、実際の実装ではなく推定値である。

### 6.3 今後の課題

**JMHベンチマーク。** `@Benchmark`、`@Warmup(iterations=10, time=1s)`、`@Measurement(iterations=10, time=1s)`、および`@Fork(3)`を備えたJMHへの性能ベンチマーク移行を計画しており、平均、標準偏差、および99パーセンタイルレイテンシを報告する予定である。

**追加のケーススタディ。** サードパーティのDSL実装による外部検証が、今後の評価の主要なターゲットである。合成文法ベンチマーク（規則数、再帰深度、`@mapping`密度を変化させる）もスケーラビリティデータを提供するであろう。

**宣言的評価アノテーション。** UBNFを`@eval`アノテーションで拡張し、文法内で直接評価戦略を指定することを計画しており、一般的なパターンに対する手書きエバリュエータコードをさらに削減する。

**エラー回復。** PEG互換のエラー回復戦略（現在の失敗地点診断を超える）の研究が、生成されたLSPサーバの不完全な入力の処理を改善するために必要である。

**インクリメンタル構文解析。** 現在のパーサは変更のたびに入力全体を再解析する。IDE統合（LSP）のためには、変更された領域のみを再解析し、変更のない領域の解析結果を再利用するインクリメンタル構文解析が、大規模ドキュメントに対する応答性を大幅に改善するであろう。

**多言語コード生成。** UBNF文法からTypeScript、Python、またはRustソースを生成することで、フレームワークの適用範囲をJVMエコシステムの外に拡張する。

**圏論的形式化。** PropagationStopper階層の圏論的定式化――パーサ状態の適切な圏における自己準同型としてストッパを特徴づけること――は、今後の理論研究として興味深い方向である。ただし、第3.6節の操作的意味論がストッパの振る舞いに関する実用的な推論には十分であることを付記する。

---

## 7. 結論

本論文では、単一のUBNF文法仕様から6つの相互関連するアーティファクト――パーサ、AST型、構文解析木からASTへのマッパ、エバリュエータスケルトン、LSPサーバ、およびDAPサーバ――を生成するJava 21フレームワーク、unlaxer-parserを提示した。本フレームワークは、DSL開発の根本的な問題、すなわち相互に整合性を保つ必要がある複数の緊密に結合したサブシステムを構築・保守する必要性に対処するものである。

4つの貢献はこの統一生成における特定の課題に対処する。

1. **伝搬制御**は、4つの伝搬ストッパクラスの階層を通じて、2つの直交する次元（`TokenKind`と`invertMatch`）に作用し、パースモードがコンビネータツリーをどのように伝搬するかに対するきめ細かく合成的な制御を提供する。形式的な操作的意味論（第3.6節）を提供し、ストッパ階層の代数的性質を実証し、Readerモナドの`local`との正確な対応関係を示した（第3.7節）。この貢献の価値は、モナディック抽象自体――これはよく知られている――にあるのではなく、パーサコンビネータのための特定の設計パターンとしての同定、JavaにおけるファーストクラスAPIとしての実現、およびコード生成パイプラインへの統合にある。

2. **メタデータ搬送構文解析木**は、`ContainerParser<T>`を通じて、単一の構文解析パス中にエラーメッセージと補完候補を構文解析木に直接埋め込むことを可能にする。これはWriterモナドの`tell`操作に対応する。実用的な利点は、LSP機能がASTと同じ構文解析パスから導出され、一貫性が保証されることである。

3. **エバリュエータのためのGeneration Gap Pattern**は、Java 21のsealed interfaceおよび網羅的switch式と組み合わせることで、コンパイラによる網羅性保証を提供する。文法に新しいASTノード型が追加されると、開発者が対応する評価メソッドを実装するまでコンパイラが手書きエバリュエータを拒否する。

4. **MatchedTokenParser**は、フレームワークの認識能力を文脈自由言語を超えて拡張し、コンビネータレベルで回文認識とXMLタグ対応を可能にする。Macro PEG [Mizushima 2016]に着想を得たMatchedTokenParserは、合成可能なslice、effect、およびpythonian操作によるキャプチャ・再生意味論を提供する。

評価は実用的な影響を実証している。金融トランザクション処理においてUDFとして月間**10^9件のトランザクション**を処理する本番式評価器tinyexpressionは、スクラッチ実装と比較して保守可能なコードの**14倍の削減**（~15,000行から~1,062行）を達成する。性能ベンチマークは、リフレクションベースからsealed-switch評価への**1,400倍の改善**を示し、sealed-switchエバリュエータはJITコンパイルされたバイトコードの**2.8倍**以内で動作する。第2のケーススタディは、MatchedTokenParserを用いた5つの異なる回文認識器の実装を実証し、フレームワークが文脈依存パターンを処理する能力を検証している。

本フレームワークはMITライセンスの下でオープンソースソフトウェアとして提供され、Maven Centralに`org.unlaxer:unlaxer-common`および`org.unlaxer:unlaxer-dsl`として公開されている。

---

## References

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

以下の最小限のUBNF文法とエバリュエータは、完全な生成パイプラインを実証する。

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

この35行の文法と17行のエバリュエータから、パーサ、AST、マッパ、エバリュエータ、LSPサーバ、およびDAPサーバを備えた完全な計算器が生成される。

---

## 付録B: PropagationStopper決定行列

| ストッパ | 子へのTokenKind | 子へのinvertMatch | ユースケース |
|---------|-------------------|---------------------|----------|
| *(なし)* | 親の値 | 親の値 | デフォルト伝搬 |
| `AllPropagationStopper` | `consumed` | `false` | 部分式に対するすべてのパースモードのリセット |
| `DoConsumePropagationStopper` | `consumed` | 親の値 | matchOnlyコンテキスト内での消費の強制 |
| `InvertMatchPropagationStopper` | 親の値 | `false` | NOT意味論がサブパーサに伝搬することの防止 |
| `NotPropagatableSource` | 親の値 | `!parent` | 反転フラグを反転させることによる論理NOTの実装 |

---

## 付録C: PropagationStopper合成表

以下の表は、すべてのストッパの対に対する合成`f . g`を示す。ここで`f`は`g`の後に適用される。

|  f \ g | Id | AllStop | DoConsume | StopInvert | NotProp |
|--------|-----|---------|-----------|------------|---------|
| **Id** | Id | AllStop | DoConsume | StopInvert | NotProp |
| **AllStop** | AllStop | AllStop | AllStop | AllStop | AllStop |
| **DoConsume** | DoConsume | AllStop | DoConsume | AllStop | DoConsume' |
| **StopInvert** | StopInvert | AllStop | AllStop | StopInvert | StopInvert |
| **NotProp** | NotProp | AllStop | DoConsume' | ForceInvert | Id |

ここで:
- `DoConsume'`: `(tk, inv) -> (consumed, not(inv))` -- 消費を強制し一致を反転する。
- `ForceInvert`: `(tk, inv) -> (tk, true)` -- invertMatchをtrueに強制する。

注目すべき性質:
- `AllStop`は右吸収元である: すべての`X`に対して`AllStop . X = AllStop`。
- `Id`は単位元である: すべての`X`に対して`X . Id = Id . X = X`。
- `NotProp`は対合である: `NotProp . NotProp = Id`。
- `DoConsume . StopInvert = StopInvert . DoConsume = AllStop`（この対では可換）。
- `StopInvert . NotProp != NotProp . StopInvert`（一般には非可換）。

---

*ACM SIGPLAN International Conference on Software Language Engineering (SLE), 2026に投稿。*

---

## ナビゲーション

[← インデックスに戻る](../INDEX.md)

| 論文 (JA) | 査読 |
|-----------|------|
| [← v1 論文](../v1/from-grammar-to-ide.ja.md) | [v1 査読](../v1/review-dialogue-v1.ja.md) |
| **v2 — 現在** | [v2 査読](./review-dialogue-v2.ja.md) |
| [v3 論文 →](../v3/from-grammar-to-ide.ja.md) | — |
