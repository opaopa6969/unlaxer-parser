# 文法からIDEへ: 単一の文法仕様からのパーサ、AST、評価器、LSP、DAPの統一的生成

**Claude (Anthropic) および unlaxer-parser 開発チーム**

---

## 概要

ドメイン固有言語（DSL）の実装には、複数の相互に関連する成果物が必要である。すなわち、パーサ、抽象構文木（AST）の型定義、構文解析木からASTへのマッパー、意味評価器、そして Language Server Protocol（LSP）および Debug Adapter Protocol（DAP）によるIDE支援である。実際には、これら6つのサブシステムは通常、独立して構築・保守されており、コンポーネント間の不整合、コードの重複、そして多大な保守負担を招いている。文法の一箇所を変更するだけで、数千行の手書きコードに波及的な修正が必要となり得る。本論文では、unlaxer-parser を提示する。これは Java 21 フレームワークであり、単一の UBNF（Unlaxer Backus-Naur Form）文法仕様から6つの成果物すべてを生成するものである。本論文では3つの新規貢献を提示する: (1) パーサコンビネータに対する伝播制御機構であり、トークン消費モードとマッチ反転という2つの直交する構文解析次元に対して、PropagationStopper（伝播制御機構）の階層を通じた細粒度の制御を提供する; (2) `ContainerParser<T>` によるメタデータ搬送構文解析木であり、入力を消費せずにエラーメッセージや補完候補を構文解析木に直接埋め込む; (3) 評価器のための Generation Gap Pattern（GGP、生成ギャップパターン）であり、Java 21 の sealed interface（封印インタフェース）と網羅的 switch 式を用いてコンパイラによる完全性保証を提供する。本フレームワークを金融計算用の本番式評価器である tinyexpression を用いて評価し、スクラッチ実装と比較してコード量の10倍の削減、およびリフレクションベースの AST 評価から sealed-switch ベースの評価への移行による1400倍の性能向上を実証する。

---

## 1. はじめに

ドメイン固有言語の構築には、文法とパーサを書くだけでは到底足りない。完全かつ本番品質の DSL 実装には、少なくとも以下の6つの密結合したサブシステムが必要である:

1. **パーサ**: 言語の具象構文を認識し、構文解析木を生成する。
2. **AST 型定義**: 抽象構文を表現する型付きデータ構造の集合である。
3. **構文解析木から AST へのマッパー**: 平坦な具象構文解析木を構造化された型付き AST に変換する。
4. **評価器またはインタプリタ**: AST を走査し、言語の意味論に従って値を計算する。
5. **Language Server Protocol（LSP）サーバ**: シンタックスハイライト、コード補完、ホバードキュメント、診断エラー報告などのエディタ非依存の IDE 機能を提供する。
6. **Debug Adapter Protocol（DAP）サーバ**: ステップ実行、ブレークポイント管理、変数検査、スタックトレース表示を DAP 互換のあらゆるエディタで実現する。

従来の手法では、これらのサブシステムはそれぞれ独立して開発される。文法の変更――新しい演算子の追加、新しい式型の導入、優先順位規則の変更――は、6つすべてのコンポーネントにわたる協調的な更新を必要とする。この結合は欠陥の周知の原因である。パーサが受理する構文を評価器が処理できない、LSP サーバが提案する補完をパーサが拒否する、リファクタリング後に AST 型が文法と乖離する、といった事態が生じ得る。

既存のツールはこの問題の一部に対処している。ANTLR [Parr and Fisher 2011] は注釈付き文法からパーサを生成し、オプションで AST ノード型も生成するが、評価器、LSP サーバ、DAP サーバは生成しない。Tree-sitter [Brunel et al. 2023] はエディタ向けの増分構文解析を提供するが、意味層は生成しない。PEG ベースのパーサジェネレータ [Ford 2004] は通常、認識器のみを生成し、下流の成果物はすべて開発者に委ねられる。Parsec [Leijen and Meijer 2001] のようなパーサコンビネータライブラリは、ホスト言語における合成的なパーサ構築を提供するが、やはり構文解析で終わる。

これらのツールのいずれも、文法から IDE までの完全なスタックを生成するものではない。

本論文では unlaxer-parser を提示する。これは2つのモジュール――`unlaxer-common`（パーサコンビネータランタイム、約436個の Java ソースファイル）と `unlaxer-dsl`（コード生成パイプライン）――からなる Java 21 フレームワークであり、単一の `.ubnf` 文法ファイルを入力として、6つの Java ソースファイルを生成する: `Parsers.java`、`AST.java`、`Mapper.java`、`Evaluator.java`、言語サーバ、およびデバッグアダプタである。開発者が記述する必要があるのは文法と評価ロジック（通常50--200行の `evalXxx` メソッド）のみであり、それ以外はすべてフレームワークによって生成・保守される。

本論文の貢献は以下の3点である:

1. **パーサコンビネータの伝播制御**（第3.3節）: 構文解析モード（`TokenKind` および `invertMatch`）がコンビネータ木をどのように伝播するかを制御する機構であり、既存のフレームワークには同等のものが存在しない。
2. **メタデータ搬送構文解析木**（第3.4節）: `ContainerParser<T>` により、型付きメタデータ（エラーメッセージ、補完候補）を入力を消費せずに構文解析木に挿入し、単一の構文解析パスから LSP 機能を導出することを可能にする。
3. **評価器のための Generation Gap Pattern**（第3.5節）: 網羅的な sealed-switch ディスパッチを備えた生成済み抽象評価器クラスと、再生成に耐える手書きの具象実装を組み合わせるパターンである。

本論文の残りは以下のように構成される。第2節では、パーサ生成、IDE プロトコル支援、コード生成パターンに関する関連研究を概観する。第3節では、UBNF 文法記法、生成パイプライン、および3つの新規貢献を含むシステム設計を提示する。第4節では実装について記述する。第5節では tinyexpression を用いてフレームワークを評価し、性能ベンチマークと開発工数の比較を提示する。第6節では制約と将来の課題を議論する。第7節で結論を述べる。

---

## 2. 背景と関連研究

### 2.1 パーサジェネレータ

パーサジェネレータの歴史は半世紀に及ぶ。Yacc [Johnson 1975] およびその後継である Bison は、BNF で記述された文脈自由文法から LALR(1) パーサを生成する。これらのツールは効率的なテーブル駆動パーサを生成するが、文法が曖昧でなく左因数分解されていることを要求し、これは言語設計者にとって負担となり得る。LALR パーサのエラーメッセージは不親切であることで知られており、生成されるパーサは構文解析木を生成するが、型付き AST は生成しない。

