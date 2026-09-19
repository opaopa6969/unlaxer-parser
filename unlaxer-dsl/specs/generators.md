# ジェネレータ仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは8つのコードジェネレータの入力・出力・生成 API 面・命名規則を定義する。

このドキュメントが **扱わない** 範囲:
- CLI オプション（→ [cli.md](cli.md)）
- バリデーション（→ [validation.md](validation.md)）

## 関連ドキュメント

- [overview.md](overview.md) — ジェネレータ一覧
- [annotations.md](annotations.md) — アノテーションがジェネレータに与える影響
- [cli.md](cli.md) — `--generators` オプション

---

## 共通インタフェース

**インタフェース**: `org.unlaxer.dsl.codegen.CodeGenerator`

すべてのジェネレータは `CodeGenerator` インタフェースを実装する。

```java
public interface CodeGenerator {
    GeneratedSource generate(GrammarDecl grammar);
}
```

### 入力

- `GrammarDecl`: バリデーション済みの UBNF 文法 AST

### 出力

- `GeneratedSource`: 生成された Java ソースコード（クラス名、パッケージ名、ソーステキスト）

### 命名規則

生成されるクラス名は以下のパターンに従う（MUST）:

```
{GrammarName}{GeneratorSuffix}.java
```

---

## ParserGenerator

**クラス**: `org.unlaxer.dsl.codegen.ParserGenerator`

### 出力

`{GrammarName}Parsers.java`

### 生成内容

- 各ルールに対応するパーサーインナークラス
- unlaxer-common の `LazyChain`, `LazyChoice`, `ZeroOrMore` 等を使用
- `@whitespace` 設定に基づくスペースデリミタ自動挿入（`WhiteSpaceDelimitedLazyChain`）
- `@precedence` / `@leftAssoc` / `@rightAssoc` に基づく演算子メタデータ API
- `@scopeTree` に基づくスコープツリーメタデータ API
- `@rightAssoc` ルールの右再帰 Choice 構造

### 補助パーサーの文法位置

group・optional・repeat・separated の補助クラス名は、分析時にルール内の文法位置へ割り当てる。
生成時は同じ位置の名前を参照し、生成順による再採番は行わない。
これにより、入れ子の補助パーサーが後続の兄弟要素の参照をずらさない。
内容が同一の要素も別の位置として扱い、capture の所属を保持する。
既存のクラス命名規則と宣言順は維持する。修正前に生成した入れ子を含むパーサーは再生成が必要。

### 演算子メタデータ API（@precedence 使用時）

- `PRECEDENCE_{RULE_NAME}` 定数
- `getPrecedence(String)`, `getAssociativity(String)`
- `getOperatorSpecs()`, `getOperatorSpec(String)`, `isOperatorRule(String)`
- `getNextHigherPrecedence(String)`
- `getOperatorParser(String)`, `getLowestPrecedenceOperator()`, `getLowestPrecedenceParser()`
- `getPrecedenceLevels()`, `getOperatorsAtPrecedence(int)`, `getOperatorParsersAtPrecedence(int)`

---

## ASTGenerator

**クラス**: `org.unlaxer.dsl.codegen.ASTGenerator`

### 出力

`{GrammarName}AST.java`

### 生成内容

- sealed interface として AST のルートインタフェース
- `@mapping` 付きルールごとに record クラスを内部型として生成
- record のフィールドは `@mapping` の `params` に対応
- 繰り返しキャプチャ（`{ ... @name }` で同じ名前が複数回）は `List<T>` 型
- 省略可能キャプチャは `Optional<T>` 型

### 数値 token の型境界

`NumberParser` / `DigitParser` を直接 capture する既存の Java API は `int`。
optional と repeat の要素は primitive を boxing し、`Optional<Integer>` /
`List<Integer>` を生成する。必須数値 capture が見つからない場合、mapper は
`IllegalArgumentException` を投げる（`0` で補完しない）。

`NumberParser` の構文上の受理範囲は AST の `int` より広い。mapper の変換は
`Integer.parseInt` の契約に従い、符号付き十進整数と先頭ゼロは許すが、小数表記・
指数表記（`1.0` / `1e2` を含む）・32-bit signed integer の範囲外は
`NumberFormatException` とする。丸め・切捨て・overflow wrap はしない。
したがって `diagnose()` が空（構文成功）でも、AST mapping が失敗し得る。
数値全文を保持して利用者が変換したい場合は、非 mapping のルール
`Digits ::= NUMBER ;` を経由して capture すると `String` 型になる。