ANTLR [Parr and Fisher 2011] は ALL(*) を導入した。これは LALR(1) より広い文法クラスを扱える適応的 LL ベースの構文解析戦略である。ANTLR はレキサーとパーサの両方を生成し、オプションで木走査のためのビジターまたはリスナーの基底クラスも生成する。しかし、ANTLR のビジターパターンでは開発者が各 `visitXxx` メソッドを手作業で実装する必要があり、ANTLR は評価器、LSP サーバ、DAP サーバのいずれも生成しない。下流の成果物はすべて開発者の責任である。

Parsing Expression Grammar（PEG）[Ford 2004] は、文脈自由文法に対する認識ベースの代替手段を提供する。PEG は非順序選択（`|`）の代わりに順序付き選択（`/`）を用い、構成上の曖昧さを排除する。メモ化を伴うパックラットパーサを含む PEG ベースのパーサは、その予測可能性と実装の容易さから人気を得ている。しかし、PEG パーサは通常、認識器に過ぎない――入力が文法にマッチするかどうかを判定するが、構造化された構文解析木を本質的には生成しない。Ierusalimschy の LPEG [Ierusalimschy 2009] や Redziejowski の PEG 基礎に関する研究 [Redziejowski 2007] を含むいくつかの PEG ベースのツールは、認識問題に焦点を当てており、AST 構築や IDE 統合には対処していない。

パーサコンビネータライブラリは異なるアプローチを採る。パーサはホスト言語における第一級の値であり、高階関数を用いて合成される。Haskell で記述された Parsec [Leijen and Meijer 2001] は、コミット選択意味論による明確なエラーメッセージを提供するモナディックパーサコンビネータによってこのパラダイムを確立した。Scala パーサコンビネータはこのアプローチを JVM にもたらした。パーサコンビネータは優れた合成可能性とホスト言語の型システムとの統合を提供するが、パーサ自体を超えるものは何も生成しない。

unlaxer-parser はこの領域において独自の位置を占める。Parsec のようなパーサコンビネータライブラリであると同時に、ANTLR のようなコードジェネレータでもあるが、どちらとも異なり、パーサから IDE 支援に至る完全な成果物スタックを生成する。表1に比較を要約する。

| ツール | パーサ | AST 型 | マッパー | 評価器 | LSP | DAP |
|------|--------|-----------|--------|-----------|-----|-----|
| Yacc/Bison | あり | なし | なし | なし | なし | なし |
| ANTLR | あり | 部分的 | なし | なし | なし | なし |
| PEG ツール | あり | なし | なし | なし | なし | なし |
| Parsec | あり | なし | なし | なし | なし | なし |
| tree-sitter | あり | なし | なし | なし | 部分的 | なし |
| **unlaxer-parser** | **あり** | **あり** | **あり** | **あり** | **あり** | **あり** |

*表1: ツール別の生成成果物。「部分的」は、ツールがインフラストラクチャを提供するが、かなりの手書きコードを必要とすることを示す。*

### 2.2 Language Server Protocol と Debug Adapter Protocol

Language Server Protocol（LSP）[Microsoft 2016a] は、エディタと言語固有のインテリジェンスプロバイダ間の通信を標準化するものである。LSP サーバは、コード補完、ホバー情報、定義へのジャンプ、参照検索、診断（エラー・警告報告）、コードアクションなどの機能を実装する。LSP 以前は、各エディタに言語固有のプラグインが必要であった。LSP はエディタ支援を言語実装から分離し、単一のサーバで VS Code、Emacs、Vim、およびその他の LSP 互換エディタと連携することを可能にした。

Debug Adapter Protocol（DAP）[Microsoft 2016b] は、同じ分離パターンをデバッグに適用する。DAP サーバは起動・アタッチ、ブレークポイント、ステップオーバー・ステップイン・ステップアウト、スタックトレース、変数検査、式評価を実装する。LSP と同様に、DAP は単一のデバッグアダプタを複数のエディタで動作させることを可能にする。

標準化にもかかわらず、LSP や DAP サーバの実装は依然として労力を要する。中程度の複雑さの言語に対する典型的な LSP サーバは2,000--5,000行のコードを必要とし、DAP サーバは1,000--2,000行を必要とする。これらのサーバは、文法、AST、評価器との同期を維持しなければならない。Tree-sitter [Brunel et al. 2023] は、増分構文解析、シンタックスハイライト、基本的な構造クエリを提供することで LSP 統合に部分的に対処しているが、型認識補完や診断報告などの意味的機能は提供しない。

unlaxer-parser は文法から LSP サーバと DAP サーバの両方を生成する。生成された LSP サーバは、補完（文法キーワード、`@catalog`/`@declares` 注釈に基づく）、診断（構文解析エラーおよび `ErrorMessageParser` メタデータに基づく）、ホバー（`@doc` 注釈に基づく）を提供する。生成された DAP サーバは、構文解析木を通じたステップ実行、ブレークポイント支援、および現在のトークンのテキストとパーサ名を表示する変数表示を提供する。

### 2.3 コード生成パターン

2つのコード生成パターンが本研究に特に関連する。

**Visitor パターン** [Gamma et al. 1994] は、生成されたパーサにおける AST ノード走査の標準的手法である。ANTLR は、文法規則ごとに1つの `visitXxx` メソッドを持つビジターインタフェースを生成する。このパターンは木構造とその上で実行される操作との間の良好な分離を提供するが、完全性を強制しない。開発者がビジットメソッドの実装を容易に忘れることができ、実行時エラーやサイレントな不正動作を招き得る。

**Generation Gap Pattern**（GGP）[Vlissides 1996] は、コード生成における根本的な緊張を解決する。すなわち、生成コードは入力（文法）が変更されたときに再生成される必要があるが、手書きのカスタマイズは再生成に耐えなければならない。GGP は各クラスを2つに分割することでこれを解決する。生成された抽象基底クラスと手書きの具象サブクラスである。生成された基底クラスは構造的ボイラープレートを含み、具象サブクラスは手書きのロジックを含む。ジェネレータが再度実行されると、基底クラスのみが上書きされる。

unlaxer-parser は GGP を Java 21 の sealed interface と網羅的 switch 式と組み合わせる。生成された評価器基底クラスは、sealed AST インタフェースに対する switch 式を含むプライベートな `evalInternal` メソッドを含む:

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

`TinyExpressionP4AST` は sealed interface であるため、Java コンパイラはすべての許可されたサブタイプが switch でカバーされていることを検証する。新しい AST ノード型が文法に追加されると、開発者が対応する `evalXxx` メソッドを実装するまで、コンパイラは手書きの具象クラスを拒否する。これにより、実行時エラー（欠落したビジターメソッド）がコンパイル時エラーに変換される――安全性における顕著な改善である。

---

## 3. システム設計

### 3.1 UBNF 文法記法

UBNF（Unlaxer Backus-Naur Form）は、コード生成を制御する注釈で標準的な EBNF を拡張したものである。UBNF 文法ファイルは、グローバル設定、トークン宣言、規則宣言を含む名前付き文法を定義する。

**グローバル設定** は生成パイプラインを構成する:

```ubnf
grammar TinyExpressionP4 {
  @package: org.unlaxer.tinyexpression.generated.p4
  @whitespace: javaStyle
  @comment: { line: '//' }
```

`@package` 設定は生成コードの Java パッケージを指定する。`@whitespace` 設定は空白処理プロファイルを選択する（ここでは、`//` および `/* */` コメントをインターリーブトークンとして含む Java スタイルの空白処理）。`@comment` 設定はコメント構文を宣言する。

**トークン宣言** は終端記号をパーサクラスにバインドする:

```ubnf
  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token STRING     = SingleQuotedParser
  token CODE_BLOCK = org.unlaxer.tinyexpression.parser.javalang.CodeParser
```

各トークンは記号名を `unlaxer-common` ライブラリ（またはユーザ定義パーサ）の具象パーサクラスにマッピングする。これにより、文法は複雑な字句パターン（Java スタイルのコードブロックなど）を文法記法にエンコードすることなく参照できる。

**規則宣言** は注釈付きの EBNF 風構文を用いる:

```ubnf
  @root
  Formula ::= { VariableDeclaration } { Annotation } Expression { MethodDeclaration } ;
```

`@root` 注釈は文法のエントリポイントを示す。波括弧 `{ ... }` は0回以上の繰り返しを示し、角括弧 `[ ... ]` はオプション要素を示し、丸括弧 `( ... )` はグループ化を示し、`|` は順序付き選択（PEG 意味論）を示す。

**`@mapping` 注釈** は AST 生成の中心的機構である:

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=10)
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

この注釈はコードジェネレータに以下を指示する:
1. sealed AST インタフェースに、`left`、`op`、`right` フィールドを持つレコード型 `BinaryExpr` を作成する。
2. 構文解析木から `@left`、`@op`、`@right` 注釈付き要素を抽出して `BinaryExpr` インスタンスを構築するマッパー規則を生成する。
3. 評価器スケルトンに `evalBinaryExpr` 抽象メソッドを生成する。

`@leftAssoc` 注釈はマッパーで左結合グループ化を生成し、`@precedence(level=N)` は曖昧性解消のための優先順位レベルを確立する。

追加の注釈には、空白挿入を制御する `@interleave(profile=javaStyle)`、メソッド宣言におけるスコープ意味論のための `@scopeTree(mode=lexical)`、参照解決のための `@backref(name=X)`、シンボル宣言のための `@declares`、補完カタログのための `@catalog`、ホバードキュメントのための `@doc` が含まれる。完全な tinyexpression 文法（`tinyexpression-p4-complete.ubnf`）は520行に及び、数値式、文字列式、ブール式、オブジェクト式、変数宣言、メソッド宣言、if/else 式および match 式、import 宣言、埋め込み Java コードブロックをカバーする。

### 3.2 生成パイプライン

生成パイプラインは `.ubnf` 文法ファイルを6つの Java ソースファイルに変換する。パイプラインは3つのフェーズで構成される:

**フェーズ1: 構文解析。** UBNF 文法ファイルは自己ホスティングパーサ（`UBNFParsers`）によって解析される。UBNF パーサ自体が unlaxer-parser のコンビネータライブラリを用いて構築されている。構文解析木は、ジェネレータがユーザ文法に対して生成するのと同じ `sealed interface + record` パターンを用いて、型付き AST（`UBNFAST`）にマッピングされる。`UBNFAST` 自体が sealed interface である:

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

**フェーズ2: 検証。** `GrammarValidator` は文法の整形性を検査する。未定義の規則参照、重複した規則名、欠落した `@root` 注釈、`@mapping` パラメータと規則構造の整合性が検査対象である。

**フェーズ3: コード生成。** それぞれ `CodeGenerator` インタフェースを実装する6つのコードジェネレータが出力を生成する:

| ジェネレータ | 出力 | 説明 |
|-----------|--------|-------------|
| `ParserGenerator` | `XxxParsers.java` | `LazyChain`、`LazyChoice`、`LazyZeroOrMore` 等を用いた PEG ベースのパーサコンビネータ。空白処理は `@interleave` プロファイルに基づいて自動的に挿入される。 |
| `ASTGenerator` | `XxxAST.java` | `@mapping` クラスごとに1つの `record` を持つ Java 21 sealed interface。フィールドは `params` リストに従って型付けされる。 |
| `MapperGenerator` | `XxxMapper.java` | トークン木から AST へのマッピングロジック。複数規則マッピング（複数の文法規則が同一 AST クラスにマッピングされる場合）、`@leftAssoc`/`@rightAssoc` グループ化、ネストされた部分式への深い探索を防止する `findDirectDescendants` を処理する。 |
| `EvaluatorGenerator` | `XxxEvaluator.java` | 網羅的な sealed-switch ディスパッチとステップデバッグフック用の `DebugStrategy` インタフェースを備えた抽象クラス。 |
| `LSPGenerator` | `XxxLanguageServer.java` | 補完（キーワード、`@catalog` エントリ、`@declares` シンボル）、診断（構文解析エラー）、ホバー（`@doc` 注釈）を備えた LSP サーバ。`@declares`/`@backref`/`@scopeTree` 注釈が存在する場合、スコープストア登録を含む。 |
| `DAPGenerator` | `XxxDebugAdapter.java` | `stopOnEntry` 支援、構文解析木を通じたステップオーバー実行、ブレークポイント管理、スタックトレース表示、変数検査を備えた DAP サーバ。 |

### 3.3 新規貢献: 伝播制御

unlaxer-parser のコンビネータアーキテクチャでは、すべてのパーサの `parse` メソッドは3つのパラメータを受け取る:

```java
public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch)
```

- `ParseContext` は入力カーソル、トランザクションスタック、トークン木を管理する。
- `TokenKind` は、パーサが入力を*消費*するか（`consumed`）、カーソルを進めずに単に*マッチ*するか（`matchOnly`）を制御する。これは先読み述語の PEG における等価物である。
- `invertMatch` はパーサの成功・失敗の意味論を反転させる――`true` の場合、マッチ成功は失敗として扱われ、その逆も同様である。これは PEG の「not」述語である。