Rust backend の direct number capture は現段階で `String` であり、Java の
`int` と型同値ではない。`numeric-capture/conformance.json` を両 backend の
実生成・実コンパイルに通し、Java の数値変換結果/失敗と Rust の字句保持を
別々の oracle で検証する（`NumericCaptureRuntimeTest`、Rust は
`-DrustConformance=true`）。この差は数値意味論の同等性として扱わない。

---

## MapperGenerator

**クラス**: `org.unlaxer.dsl.codegen.MapperGenerator`

### 出力

`{GrammarName}Mapper.java`

### 生成内容

- Token 木から AST（record）へのマッピングメソッド群
- 各 `@mapping` ルールに対応する `mapXxx(Token)` メソッド
- `@rightAssoc` の右再帰 CST から各ノードへの位置 binding 付きマッピング

### 左結合の AST と元 CST（#138）

`@leftAssoc` は `left` と出現順の `op` / `right` リストを生成し、二分木へ変換しない。
同じ AST class を複数の優先順位ルールで共有する場合も、各ルールの反復だけを集める。
元 CST では反復 helper は `ZeroOrMore` の直下にあるため、その wrapper だけを透過する。
括弧内などの operand rule には探索を広げず、内側の演算子を外側のリストへ混入させない。
宣言rootのidentityと必要なcaptureを保持している旧来の縮約済み token は
`mapParsedTokenWithSourceMap` へ渡せる。縮約でrootが除去された場合の制約は下記を参照。

typed leaf は実際の mapped class として保持する。source span は各 mapped rule の
消費範囲であり、`javaStyle` delimiter が消費した末尾空白・コメントを含むことがある。
優先順位の構文構造は rule 間の参照で決まり、`@precedence` 数値はその参照関係の検証と
メタデータ API に使う（数値を書くだけで parser の選択順を並べ替えない）。

### 右結合の再帰構造（#139）

canonical な `Base @left { Op @op Self @right }` は parser が
`Base Op Self | Base` の右再帰に変換する。`@right` も通常の位置 binding を保ち、
mapper は現在の rule 呼び出しに属する `left` / `op` / `right` だけを取り出す。
各 AST の `op` / `right` リストは基底なら空、再帰なら要素が1つになり、
`2^3^2` は `2^(3^2)` に相当する構造になる。再 fold は行わない。

公開 record の field 型は維持し、例えば `String left` と `List<Pow> right` のように
左と右が異なる型でも生成コードをコンパイルできる。内側ノードも各再帰 rule の
source span を持つ。同じ mapping class の追加 rule でも自身の結合指定を使用する。
旧 package-private `foldRightAssoc{ClassName}` は生成しなくなったため、Parser と Mapper を
一緒に再生成する。`parse` / `parseWithSourceMap` は root transaction の token を使用する。

共有 mapping の異種 operand 型が代表 rule の宣言順により矛盾するケースは #145 で追跡する。

### 汎用 token mapping の root 境界（#146）

`mapParsedToken` / `mapParsedTokenWithSourceMap` は再parseもCST縮約も行わない。
文法の `@root` 自体が `@mapping` を持つ場合、渡すtokenはその宣言rootのparser classを
持つ必要がある。欠けていれば `IllegalArgumentException: Mapped root token is missing for ...`
を投げる。子孫の同名parserやmutableな `Token.parent`、source spanからrootを推測しない。
preferred AST型を指定しても、この入力チェックは省略しない。

`ChoiceInterface.parse` は選ばれた子の `Parsed` を返すので、`parsed.getRootToken(false)`
でさえ外側のmapped rootを持たないことがある。`getRootToken(true)`も復元はしない。
その場合はcontextを閉じる前に、次のようにcommitされたrootを確保する。

```java
Parser parser = ExampleParsers.getRootParser();
Token root;
try (ParseContext context = new ParseContext(StringSource.createRootSource(source))) {
    Parsed parsed = parser.parse(context);
    if (!parsed.isSucceeded() || !context.allConsumed()) {
        throw new IllegalArgumentException("Parse failed");
    }
    root = context.getCurrent().getTokens().stream()
        .filter(token -> token.parser == parser).findFirst().orElseThrow();
}
var mapped = ExampleMapper.mapParsedTokenWithSourceMap(root);
```

普通の文字列入力には `parse` / `parseWithSourceMap` がこのroot確保を行うので推奨する。
mapped rootの代わりに任意の子孫tokenや外部wrapperを渡して自動探索させる旧動作は、
外側の演算が黙って失われるため拒否する。`@root` がunmappedの文法では、従来の
子孫mapped node選択とpreferred型選択を維持する。