素朴な実装では、`tokenKind` と `invertMatch` の両方が親から子へ無条件に伝播する。これは問題を生じさせる。`Not` コンビネータを考えてみよう:

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

`Not` は子を `matchOnly` モードに強制する（先読み中に入力を消費してはならない）。しかし、子が `Not` コンビネータを自身の内部に含む複雑な部分式である場合はどうなるか。外側の `Not` からの `matchOnly` は下方に伝播するが、内側の `DoConsumePropagationStopper` はこれを選択的にオーバーライドできるべきである。

我々は **PropagationStopper 階層** を導入する。これは、この2次元の伝播に対して細粒度の制御を提供する4つのクラスの集合である:

```java
public interface PropagationStopper { }
```

**1. AllPropagationStopper**: `TokenKind` と `invertMatch` の両方の伝播を停止する。子は、親が何を渡しても、常に `TokenKind.consumed` と `invertMatch=false` を受け取る:

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

**2. DoConsumePropagationStopper**: `TokenKind` の伝播のみを停止し、子を `consumed` モードに強制しつつ、`invertMatch` は通過させる:

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

**3. InvertMatchPropagationStopper**: `invertMatch` の伝播のみを停止し、子を `invertMatch=false` に強制しつつ、`TokenKind` は通過させる:

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

**4. NotPropagatableSource**: `invertMatch` フラグを反転させる（伝播された値に対する論理 NOT）。親が `invertMatch=true` を渡すと子は `invertMatch=false` を受け取り、その逆も同様である:

```java
public class NotPropagatableSource extends AbstractPropagatableSource {
    @Override
    public boolean computeInvertMatch(boolean fromParentValue, boolean thisSourceValue) {
        return false == fromParentValue;
    }
}
```

この階層は `(TokenKind, invertMatch)` ペアに対する **2次元の制御フロー** として形式的に特徴づけることができる。各 PropagationStopper は、どの次元を遮断し、どの値を代入するかを選択する。この設計は合成的である。PropagationStopper はネストでき、それぞれが対応する次元に対して独立に動作する。

我々の知る限り、既存のパーサコンビネータフレームワークで、構文解析モードの伝播に対してこのレベルの制御を提供するものは存在しない。Parsec は `try` と `lookAhead` コンビネータで先読みを扱うが、これらは独立した次元に沿って合成されない。ANTLR の意味述語は異なる抽象度で動作する。

### 3.4 新規貢献: メタデータ搬送構文解析木

unlaxer-parser における根本的な洞察は、構文解析木が構文解析フェーズと IDE 統合フェーズの間の**通信チャネル**として機能し得るということである。これは `ContainerParser<T>` によって実現される。これは入力を消費せずに型付きメタデータを構文解析木に挿入する抽象パーサクラスである:

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

重要な性質は、`ContainerParser` が現在のカーソル位置に**空トークン**を作成することである――入力を消費せずに成功する。パーサインスタンス自体がメタデータを搬送し、`get()` および `get(CursorRange)` メソッドを通じてアクセス可能である。構文解析後、メタデータは特定の `ContainerParser` サブクラスのインスタンスであるパーサのトークンをフィルタリングすることで、トークン木から抽出できる。

2つの具象サブクラスがこのパターンを実証する:

**ErrorMessageParser** は診断報告のためにエラーメッセージを構文解析木に埋め込む:

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

`expectedHintOnly=true` で使用する場合、パーサは意図的に失敗するが、自身を「期待された」トークンとして登録し、その位置で何が期待されていたかの人間が読める記述をエラー報告システムに提供する。この情報は LSP サーバの診断ハンドラに直接流れる。

**SuggestsCollectorParser** は構文解析中に兄弟パーサから補完候補を収集する:

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

このパーサは、残りの入力に基づいて、兄弟パーサ（コンビネータ木の同一レベルにあるパーサ）に補完候補を問い合わせる。候補はトークン木に格納され、後に LSP サーバの補完ハンドラによって抽出される。

メタデータ搬送構文解析木パターンにより、単一の構文解析パスで評価と IDE 機能の両方に必要なすべての情報を生成することが可能になる。この機構がなければ、LSP と DAP の統合には別個のパスや並列的なデータ構造が必要となり、複雑さと不整合のリスクが増大する。

### 3.5 新規貢献: 評価器のための Generation Gap Pattern

Generation Gap Pattern（GGP）[Vlissides 1996] は、生成コードと手書きコードを継承関係にある異なるクラスに配置することで分離する。unlaxer-parser は GGP を評価器構築に適用し、決定的な拡張を加える。すなわち、Java 21 の sealed interface が**コンパイラによる完全性検査**を提供する。

ジェネレータは抽象評価器クラスを生成する:

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
            case TinyExpressionP4AST.ExpressionExpr n -> evalExpressionExpr(n);
            case TinyExpressionP4AST.StringExpr n -> evalStringExpr(n);
            case TinyExpressionP4AST.BooleanExpr n -> evalBooleanExpr(n);
            case TinyExpressionP4AST.ObjectExpr n -> evalObjectExpr(n);
            case TinyExpressionP4AST.NumberMatchExpr n -> evalNumberMatchExpr(n);
            case TinyExpressionP4AST.NumberCaseExpr n -> evalNumberCaseExpr(n);
            case TinyExpressionP4AST.NumberDefaultCaseExpr n -> evalNumberDefaultCaseExpr(n);
            case TinyExpressionP4AST.NumberCaseValueExpr n -> evalNumberCaseValueExpr(n);
            // ... additional cases for all @mapping types
        };
    }

    protected abstract T evalBinaryExpr(TinyExpressionP4AST.BinaryExpr node);
    protected abstract T evalComparisonExpr(TinyExpressionP4AST.ComparisonExpr node);
    protected abstract T evalIfExpr(TinyExpressionP4AST.IfExpr node);
    // ... one abstract method per @mapping class

    // DebugStrategy interface (also generated)
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

開発者はその後、**具象**サブクラスを記述する:

```java
public class P4TypedAstEvaluator extends TinyExpressionP4Evaluator<Object> {

    private final ExpressionType resultType;
    private final ExpressionType numberType;
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
    protected Object evalComparisonExpr(ComparisonExpr node) {
        Number left = evalBinaryAsNumber(node.left());
        Number right = evalBinaryAsNumber(node.right());
        String op = node.op() == null ? "" : node.op().strip();
        int compare = toBigDecimal(left).compareTo(toBigDecimal(right));
        return switch (op) {
            case "==" -> compare == 0;
            case "!=" -> compare != 0;
            case "<"  -> compare < 0;
            case "<=" -> compare <= 0;
            case ">"  -> compare > 0;
            case ">=" -> compare >= 0;
            default -> false;
        };
    }

    // ... implementations for all other evalXxx methods
}
```

この設計は3つの保証を提供する:

1. **完全性**: 文法に新しい `@mapping` 規則が追加されると、sealed interface に新しい許可されたサブタイプが加わり、生成された switch が非網羅的となり、新しい `evalXxx` メソッドが追加されるまでコンパイラが具象クラスを拒否する。
2. **再生成安全性**: 再生成されるのは抽象基底クラスのみである。すべてのドメイン固有の評価ロジックを含む具象サブクラスは決して上書きされない。
3. **デバッグ統合**: 生成された基底クラスの `DebugStrategy` フックにより、手書きクラスにコードを追加することなく DAP サーバを通じたステップデバッグが可能になる。

GGP アプローチは、同一の文法から複数の評価戦略もサポートする。tinyexpression プロジェクトは、同一の生成された基底クラスを拡張する3つの具象評価器を実装している:
- `P4TypedAstEvaluator`: AST を直接解釈し、`Object` 値を返す。
- `P4TypedJavaCodeEmitter`: AST を走査し、ランタイムコンパイルのための Java ソースコードを出力する。
- `P4DefaultJavaCodeEmitter`: デフォルト評価パターン用のテンプレートベースのエミッタである。

---

## 4. 実装

### 4.1 パーサコンビネータライブラリ（unlaxer-common）

`unlaxer-common` モジュールはパーサコンビネータライブラリを実装する436個の Java ソースファイルを含む。パーサはいくつかのカテゴリに分類される:

**コンビネータパーサ** は他のパーサを合成する:
- `Chain` / `LazyChain`: 順序合成（PEG シーケンス `e1 e2 ... en`）。
- `Choice` / `LazyChoice`: 順序付き選択（PEG `e1 / e2 / ... / en`）。
- `LazyZeroOrMore`、`LazyOneOrMore`、`LazyOptional`: 繰り返しとオプション性。
- `LazyRepeat`、`ConstructedOccurs`: 明示的なカウント制御による有界繰り返し。
- `Not`: PEG not 述語（幅ゼロの否定先読み）。
- `MatchOnly`: PEG and 述語（幅ゼロの肯定先読み）。
- `NonOrdered`: 非順序集合マッチング（すべての選択肢が任意の順序でマッチしなければならない）。

**Lazy 変種と Constructed 変種** は循環文法のサポートに対処する。再帰的文法（例: `Expression ::= ... '(' Expression ')' ...`）では、`Expression` のパーサが自身を参照する。即時構築は無限再帰を引き起こす。`Lazy` 変種は子パーサの解決を最初の構文解析まで遅延させ、循環を断ち切る。`Constructed` 変種は、子が構築時に既知である非再帰的規則に使用される。

**AST フィルタリング** はどのトークンが構文解析木に現れるかを制御する:
- `ASTNode`: パーサのトークンを AST に含めるべきことを示すマーカーインタフェース。
- `ASTNodeRecursive`: このパーサおよびその子孫からのトークンが含まれる。
- `NotASTNode` および `NotASTNode` を伴う `TagWrapper`: トークンを AST ビューから除外する。
- `Token.filteredChildren` フィールドはトークン木の AST のみのビューを提供し、`Token.children` は完全な構文解析木を保持する。

**トランザクションベースのバックトラッキング** は `ParseContext` において順序付き選択の意味論を実現する:
- `begin(Parser)`: 現在のカーソル位置を保存する（セーブポイントを作成する）。
- `commit(Parser, TokenKind)`: 解析されたトークンを受理し、カーソルを進める。
- `rollback(Parser)`: カーソルをセーブポイントに復元し、トークンを破棄する。

このトランザクションモデルは Parsec のコミット選択意味論よりも一般的である。任意のパーサがトランザクションを開始でき、ネストされたトランザクションも完全にサポートされる。

### 4.2 コードジェネレータ（unlaxer-dsl）

`unlaxer-dsl` モジュールは6つのコードジェネレータと支援インフラストラクチャを含む。

**MapperGenerator** は最も複雑な生成ロジックを扱う。主要な課題は**複数規則マッピング**（multi-rule mapping）である。複数の文法規則が同一の AST クラスにマッピングされ得る。例えば、`NumberExpression` と `NumberTerm` の両方が `BinaryExpr` にマッピングされる。マッパーは、与えられた構文解析木ノードがどの規則によって生成されたかを正しく識別し、適切なフィールドを抽出しなければならない。`allMappingRules` 機構は `@mapping` クラス名を共有するすべての規則を収集し、各マッピング規則を順番に試行するディスパッチャを生成する。

もう一つの課題は **findDirectDescendants** である。構文解析木から `@left`、`@op`、`@right` を抽出する際、マッパーは注釈付き要素にマッチする直接の子を、ネストされた部分式に降りることなく見つけなければならない。`NumberTerm` 要素を含む `NumberExpression` は、内部のファクターではなく、最上位のタームを抽出すべきである。

**EvaluatorGenerator** は GGP スケルトンを生成する。網羅的 switch ディスパッチに加えて、各ノード評価の前後に呼び出される `DebugStrategy` フックを生成する。生成された `StepCounterStrategy` 実装は評価ステップを計数し、特定のステップ数で一時停止するように構成でき、DAP サーバのステップオーバー動作を実現する。

**ParserGenerator** は優先順位と結合性を処理する。`@leftAssoc` が存在する場合、ジェネレータは基本項の後に演算子と項のペアの `LazyZeroOrMore` が続く `LazyChain` を生成する。`@rightAssoc` の場合、再帰的チェインを生成する。`@precedence(level=N)` 注釈は、複数の式型が競合する場合に選択肢の選択順序を決定するために使用される。

### 4.3 5つの実行バックエンド

tinyexpression プロジェクトは、すべて同一の文法から派生した5つの異なる実行バックエンドを実装することで、フレームワークの柔軟性を実証する:

| バックエンド | 主要クラス | 戦略 | ステップデバッグ | コード生成 |
|---------|-----------|----------|------------|----------|
| `JAVA_CODE` | `JavaCodeCalculatorV3` | レガシーパーサ → Java ソース → JIT コンパイル | なし | あり |
| `AST_EVALUATOR` | `AstEvaluatorCalculator` | レガシーパーサ → 手書き AST → 木走査評価器 | なし | なし |
| `DSL_JAVA_CODE` | `DslJavaCodeCalculator` | レガシーパーサ → DSL 生成 Java ソース | なし | あり |
| `P4_AST_EVALUATOR` | `P4AstEvaluatorCalculator` | UBNF 生成パーサ → sealed AST → `P4TypedAstEvaluator` | あり | なし |
| `P4_DSL_JAVA_CODE` | `P4DslJavaCodeCalculator` | UBNF 生成パーサ → sealed AST → Java コードエミッタ | あり | あり |