zero-width captureを保つには元CSTを使用する。縮約で削除済みのcapture情報を汎用APIが
復元できるとは限らない。root identityの検査は生/縮約済みtoken共通であり、
この検査のために縮約を再有効化することはない。

### Capture の位置 binding と再生成（#116）

通常の mapping は parser class の全体探索や「同じ class の何番目か」ではなく、
文法中の capture 位置に付けた binding を使う。省略された optional は空のままで、
repeat の要素や構造上の literal を別 field へ流用しない。量指定子の外側に付けた
capture は各値へ binding し、separated の separator は含めない。同名 capture の
複数箇所は入力中の出現順に取り出し、別の mapped rule の内部へは探索しない。

文字列だけで構成される group/choice と複数要素の量指定子 body は、
先頭の token ではなく binding 先の source 全体を値にする（#132）。
例えば `T=IdentifierParser` の `(T ':' T) @value` は `a:b`、
`(T | '!') @value` は一致した枝に応じて識別子または `!` を返す。
optional/list でも各 binding の範囲を使い、外側の未 capture 要素を拾わない。
既存の文字列変換（外側空白の strip と単引用符だけの除去）を適用し、
内部の区切り文字やコメントは消さない。group の `Object` 型等の公開 API は維持する。
数値 token の直接 capture は引き続き `int`/boxed 型へ厳密に変換するが、
`(N ':' T)` のような複合 capture を先頭の数値へ縮めたり数値に変換したりしない。
mapped AST/enum の参照を含む compound はこの文字列化の対象外で、従来の型付き dispatch を維持する。

ParserGenerator は位置専用の `__CaptureSite` と `__CaptureBinding` を生成する。
`Parser.get` の共有 parser に capture metadata を書き込むことはない。CST には
capture wrapper が増えるため、手書きの木の走査は追加ノードを考慮する必要がある。
生成 scope/backref listener は新 wrapper だけを透過し、既存の rule 境界は維持する。
`parseWithSourceMap` の AST/span API は変えない。

**Parser と Mapper は同じ generator revision で一緒に再生成する。** 新 Mapper と
旧 Parser の混在は `__CaptureBinding` が存在せずコンパイルできない。旧生成物の
ペアはそのまま利用できるが、この修正は再生成しない限り反映されない。
golden fixture と実 Java コンパイル/実行、および Rust との共有 cardinality corpus
でこの契約を検査する。

入れ子量指定子の内側の capture（`[ { Item @values } ]`）は扱うが、
外側 capture が作る入れ子 container 型（`[ { Item } ] @values` の
`Optional<List<Item>>` など）の mapper 再構築は未完了。型が生成できることと
その構造へ正しく mapping できることを同一視しない。

---

## EvaluatorGenerator

**クラス**: `org.unlaxer.dsl.codegen.EvaluatorGenerator`

### 出力

`{GrammarName}Evaluator.java`

### 生成内容

- AST を評価するスケルトンクラス
- 各 AST ノード型に対応する `evaluate(XxxNode)` メソッドスケルトン

---

## LSPGenerator

**クラス**: `org.unlaxer.dsl.codegen.LSPGenerator`

### 出力

`{GrammarName}LSP.java`

### 生成内容

- Language Server Protocol サーバー実装
- 文法固有の診断、ホバー、補完を提供

---

## LSPLauncherGenerator

**クラス**: `org.unlaxer.dsl.codegen.LSPLauncherGenerator`

### 出力

`{GrammarName}LSPLauncher.java`

### 生成内容

- LSP サーバーの起動クラス（`main` メソッド）

---

## DAPGenerator

**クラス**: `org.unlaxer.dsl.codegen.DAPGenerator`

### 出力

`{GrammarName}DAP.java`

### 生成内容

- Debug Adapter Protocol サーバー実装
- パーストークンストリームに基づくブレークポイント・ステッピング

---

## DAPLauncherGenerator

**クラス**: `org.unlaxer.dsl.codegen.DAPLauncherGenerator`

### 出力

`{GrammarName}DAPLauncher.java`

### 生成内容

- DAP サーバーの起動クラス（`main` メソッド）

---

## パッケージ名の決定

生成コードのパッケージ名は、文法のグローバル設定 `@package` から決定される:

```
@package: org.example.generated
```

`@package` が未指定の場合のデフォルト動作は実装依存。

---

## 現在の制限事項

- Evaluator ジェネレータはスケルトンのみ生成し、評価ロジックはユーザーが実装する必要がある
- LSP / DAP ジェネレータの生成コードは限定的な機能セットを提供する

## 変更履歴

- 2026-03-01: 初版作成