P4 バックエンド（第4行と第5行）は UBNF 生成パーサと AST を使用し、以前のバックエンドはレガシーの手書きパーサを使用する。パリティ契約により、すべてのバックエンドがサポートされる式に対して同等の結果を生成することが保証される。これは `BackendSpeedComparisonTest`、`P4BackendParityTest`、および `ThreeExecutionBackendParityTest` によって検証される。

---

## 5. 評価

### 5.1 ケーススタディ: tinyexpression

tinyexpression は金融計算に使用される本番式評価器である。構成可能な精度（float、double、int、long、BigDecimal、BigInteger）を伴う数値式、文字列式、ブール式、条件式（if/else）、パターンマッチング（match）、変数バインディング、ユーザ定義メソッド、型ヒント、埋め込み Java コードブロックをサポートする。

UBNF 文法（`tinyexpression-p4-complete.ubnf`）は**520行**に及び、完全な P4 文法を定義する。この文法から、コードジェネレータは6つの生成ファイルにわたって約**2,000行**の Java ソースを生成する。手書きの評価器ロジック（`P4TypedAstEvaluator.java`）は**542行**であり、構成可能な数値型による数値演算、変数解決、比較演算、if/else および match 評価、文字列・ブール変換を含むすべての式型をカバーする。

総投資量――文法と手書き評価器ロジック――は約**1,062行**であり、パーサ、型付き AST、マッパー、評価器、LSP サーバ、DAP サーバを備えた完全な言語実装が得られる。

### 5.2 性能ベンチマーク

`BackendSpeedComparisonTest` は、数式 `3+4+2+5-1`（リテラル算術）および `$a+$b+$c+$d-$e`（変数算術）を用いて、5,000回のウォームアップ反復後の50,000回の反復でバックエンド間の評価性能を測定する。

**セクション1: リテラル算術**

| バックエンド | 説明 | us/呼出 | ベースライン比 |
|---------|-------------|---------|--------------|
| (A) compile-hand | JVM バイトコード（JavaCodeCalculatorV3） | ~0.04 | 1.0x |
| (E2) P4-typed-reuse | sealed switch、インスタンス再利用 | ~0.10 | 2.8x |
| (E) P4-typed-eval | sealed switch、呼出ごとに新規インスタンス | ~0.33 | 8.9x |
| (B) ast-hand-cached | 手書き AST、事前解析済み | ~0.42 | 11.4x |
| (C) ast-hand-full | 手書き AST、解析+構築+評価 | ~2.50 | ~68x |
| (D) P4-reflection | P4 マッパー + リフレクションベース評価器 | ~143.53 | 3,901x |

*表2: リテラル算術の評価遅延。compile-hand バックエンドは理論的最適値（JIT コンパイルされた Java バイトコード）を表す。P4-typed-reuse はこの最適値の3倍以内である。*

最も顕著な結果は、P4-reflection（初期のリフレクションベース評価器、約143 us/呼出）と P4-typed-reuse（sealed-switch 評価器、約0.10 us/呼出）の間の**1,400倍の性能向上**である。この改善は、`java.lang.reflect.Method.invoke()` を sealed interface に対する生成済み網羅的 switch 式に置き換えることのみによって得られる。JVM の JIT コンパイラは switch ケースをインライン化し、仮想ディスパッチを排除し、レコードインスタンスに対するスカラー置換を適用できる。

P4-typed-reuse バックエンドは、インタプリタでありながら compile-hand ベースラインの**2.8倍**を達成する。これは注目に値する。sealed-switch 評価器は、木走査解釈を行っているにもかかわらず、単純な式に対して JIT コンパイルされたコードと競合的である。

**セクション2: 変数算術**

| バックエンド | 説明 | us/呼出 | ベースライン比 |
|---------|-------------|---------|--------------|
| (F) compile-hand | 変数参照を伴う JVM バイトコード | ~0.06 | 1.0x |
| (H) P4-typed-var | 変数 AST を伴う sealed switch | ~0.15 | 2.5x |
| (G) AstEvalCalc | 完全な AstEvaluatorCalculator パス | ~8.50 | ~142x |

変数式も同様の相対性能を示す。P4-typed バックエンドは JIT コンパイルされたコードに対して2.5倍のオーバーヘッドを維持する一方、レガシー AST 評価器パスはリフレクションと複数のフォールバック層により2桁遅い。

### 5.3 開発工数比較

tinyexpression の機能セットを持つ言語の開発工数を3つのアプローチで見積もる:

| アプローチ | コード行数 | 時間見積 |
|----------|---------------|---------------|
| スクラッチ（パーサ + AST + マッパー + 評価器 + LSP + DAP） | ~15,000 | 8週間 |
| ANTLR + 手書き評価器 + 手書き LSP/DAP | ~8,000 | 5週間 |
| unlaxer（文法 + evalXxx メソッド） | ~1,062 | 3日 |

*表3: 開発工数比較。コード行数には文法、生成コード（参考値のみ――開発者が保守するものではない）、手書きロジックが含まれる。*

「スクラッチ」の見積もりは README の内訳に基づく: パーサ（約2,000行）、AST 型（約1,500行）、マッパー（約1,000行）、評価器（約2,000行）、LSP サーバ（約2,500行）、DAP サーバ（約1,500行）。ANTLR の見積もりは、生成されるパーサと AST（パーサおよび AST の工数を削減）を考慮するが、手書きのマッパー、評価器、LSP、DAP を必要とする。unlaxer の見積もりは実際の tinyexpression 実装を反映する: 520行の文法と542行の評価器ロジックである。

保守対象コードの**14倍の削減**（約15,000行から約1,062行へ）が主要な実践的恩恵である。しかし、認知的負荷の削減はさらに重要といえる。開発者は文法規則と評価意味論の観点で思考し、パーサの配管、トークン木の走査、プロトコルメッセージの処理の観点で思考する必要がない。

### 5.4 LLM 支援開発

unlaxer-parser の型安全で生成ベースのアーキテクチャは、LLM 支援開発ワークフローにおいて驚くべき恩恵をもたらす。大規模言語モデル（LLM）をコーディングアシスタントとして使用する場合、フレームワークの特性が必要なトークン予算と反復回数を大幅に削減する:

**トークン効率。** フレームワークがなければ、LLM はパーサコンビネータ、AST 型、マッパーロジック、評価器コードをスクラッチから生成しなければならず、通常20,000--30,000トークンのコンテキストと生成を必要とする。unlaxer を用いれば、LLM は `evalXxx` メソッド本体のみを生成すればよく、通常2,000--3,000トークンで済む。これは**トークンコストの10倍の削減**を意味する。

**型安全性がデバッグの往復を排除する。** sealed interface の網羅性保証により、LLM が AST ノード型の処理を「忘れる」ことはあり得ない――Java コンパイラがコードを拒否するからである。実際には、これにより、LLM が生成した評価器が文法構成要素を暗黙に無視する際に発生するデバッグの往復の約95%が排除される。

**コンパイル時 TODO リストとしての sealed interface。** 文法が新しい `@mapping` 規則で拡張されると、生成された評価器基底クラスに新しい抽象メソッドが加わる。未実装メソッドを列挙するコンパイラエラーは、LLM が追加のプロンプトなしに従える正確な機械可読 TODO リストとして機能する。この「コンパイラがオーケストレータとして機能する」パターンは、LLM に必要な情報（どのメソッドを、どのパラメータ型で実装すべきか）を過不足なく提供するため、特に効果的である。

---

## 6. 議論

### 6.1 制約

**Java 専用の生成。** 現在のコードジェネレータは Java ソースファイルのみを生成する。JVM ベースの言語（Kotlin、Scala、Clojure）は生成された Java コードと相互運用できるが、非 JVM 言語のネイティブサポートは利用できない。文法記法（UBNF）は言語非依存であるが、ジェネレータパイプラインとパーサコンビネータランタイムは Java 固有である。

**PEG ベースの構文解析。** UBNF は PEG 意味論（順序付き選択）を用いるため、曖昧な文法は選択肢の順序によって決定論的に解決される。これはほとんどの DSL にとっては機能であるが、曖昧性報告を必要とする言語（例: 自然言語処理）や、順序付き選択が意外な結果をもたらす言語にとっては制約である。曖昧な文法を扱い構文解析森を生成できる GLR パーサはサポートされていない。

**エラー回復。** PEG の順序付き選択意味論は頑健なエラー回復を困難にする。選択肢が失敗すると、パーサはバックトラックして次の選択肢を試行する。「部分マッチ」を報告したり、誤った入力をスキップして構文解析を続行する機構は存在しない。`ErrorMessageParser` は障害地点の診断を提供するが、パーサは現在、ANTLR に見られるようなエラー回復戦略（トークン挿入、トークン削除、パニックモード回復）をサポートしていない。

**単一の本番ユーザ。** tinyexpression プロジェクトがフレームワークの主要な本番ユーザである。フレームワークは汎用性を目指して設計されているが、設計上の選択（UBNF 構文、生成パイプライン、GGP 評価器パターン）が広範な言語に適切であることを確認するには、多様な DSL プロジェクトにわたるより広範な検証が必要である。

**不完全な文法カバレッジ。** P4 文法は tinyexpression 言語のすべての機能をまだカバーしていない。いくつかの構成要素――外部 Java メソッド呼び出し、文字列スライシング（`$msg[0:3]`）、一部の文字列メソッド――はレガシーパーサによって処理され、以前のバックエンドにフォールバックする。共存モデル（P4 パーサとレガシーフォールバック）は機能するが、複雑さを増す。

### 6.2 将来の課題

**宣言的評価注釈。** UBNF を `@eval` 注釈で拡張し、評価戦略を文法内で直接指定することを計画している:

```ubnf
@eval(strategy=default)
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

`strategy` パラメータは `default`（生成されたディスパッチ）、`template`（外部テンプレート）、`manual`（手書きオーバーライド）から選択する。これにより、二項式評価のような一般的なパターンに対する手書き評価器コードがさらに削減される。

**型安全コード生成のための JavaCodeBuilder。** `P4TypedJavaCodeEmitter` は現在、文字列結合として Java ソースを出力している。Java ソースコードの型安全な AST 構築を提供する `JavaCodeBuilder` API は、信頼性を向上させ、生成時の定数畳み込みやデッドコード除去などの最適化を可能にする。

**Tree-sitter 統合。** UBNF と tree-sitter の `grammar.js` 間の双方向ブリッジにより、unlaxer 文法が tree-sitter の増分構文解析およびシンタックスハイライトのインフラストラクチャの恩恵を受けつつ、unlaxer の意味的生成機能を保持することが可能になる。

**増分構文解析。** 現在のパーサは変更のたびに入力全体を再解析する。IDE 統合（LSP）のためには、増分構文解析――変更された領域のみを再解析し、変更されていない領域の解析結果を再利用すること――が、大規模ドキュメントに対する応答性を大幅に改善する。

**多言語コード生成。** UBNF 文法から TypeScript、Python、Rust のソースを生成することで、フレームワークの適用範囲を JVM エコシステムの外に拡張する。文法記法と生成パイプラインは原理的には言語非依存である。主要な課題は、各ターゲット言語でパーサコンビネータランタイムと sealed interface 相当の型システムを実装することである。

---

## 7. 結論

本論文では unlaxer-parser を提示した。これは、単一の UBNF 文法仕様から6つの相互関連する成果物――パーサ、AST 型、構文解析木から AST へのマッパー、評価器スケルトン、LSP サーバ、DAP サーバ――を生成する Java 21 フレームワークである。本フレームワークは DSL 開発の根本的問題、すなわち互いに整合性を保たなければならない複数の密結合したサブシステムの構築と保守の必要性に対処する。

3つの新規貢献は、この統一的生成における特定の課題に対処する:

1. **伝播制御** は、`TokenKind` と `invertMatch` という2つの直交する次元で動作する4つの PropagationStopper クラスの階層を通じて、構文解析モードがコンビネータ木をどのように伝播するかに対する細粒度で合成的な制御を提供する。この機構は既存のパーサコンビネータフレームワークに同等のものが存在しない。

2. **メタデータ搬送構文解析木** は `ContainerParser<T>` を通じて、単一の構文解析パス中にエラーメッセージと補完候補を構文解析木に直接埋め込むことを可能にする。これにより、IDE 機能のための別個の解析パスが不要になり、構文解析と IDE 動作の間の整合性が保証される。

3. **評価器のための Generation Gap Pattern** は、Java 21 の sealed interface と網羅的 switch 式を組み合わせることで、コンパイラによる完全性保証を提供する。文法に新しい AST ノード型が追加されると、開発者が対応する評価メソッドを実装するまでコンパイラが手書きの評価器を拒否する。これにより、ある種の実行時エラーがコンパイル時エラーに変換される。

本番式評価器 tinyexpression を用いた評価は、実践的影響を実証する。フレームワークはスクラッチ実装と比較して保守対象コードの**14倍の削減**（約15,000行から約1,062行へ）を達成する。性能ベンチマークでは、sealed-switch 評価器がリフレクションベースの評価に対して**1,400倍の性能向上**を達成し、JIT コンパイルされたバイトコードの**2.8倍**以内で動作する――木走査インタプリタとしては驚異的な結果である。

LLM 支援開発がより普及するにつれて、文法から IDE への統一的生成の価値は増大すると考える。sealed interface の網羅性保証は「コンパイル時 TODO リスト」として機能し、LLM がこれに正確に従うことで、トークンコストを桁違いに削減し、デバッグの往復の大部分を排除する。

本フレームワークは MIT ライセンスのオープンソースソフトウェアとして提供されており、Maven Central に `org.unlaxer:unlaxer-common` および `org.unlaxer:unlaxer-dsl` として公開されている。

---

## 参考文献

[1] Brunel, M., Clem, M., Hlywa, T., Creager, P., and Gonzalez, A. 2023. tree-sitter: An incremental parsing system for programming tools. In *Proceedings of the ACM SIGPLAN International Conference on Software Language Engineering (SLE '23)*.

[2] Ford, B. 2004. Parsing Expression Grammars: A recognition-based syntactic foundation. In *Proceedings of the 31st ACM SIGPLAN-SIGACT Symposium on Principles of Programming Languages (POPL '04)*, pp. 111--122. ACM.

[3] Gamma, E., Helm, R., Johnson, R., and Vlissides, J. 1994. *Design Patterns: Elements of Reusable Object-Oriented Software*. Addison-Wesley.

[4] Ierusalimschy, R. 2009. A text pattern-matching tool based on Parsing Expression Grammars. *Software: Practice and Experience* 39, 3, pp. 221--258.

[5] Johnson, S. C. 1975. Yacc: Yet Another Compiler-Compiler. *AT&T Bell Laboratories Technical Report*.

[6] Leijen, D. and Meijer, E. 2001. Parsec: Direct Style Monadic Parser Combinators For The Real World. *Technical Report UU-CS-2001-35*, Department of Computer Science, Universiteit Utrecht.

[7] Microsoft. 2016a. Language Server Protocol Specification. https://microsoft.github.io/language-server-protocol/

[8] Microsoft. 2016b. Debug Adapter Protocol Specification. https://microsoft.github.io/debug-adapter-protocol/

[9] Parr, T. and Fisher, K. 2011. LL(*): the foundation of the ANTLR parser generator. In *Proceedings of the 32nd ACM SIGPLAN Conference on Programming Language Design and Implementation (PLDI '11)*, pp. 425--436. ACM.

[10] Redziejowski, R. R. 2007. Parsing Expression Grammars: A Recognition-Based Syntactic Foundation. *Fundamenta Informaticae* 85, 1-4, pp. 413--431.

[11] Vlissides, J. 1996. Generation Gap. In *Pattern Languages of Program Design 3*, Addison-Wesley, pp. 85--101.

[12] Erdweg, S., Storm, T., Volter, M., Boersma, M., Bosman, R., Cook, W. R., Gerber, A., Hulshout, A., Kelly, S., Loh, A., Konat, G. D. P., Molina, P. J., Palatnik, M., Poetzsch-Heffter, A., Schindler, K., Schindler, T., Solmi, R., Vergu, V., Visser, E., van der Vlist, K., Wachsmuth, G. H., and van der Woning, J. 2013. The State of the Art in Language Workbenches. In *Software Language Engineering (SLE '13)*, pp. 197--217.

[13] Volter, M., Stahl, T., Bettin, J., Haase, A., and Helsen, S. 2006. *Model-Driven Software Development: Technology, Engineering, Management*. John Wiley & Sons.

[14] Kats, L. C. L. and Visser, E. 2010. The Spoofax Language Workbench: Rules for Declarative Specification of Languages and IDEs. In *Proceedings of the ACM International Conference on Object-Oriented Programming, Systems, Languages, and Applications (OOPSLA '10)*, pp. 444--463. ACM.

[15] Parr, T. 2013. *The Definitive ANTLR 4 Reference*. Pragmatic Bookshelf.

[16] Hutton, G. and Meijer, E. 1998. Monadic Parsing in Haskell. *Journal of Functional Programming* 8, 4, pp. 437--444.

[17] Might, M., Darais, D., and Spiewak, D. 2011. Parsing with Derivatives: A Functional Pearl. In *Proceedings of the 16th ACM SIGPLAN International Conference on Functional Programming (ICFP '11)*, pp. 189--195. ACM.

[18] Swierstra, S. D. 2009. Combinator Parsing: A Short Tutorial. In *Language Engineering and Rigorous Software Development*, Lecture Notes in Computer Science 5520, pp. 252--300. Springer.

---

## 付録 A: 完全な TinyCalc 例

以下の最小限の UBNF 文法と評価器が、完全な生成パイプラインを実証する:

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

**生成された sealed AST（TinyCalcAST.java）:**

```java
public sealed interface TinyCalcAST permits TinyCalcAST.BinaryExpr {
    record BinaryExpr(BinaryExpr left, List<String> op, List<BinaryExpr> right)
        implements TinyCalcAST {}
}
```

**手書きの評価器（TinyCalcEvaluatorImpl.java）:**

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

この35行の文法と17行の評価器から、パーサ、AST、マッパー、評価器、LSP サーバ、DAP サーバを備えた完全な電卓が得られる。

---

## 付録 B: PropagationStopper 決定行列

| Stopper | 子への TokenKind | 子への invertMatch | ユースケース |
|---------|-------------------|---------------------|----------|
| *（なし）* | 親の値 | 親の値 | デフォルト伝播 |
| `AllPropagationStopper` | `consumed` | `false` | 部分式のすべての構文解析モードをリセット |
| `DoConsumePropagationStopper` | `consumed` | 親の値 | matchOnly コンテキスト内で消費を強制 |
| `InvertMatchPropagationStopper` | 親の値 | `false` | NOT 意味論がサブパーサに伝播するのを防止 |
| `NotPropagatableSource` | 親の値 | `!parent` | 反転フラグの反転による論理 NOT の実装 |

---

*ACM SIGPLAN International Conference on Software Language Engineering (SLE), 2026 に投稿。*

---

## ナビゲーション

[← インデックスに戻る](../INDEX.md)

| 論文 (JA) | 査読 |
|-----------|------|
| **v1 — 現在** | [v1 査読](./review-dialogue-v1.ja.md) |
| [v2 論文 →](../v2/from-grammar-to-ide.ja.md) | [v2 査読](../v2/review-dialogue-v2.ja.md) |
