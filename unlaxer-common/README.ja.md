# Unlaxer

[English](./README.md) | [日本語](./README.ja.md)

[RELAX NG](http://relaxng.org/) に着想を得た、Java 向けのシンプルかつ強力なパーサーコンビネータライブラリです。

英語版 README は [`README.md`](./README.md) を参照してください。

[![Maven Central](https://img.shields.io/maven-central/v/org.unlaxer/unlaxer-common.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22org.unlaxer%22%20AND%20a:%22unlaxer-common%22)

## 特徴

- **読みやすく書きやすい**: 説明的な名前を持つコードファーストのアプローチ（例: `*` ではなく `ZeroOrMore`）
- **IDE フレンドリー**: Java をフル活用でき、優れたデバッグ機能を提供
- **強力なコンビネータ**: RELAX NG の語彙に由来する Optional, Choice, Interleave, ZeroOrMore, OneOrMore, Chain などをサポート
- **高度なパース機能**: 無限先読み、バックトラック、後方参照をサポート
- **柔軟なアーキテクチャ**: コンテキストスコープツリー上での functional な parser/token 参照
- **依存ゼロ**: サードパーティライブラリ不要
- **豊富なデバッグ**: parse、token、transaction ログを包括的に提供

## クイックスタート

### インストール

`build.gradle` に追加:

```groovy
dependencies {
    implementation 'org.unlaxer:unlaxer-common:VERSION'
}
```

または `pom.xml`:

```xml
<dependency>
    <groupId>org.unlaxer</groupId>
    <artifactId>unlaxer-common</artifactId>
    <version>VERSION</version>
</dependency>
```

### 基本例

```java
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.posix.*;
import org.unlaxer.context.*;

// 文法を定義: [0-9]+([-+*/][0-9]+)*
Parser parser = new Chain(
    new OneOrMore(DigitParser.class),
    new ZeroOrMore(
        new Chain(
            new Choice(
                PlusParser.class,
                MinusParser.class,
                MultipleParser.class,
                DivisionParser.class
            ),
            new OneOrMore(DigitParser.class)
        )
    )
);

// 入力をパース
ParseContext context = new ParseContext(
    StringSource.createRootSource("1+2+3")
);
Parsed result = parser.parse(context);

// 結果を確認
System.out.println("Status: " + result.status); // succeeded
System.out.println("Token: " + result.getRootToken());
```

## ユーザーガイド

### パーサーコンビネータを理解する

パーサーコンビネータは、小さなパース関数を組み合わせて複雑なパーサーを構築する手法です。各コンビネータは次のような「パーサービルダー」です:

1. 入力としてシンプルなパーサーを受け取る
2. 特定のルールでそれらを組み合わせる
3. より複雑な新しいパーサーを返す

この合成可能性こそが、パーサーコンビネータの最大の強みです。

### コアコンビネータ

#### Chain - 逐次マッチ

`Chain` はすべての子パーサーを順番にマッチさせます（正規表現の連接に相当）。

```java
// マッチ対象: "if", whitespace, identifier
Parser ifStatement = new Chain(
    IfKeywordParser.class,
    WhiteSpaceParser.class,
    IdentifierParser.class
);
```

**文法表記**: `A B C` または `A , B , C`

#### Choice - 選択マッチ

`Choice` は成功するまで各子パーサーを試行します（正規表現の `|` に相当）。

```java
// マッチ対象: number OR string OR boolean
Parser literal = new Choice(
    NumberParser.class,
    StringParser.class,
    BooleanParser.class
);
```

**文法表記**: `A | B | C`

#### ZeroOrMore - 繰り返し (0+)

`ZeroOrMore` は子パーサーを 0 回以上マッチさせます（正規表現の `*` に相当）。

```java
// マッチ対象: "", "a", "aa", "aaa", ...
Parser manyAs = new ZeroOrMore(new MappedSingleCharacterParser('a'));
```

**文法表記**: `A*`

#### OneOrMore - 繰り返し (1+)

`OneOrMore` は子パーサーを 1 回以上マッチさせます（正規表現の `+` に相当）。

```java
// マッチ対象: "1", "12", "123", ...
Parser digits = new OneOrMore(DigitParser.class);
```

**文法表記**: `A+`

#### Optional - 0 回または 1 回

`Optional` は子パーサーを 0 回または 1 回マッチさせます（正規表現の `?` に相当）。

```java
// マッチ対象: "42" or "-42"
Parser signedNumber = new Chain(
    new Optional(MinusParser.class),
    new OneOrMore(DigitParser.class)
);
```

**文法表記**: `A?`

#### NonOrdered - 順不同マッチ

`NonOrdered` はすべての子パーサーを任意順でマッチさせます（RELAX NG の `<interleave>` に相当）。

```java
// マッチ対象: "abc", "acb", "bac", "bca", "cab", "cba"
Parser anyOrder = new NonOrdered(
    new MappedSingleCharacterParser('a'),
    new MappedSingleCharacterParser('b'),
    new MappedSingleCharacterParser('c')
);
```

### 終端パーサー

終端パーサーは入力から実際の文字をマッチさせます:

#### 文字クラスパーサー

```java
// POSIX 文字クラス（org.unlaxer.parser.posix パッケージ）
new DigitParser()              // [0-9]
new AlphabetParser()           // [a-zA-Z]
new AlphabetNumericParser()    // [a-zA-Z0-9]
new SpaceParser()              // whitespace
new AlphabetNumericUnderScoreParser()  // [a-zA-Z0-9_]

// ASCII 記号
new PlusParser()         // +
new MinusParser()        // -
new MultipleParser()     // *
new DivisionParser()     // /
```

#### カスタム文字パーサー

```java
// 単一文字
new MappedSingleCharacterParser('x')

// 文字範囲
new MappedSingleCharacterParser(new Range('a', 'z'))

// 複数文字
new MappedSingleCharacterParser("abc")

// 括弧を除外した記号
PunctuationParser p = new PunctuationParser();
MappedSingleCharacterParser withoutParens = p.newWithout("()");
```

### 高度な機能

#### Lazy 評価による再帰文法

再帰構造では、パーサー構築時の無限ループを避けるために lazy 評価を使います。Unlaxer はこの目的のために `LazyChain`, `LazyChoice`, `LazyOneOrMore`, `LazyZeroOrMore` などの lazy コンビネータを提供しています。

**なぜ Lazy 評価が必要か?**

以下のような再帰文法がある場合:
```
expr = term | '(' expr ')'
```

次のようには書けません:
```java
// NG - 構築時に無限ループが発生！
Parser expr = new Choice(
    term,
    new Chain(lparen, expr, rparen)  // expr がまだ存在しない！
);
```

**解決策 1: LazyChain と LazyChoice を使う**

推奨アプローチは lazy parser クラスを継承することです:

```java
// 再帰式パーサーを定義
public class ExprParser extends LazyChoice {
    @Override
    public Parsers getLazyParsers() {
        // このメソッドは遅延呼び出しされるため、無限再帰を避けられる
        return new Parsers(
            Parser.get(NumberParser.class),
            new Chain(
                Parser.get(LParenParser.class),
                Parser.get(ExprParser.class),  // 再帰参照！
                Parser.get(RParenParser.class)
            )
        );
    }

    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();  // AST に含める
    }
}

// 使用例
Parser expr = Parser.get(ExprParser.class);
```

**解決策 2: Supplier を使う（レガシーアプローチ）**

```java
// 括弧付き式の文法
Supplier<Parser> exprSupplier = () -> {
    Parser term = /* ... */;
    return new Choice(
        term,
        new Chain(
            new MappedSingleCharacterParser('('),
            Parser.get(exprSupplier),  // supplier 経由の再帰参照
            new MappedSingleCharacterParser(')')
        )
    );
};

Parser expr = Parser.get(exprSupplier);
```

**完全な再帰例**

```java
// 文法:
// expr   = term (('+'|'-') term)*
// term   = factor (('*'|'/') factor)*
// factor = number | '(' expr ')'

public class FactorParser extends LazyChoice {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(NumberParser.class),
            new Chain(
                Parser.get(LParenParser.class),
                Parser.get(ExprParser.class),  // 再帰！
                Parser.get(RParenParser.class)
            )
        );
    }

    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();
    }
}

public class TermParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(FactorParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(
                        Parser.get(MultipleParser.class),
                        Parser.get(DivisionParser.class)
                    ),
                    Parser.get(FactorParser.class)
                )
            )
        );
    }

    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();
    }
}

public class ExprParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(TermParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(
                        Parser.get(PlusParser.class),
                        Parser.get(MinusParser.class)
                    ),
                    Parser.get(TermParser.class)
                )
            )
        );
    }

    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();
    }
}

// 使用例
ParseContext context = new ParseContext(
    StringSource.createRootSource("3 + 4 * (2 - 1)")
);
Parser expr = Parser.get(ExprParser.class);
Parsed result = expr.parse(context);
```

**Lazy Parser の要点**:
- `LazyChain`, `LazyChoice`, `LazyOneOrMore`, `LazyZeroOrMore` などを継承する
- `getLazyParsers()` を実装して子パーサーを返す
- 子パーサーは最初に必要になった時点で構築される
- 相互再帰・自己再帰の両方を実現できる
- シングルトンインスタンス取得には `Parser.get(YourLazyParser.class)` を使う

#### 名前付きパーサー

名前付きパーサーにより、文法の特定部分を識別・参照しやすくなります:

```java
Parser number = new OneOrMore(DigitParser.class);
number.setName(new Name("Number"));

// トークンツリー上で識別しやすくなり、デバッグしやすい
```

#### ParseContext オプション

```java
// メタトークン生成を有効化（コンビネータノードをトークンツリーに含める）
ParseContext context = new ParseContext(
    source,
    CreateMetaTokenSpecifier.createMetaOn
);

// メタトークン生成を無効化（終端パーサーのみをトークンツリーに含める）
ParseContext context = new ParseContext(
    source,
    CreateMetaTokenSpecifier.createMetaOff
);
```

### パース結果の扱い

#### Parse Status

```java
Parsed result = parser.parse(context);

// ステータス確認
if (result.status == Parsed.Status.succeeded) {
    // 成功！
}
else if (result.status == Parsed.Status.failed) {
    // パース失敗
}
else if (result.status == Parsed.Status.stopped) {
    // パース停止（例: エラーメッセージパーサーがマッチ）
}
```

#### トークンツリー

結果にはトークンとして表現された構文木が含まれます:

```java
Token root = result.getRootToken();

// Token のプロパティ
String text = root.getConsumedString();  // マッチ文字列
int start = root.getRange().start;       // 開始位置
int end = root.getRange().end;           // 終了位置（exclusive）
Parser parser = root.getParser();        // この token を生成した parser

// 子要素
List<Token> children = root.getChildren();
```

#### 整形表示

```java
// トークンツリーを表示
System.out.println(TokenPrinter.get(result.getRootToken()));

// 出力例:
// '1+2+3' : org.unlaxer.combinator.Chain
//  '1' : org.unlaxer.combinator.OneOrMore
//   '1' : org.unlaxer.posix.DigitParser
//  '+2+3' : org.unlaxer.combinator.ZeroOrMore
//   '+2' : org.unlaxer.combinator.Chain
//    '+' : org.unlaxer.ascii.PlusParser
//    '2' : org.unlaxer.combinator.OneOrMore
//     '2' : org.unlaxer.posix.DigitParser
```

### 完全例: 四則演算式パーサー

```java
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.posix.*;
import org.unlaxer.parser.ascii.*;
import org.unlaxer.context.*;
import java.util.function.Supplier;

public class Calculator {

    // 文法:
    // expr   = term (('+' | '-') term)*
    // term   = factor (('*' | '/') factor)*
    // factor = number | '(' expr ')'

    public static Parser createParser() {
        Supplier<Parser> exprSupplier = () -> {
            Parser factor = new Choice(
                new OneOrMore(DigitParser.class),
                new Chain(
                    new MappedSingleCharacterParser('('),
                    Parser.get(exprSupplier),
                    new MappedSingleCharacterParser(')')
                )
            );

            Parser term = new Chain(
                factor,
                new ZeroOrMore(
                    new Chain(
                        new Choice(
                            MultipleParser.class,
                            DivisionParser.class
                        ),
                        factor
                    )
                )
            );

            Parser expr = new Chain(
                term,
                new ZeroOrMore(
                    new Chain(
                        new Choice(
                            PlusParser.class,
                            MinusParser.class
                        ),
                        term
                    )
                )
            );

            return expr;
        };

        return Parser.get(exprSupplier);
    }

    public static void main(String[] args) {
        Parser parser = createParser();

        String input = "1+2*(3-4)";
        ParseContext context = new ParseContext(
            StringSource.createRootSource(input)
        );

        Parsed result = parser.parse(context);

        if (result.isSucceeded()) {
            System.out.println("Parse succeeded!");
            System.out.println(TokenPrinter.get(result.getRootToken()));
        } else {
            System.out.println("Parse failed: " + result.getMessage());
        }

        context.close();
    }
}
```

## Internal Architecture

内部アーキテクチャの理解は、カスタムパーサーコンビネータの作成やライブラリ拡張に不可欠です。

### コアコンセプト

#### 1. Source と Source 階層

`Source` は Unlaxer の位置追跡システムの基盤です。Unicode を正確に扱いながら入力テキストを表現し、階層関係をサポートします。

**Source の種類**

```java
public enum SourceKind {
    root,       // 元の入力ソース
    subSource,  // 親ソースのビュー（接続を維持）
    detached,   // 独立ソース（親との接続なし）
    attached    // 特殊ケース（ほぼ未使用）
}
```

**Source の作成**

```java
// Root source - 元の入力
Source root = StringSource.createRootSource("Hello World");

// SubSource - 親へのビュー（位置追跡を維持）
Source sub = root.subSource(
    new CodePointIndex(0),    // 開始位置（inclusive）
    new CodePointIndex(5)     // 終了位置（exclusive）
);
// sub.sourceAsString() = "Hello"
// sub.offsetFromRoot() = 0
// sub.parent() = Optional.of(root)

// 入れ子の subSource - オフセットは合成される
Source nested = sub.subSource(
    new CodePointIndex(1),    // sub 内の位置 1
    new CodePointIndex(4)     // sub 内の位置 4
);
// nested.sourceAsString() = "ell"
// nested.offsetFromParent() = 1 (sub 基準)
// nested.offsetFromRoot() = 1 (root 基準)
// nested.parent() = Optional.of(sub)
```

**SubSource と Detached の違い**

SubSource は親との接続を維持します:
```java
Source root = StringSource.createRootSource("ABCDEFGH");

// SubSource - 親参照とオフセットを保持
Source sub = root.subSource(new CodePointIndex(2), new CodePointIndex(6));
// sub = "CDEF"
// sub.parent().isPresent() = true
// sub.offsetFromRoot() = 2
// root まで位置追跡可能

// Detached - 独立した root になる
Source detached = sub.reRoot();
// detached = "CDEF"
// detached.parent().isEmpty() = true
// detached.offsetFromRoot() = 0
// 元の root との接続を失う
// 新しい座標系が必要なときに有用
```

**なぜ SubSource が重要か**

SubSource は以下で重要です:

1. **位置追跡**: エラーメッセージで元入力を参照できる
```java
Source root = StringSource.createRootSource("var x = 10;");
Source statement = root.subSource(new CodePointIndex(0), new CodePointIndex(11));

// statement をパース
Parsed result = parser.parse(new ParseContext(statement));

if (result.isFailed()) {
    // statement 内の位置
    int localPos = cursor.positionInSub().value();

    // 元ファイル内の位置
    int globalPos = cursor.positionInRoot().value();

    System.out.printf(
        "Error at position %d (global: %d) in: %s%n",
        localPos, globalPos, root.sourceAsString()
    );
}
```

2. **インクリメンタルパース**: 文脈を失わず部分パースできる
```java
Source file = StringSource.createRootSource(entireFileContent);

// 各関数を個別にパースしつつ、ファイル座標を維持
List<FunctionToken> functions = new ArrayList<>();
for (FunctionLocation loc : functionLocations) {
    Source funcSource = file.subSource(loc.start, loc.end);

    Parsed result = functionParser.parse(new ParseContext(funcSource));

    // Token 位置は元ファイルを参照
    functions.add(result.getRootToken());
}
```

3. **多段パース**: 位置を維持しながら再帰的にパース
```java
// 1st pass: 文字列リテラルを抽出
Source root = StringSource.createRootSource(code);
List<Source> stringLiterals = extractStringLiterals(root);

// 2nd pass: 各リテラルを別ルールでパース
for (Source literal : stringLiterals) {
    // literal は root 上の位置情報を維持
    Parsed result = stringContentParser.parse(new ParseContext(literal));

    // 元ファイル位置でエラー報告
    if (result.isFailed()) {
        int line = literal.cursorRange()
            .startIndexInclusive.lineNumber().value;
        System.err.printf("Error at line %d in original file%n", line);
    }
}
```

**Source 操作**

```java
Source source = StringSource.createRootSource("Hello World");

// ビュー作成
Source peek = source.peek(
    new CodePointIndex(0),
    new CodePointLength(5)
);  // "Hello"（一時ビュー）

Source sub = source.subSource(
    new CodePointIndex(6),
    new CodePointIndex(11)
);  // "World"（親接続あり）

// 変換（新しい detached source を作る）
Source upper = source.toUpperCaseAsStringInterface();  // "HELLO WORLD"
// upper.parent().isEmpty() = true（変換で親リンクが切れる）

// 変換して re-root
Source newRoot = source.reRoot(s -> s.replace("World", "Universe"));
// newRoot = "Hello Universe"
// newRoot.isRoot() = true
// newRoot.offsetFromRoot() = 0
```

**Source 階層の例**

```java
// Root: "The quick brown fox jumps"
Source root = StringSource.createRootSource("The quick brown fox jumps");

// Level 1: "quick brown fox"
Source level1 = root.subSource(new CodePointIndex(4), new CodePointIndex(19));

// Level 2: "brown"
Source level2 = level1.subSource(new CodePointIndex(6), new CodePointIndex(11));

// 位置アクセス
System.out.println("Text: " + level2.sourceAsString());               // "brown"
System.out.println("Parent: " + level2.parent().get().sourceAsString()); // "quick brown fox"
System.out.println("Offset from parent: " + level2.offsetFromParent());  // 6
System.out.println("Offset from root: " + level2.offsetFromRoot());      // 10

// 階層を親へ遡る
Source current = level2;
while (current.hasParent()) {
    System.out.println("  " + current.sourceAsString());
    current = current.parent().get();
}
System.out.println("Root: " + current.sourceAsString());
```

#### 2. CodePointIndex - Unicode 対応の位置追跡

`CodePointIndex` はソース内の位置を **Unicode コードポイントオフセット** で表します。文字インデックスやバイトオフセットではありません。これは次を正しく扱うために重要です:
- Emoji（😀 = 1 code point, 2 Java chars）
- サロゲートペア
- UTF-8 のマルチバイト列
- 結合文字

**なぜコードポイントが重要か**

```java
String text = "A😀B";  // A, emoji (surrogate pair), B

// NG: 文字インデックスで扱う
text.charAt(0);  // 'A'
text.charAt(1);  // '\uD83D' (high surrogate - wrong!)
text.charAt(2);  // '\uDE00' (low surrogate - wrong!)
text.charAt(3);  // 'B'

// OK: code point index で扱う
Source source = StringSource.createRootSource(text);
source.getCodePointAt(new CodePointIndex(0));  // 'A' (65)
source.getCodePointAt(new CodePointIndex(1));  // '😀' (128512)
source.getCodePointAt(new CodePointIndex(2));  // 'B' (66)

// emoji を含む SubSource
Source emoji = source.subSource(
    new CodePointIndex(1),
    new CodePointIndex(2)
);
assertEquals("😀", emoji.sourceAsString());
```

**CodePointIndex の操作**

```java
CodePointIndex index = new CodePointIndex(10);

// 算術
CodePointIndex next = index.newWithIncrements();           // 11
CodePointIndex prev = index.newWithDecrements();           // 9
CodePointIndex plus5 = index.newWithAdd(5);                // 15
CodePointIndex minus3 = index.newWithMinus(3);             // 7

// 比較
index.eq(new CodePointIndex(10));    // true
index.lt(new CodePointIndex(15));    // true
index.ge(new CodePointIndex(5));     // true

// 変換
CodePointOffset offset = index.toCodePointOffset();
CodePointLength length = new CodePointLength(index);

// 値アクセス
int value = index.value();  // 10
```

**関連する位置型**

```java
// CodePointIndex - code point 単位の位置（主型）
CodePointIndex codePointPos = new CodePointIndex(5);

// CodePointOffset - 相対オフセット
CodePointOffset offset = new CodePointOffset(3);
CodePointIndex newPos = codePointPos.newWithAdd(offset);

// CodePointLength - code point 単位の長さ
CodePointLength length = new CodePointLength(10);
Source sub = source.peek(codePointPos, length);

// StringIndex - Java String 上の位置（char 単位）
// String 操作向け内部用途
StringIndex stringPos = source.toStringIndex(codePointPos);

// LineNumber - 行番号（1-based）
LineNumber line = source.positionResolver()
    .lineNumberFrom(codePointPos);

// CodePointIndexInLine - 行内列
CodePointIndexInLine column = source.positionResolver()
    .codePointIndexInLineFrom(codePointPos);
```

**位置追跡の例**

```java
Source source = StringSource.createRootSource(
    "line 1\nline 2 with 😀\nline 3"
);

// emoji の位置
CodePointIndex emojiPos = new CodePointIndex(18);  // Position of 😀

// 行・列を取得
PositionResolver resolver = source.positionResolver();
LineNumber line = resolver.lineNumberFrom(emojiPos);
CodePointIndexInLine column = resolver.codePointIndexInLineFrom(emojiPos);

System.out.printf(
    "Emoji at line %d, column %d%n",
    line.value,      // 2
    column.value     // 11
);

// 座標系変換
StringIndex stringIdx = source.toStringIndex(emojiPos);
CodePointIndex backToCodePoint = source.toCodePointIndex(stringIdx);

// サロゲートにより stringIdx の値は異なりうるが、
// backToCodePoint == emojiPos
```

#### 3. CursorRange - テキスト範囲の表現

`CursorRange` は開始（inclusive）と終了（exclusive）の位置を持つテキスト範囲を表現します。Unlaxer 全体で次に使われます:
- Token 範囲
- エラー位置
- 選択範囲
- Source 境界

**基本的な CursorRange**

```java
Source source = StringSource.createRootSource("Hello World");

// "World" の範囲を作る
CursorRange range = CursorRange.of(
    new CodePointIndex(6),     // 開始（inclusive）
    new CodePointIndex(11),    // 終了（exclusive）
    new CodePointOffset(0),    // root からのオフセット
    SourceKind.root,
    source.positionResolver()
);

// 境界へアクセス
StartInclusiveCursor start = range.startIndexInclusive;
EndExclusiveCursor end = range.endIndexExclusive;

// 位置
CodePointIndex startPos = start.position();        // 6
CodePointIndex endPos = end.position();            // 11

// 行・列情報
LineNumber startLine = start.lineNumber();
CodePointIndexInLine startCol = start.positionInLine();
```

**SubSource の CursorRange**

subSource では CursorRange がローカル位置とグローバル位置の両方を扱います:

```java
Source root = StringSource.createRootSource("0123456789");

// subSource "3456" を作る
Source sub = root.subSource(
    new CodePointIndex(3),
    new CodePointIndex(7)
);

CursorRange subRange = sub.cursorRange();

// root 座標系での位置
CodePointIndex posInRoot = subRange.startIndexInclusive.positionInRoot();
// = 3

// subSource 座標系での位置
CodePointIndex posInSub = subRange.startIndexInclusive.positionInSub();
// = 0（subSource は自身の 0 から始まる）

// Token が両座標系を追跡できる理由
```

**CursorRange の操作**

```java
Source source = StringSource.createRootSource("ABCDEFGH");
PositionResolver resolver = source.positionResolver();

CursorRange range1 = CursorRange.of(
    new CodePointIndex(2),
    new CodePointIndex(5),
    CodePointOffset.ZERO,
    SourceKind.root,
    resolver
);  // "CDE"

CursorRange range2 = CursorRange.of(
    new CodePointIndex(4),
    new CodePointIndex(7),
    CodePointOffset.ZERO,
    SourceKind.root,
    resolver
);  // "EFG"

// 位置テスト
boolean contains = range1.match(new CodePointIndex(3));  // true
boolean before = range1.lt(new CodePointIndex(6));       // true
boolean after = range1.gt(new CodePointIndex(1));        // true

// 範囲関係
RangesRelation rel = range1.relation(range2);
// Returns: crossed（範囲が重なる）

// 同一範囲
CursorRange range3 = CursorRange.of(
    new CodePointIndex(2),
    new CodePointIndex(5),
    CodePointOffset.ZERO,
    SourceKind.root,
    resolver
);
range1.relation(range3);  // Returns: equal

// 入れ子範囲
CursorRange outer = CursorRange.of(
    new CodePointIndex(1),
    new CodePointIndex(7),
    CodePointOffset.ZERO,
    SourceKind.root,
    resolver
);
range1.relation(outer);  // Returns: outer（range1 は outer の内側）
```

**完全な位置追跡例**

```java
public class PositionTrackingExample {

    public static void main(String[] args) {
        // 元ファイル内容
        String fileContent = """
            function hello() {
                print("Hello 😀");
            }
            """;

        Source root = StringSource.createRootSource(fileContent);

        // 関数本体を切り出す
        int bodyStart = fileContent.indexOf("{") + 1;
        int bodyEnd = fileContent.indexOf("}");

        Source functionBody = root.subSource(
            new CodePointIndex(bodyStart),
            new CodePointIndex(bodyEnd)
        );

        System.out.println("Function body: " + functionBody.sourceAsString());
        System.out.println("Offset from root: " + functionBody.offsetFromRoot());

        // 本体をパース
        Parser parser = /* ... */;
        ParseContext context = new ParseContext(functionBody);
        Parsed result = parser.parse(context);

        if (result.isSucceeded()) {
            Token token = result.getRootToken();
            CursorRange tokenRange = token.getRange();

            // function body 内の位置
            int localStart = tokenRange.startIndexInclusive.positionInSub().value();

            // 元ファイル内の位置
            int globalStart = tokenRange.startIndexInclusive.positionInRoot().value();

            // 元ファイル内の行・列
            LineNumber line = tokenRange.startIndexInclusive.lineNumber();
            CodePointIndexInLine column = tokenRange.startIndexInclusive.positionInLine();

            System.out.printf(
                "Token at local pos %d, global pos %d (line %d, col %d)%n",
                localStart, globalStart, line.value, column.value
            );

            // token テキストを抽出
            Source tokenSource = root.subSource(tokenRange);
            System.out.println("Token text: " + tokenSource.sourceAsString());
        }

        context.close();
    }
}
```

**重要ポイント**

1. **Source 階層**: subSource は親関係を維持するため、元入力への位置追跡が可能

2. **Code Point Indexing**: すべての位置は文字/バイトではなく Unicode code point で扱う。emoji やマルチバイト文字を正しく処理できる

3. **二重座標系**: CursorRange は次の両方をサポート:
   - `positionInSub()`: 現在 source 内での位置（0-based）
   - `positionInRoot()`: root source 上での位置（元座標）

4. **位置合成**: ネストした subSource ではオフセットが合成される:
   ```
   root -> sub1 (offset 10) -> sub2 (offset 5)
   sub2.offsetFromRoot() = 10 + 5 = 15
   ```

5. **Detached Source**: source を変換（uppercase、replace 等）すると親から切り離され、新しい座標系になる

このアーキテクチャにより Unlaxer は次を実現します:
- 正確なファイル位置でのエラー報告
- 文脈を維持したインクリメンタルパース
- Unicode の正しい取り扱い
- 位置追跡付きのネストパース
- go-to-definition のような IDE 機能の実装基盤

#### 4. ParseContext

`ParseContext` はすべてのパース処理に渡される状態オブジェクトです:

```java
public class ParseContext {
    public final Source source;
    final Deque<TransactionElement> tokenStack;
    Map<ChoiceInterface, Parser> chosenParserByChoice;
    // ... other state
}
```

**主な責務**:
- **Source 管理**: 入力文字列を保持
- **位置追跡**: cursor で現在位置を管理
- **バックトラック**: rollback 用のトランザクションスタック
- **スコープ管理**: parser 固有・グローバルの scope tree
- **Choice 追跡**: どの選択肢が使われたかを記録

#### 3. Parser Interface

すべての parser が実装すべき中心インターフェース:

```java
public interface Parser {
    Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch);

    default Parsed parse(ParseContext parseContext) {
        return parse(parseContext, getTokenKind(), false);
    }
}
```

**要点**:
- 中心メソッドは `parse(ParseContext, TokenKind, boolean)` の 1 つ
- 結果として status と token を持つ `Parsed` を返す
- ステートレス（状態は `ParseContext` 側に保持）
- 複数回の parse で再利用可能

#### 4. Parsed Result

```java
public class Parsed {
    public enum Status { succeeded, stopped, failed }

    public Status status;
    private Token token;
    private TokenList originalTokens;
}
```

**フィールド**:
- `status`: パース結果（succeeded/stopped/failed）
- `token`: マッチしたサブツリーの root token（成功時）
- `originalTokens`: パース中に生成された全 token

#### 5. Token (Syntax Tree Node)

```java
public class Token {
    private final Parser parser;
    private final Range range;
    private final List<Token> children;
}
```

**要点**:
- 入力のマッチした部分を表す
- children によって木構造を形成
- 生成した parser への参照を持つ
- source 上の位置範囲を保持

#### 6. Transaction Stack

トランザクションスタックはバックトラックを可能にします:

```java
Deque<TransactionElement> tokenStack;

// Begin transaction
TransactionElement element = new TransactionElement(cursor);
tokenStack.push(element);

// On success - commit
tokenStack.pop();
// Tokens are kept, cursor advances

// On failure - rollback
tokenStack.pop();
// Tokens discarded, cursor restored
```

### パースの流れ

#### フローダイアグラム

```
User Code
    ↓
parser.parse(parseContext)
    ↓
Parser.parse() method
    ↓
Check cursor position
    ↓
┌─────────────────────────┐
│  Begin Transaction      │
│  (push to stack)        │
└─────────────────────────┘
    ↓
Try to match input
    ↓
    ├─── Success ─────────┐
    │                     ↓
    │              Create Token
    │                     ↓
    │              Advance Cursor
    │                     ↓
    │              ┌──────────────────┐
    │              │ Commit           │
    │              │ (pop stack)      │
    │              └──────────────────┘
    │                     ↓
    │              Return Parsed{succeeded, token}
    │
    └─── Failure ─────────┐
                          ↓
                   ┌──────────────────┐
                   │ Rollback         │
                   │ (pop stack,      │
                   │  restore cursor) │
                   └──────────────────┘
                          ↓
                   Return Parsed{failed}
```

### カスタムコンビネータの実装

#### パターン 1: Terminal Parser（葉ノード）

終端パーサーは入力の実文字をマッチします:

```java
public class MyCharParser implements Parser {
    private final char expected;

    public MyCharParser(char expected) {
        this.expected = expected;
    }

    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        // 現在の cursor を取得
        TransactionElement transaction = context.getTokenStack().peek();
        ParserCursor cursor = transaction.getCursor();

        // 末尾判定
        if (cursor.isEndOfSource()) {
            return Parsed.FAILED;
        }

        // 現在文字を取得
        CodePointString str = context.source.getCodePointString();
        int codePoint = str.getCodePointAt(cursor.getCodePointIndex());

        // マッチ判定
        if (codePoint == expected) {
            // マッチ文字の token を作成
            Range range = new Range(
                cursor.getCodePointIndex(),
                cursor.getCodePointIndex().plus(1)
            );
            Token token = new Token(this, range, context.source);

            // cursor を進める
            transaction.setCursor(cursor.advance(1));

            return new Parsed(token, Parsed.Status.succeeded);
        } else {
            return Parsed.FAILED;
        }
    }
}
```

**手順**:
1. transaction stack から cursor 取得
2. 入力末尾か確認
3. 現在文字/部分文字列を取得
4. 期待値と比較
5. マッチ時: token 作成、cursor 進行、success
6. 不一致時: failure を返す（cursor は不変）

#### パターン 2: Sequence Combinator

子パーサーを順次マッチさせます:

```java
public class MyChain implements Parser {
    private final List<Parser> children;

    public MyChain(Parser... children) {
        this.children = Arrays.asList(children);
    }

    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        TransactionElement transaction = context.getTokenStack().peek();
        ParserCursor startCursor = transaction.getCursor();

        List<Token> childTokens = new ArrayList<>();

        // 各子を順に試行
        for (Parser child : children) {
            Parsed childParsed = child.parse(context);

            if (childParsed.isFailed()) {
                // cursor を戻して失敗
                transaction.setCursor(startCursor);
                return Parsed.FAILED;
            }

            childTokens.add(childParsed.getRootToken());
        }

        // すべて成功 -> 親 token 作成
        ParserCursor endCursor = transaction.getCursor();
        Range range = new Range(
            startCursor.getCodePointIndex(),
            endCursor.getCodePointIndex()
        );
        Token token = new Token(this, range, context.source, childTokens);

        return new Parsed(token, Parsed.Status.succeeded);
    }
}
```

**手順**:
1. 開始 cursor を保存
2. 各子 parser を順に実行
3. 途中失敗時: cursor を復元して失敗
4. 全成功時: 子 token 群を持つ親 token を作成
5. 親 token で成功を返す

**重要**: cursor の前進は子 parser が行うため、親が手動で進める必要はありません。

#### パターン 3: Choice Combinator

成功するまで候補を試します:

```java
public class MyChoice implements Parser {
    private final List<Parser> alternatives;

    public MyChoice(Parser... alternatives) {
        this.alternatives = Arrays.asList(alternatives);
    }

    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        TransactionElement transaction = context.getTokenStack().peek();
        ParserCursor startCursor = transaction.getCursor();

        // 各候補を試行
        for (Parser alternative : alternatives) {
            Parsed parsed = alternative.parse(context);

            if (parsed.isSucceeded()) {
                // 最初の成功を採用
                return parsed;
            }

            // 次候補のために cursor 復元
            transaction.setCursor(startCursor);
        }

        // 全候補失敗
        return Parsed.FAILED;
    }
}
```

**手順**:
1. 開始 cursor を保存
2. 各候補 parser を実行
3. 最初の成功で即 return
4. 失敗時は cursor 復元して次候補へ
5. 全失敗なら failure

**重要**: 各試行の間で必ず cursor を戻すこと。これがバックトラックの前提です。

#### パターン 4: Repetition Combinator

子パーサーを複数回マッチさせます:

```java
public class MyZeroOrMore implements Parser {
    private final Parser child;

    public MyZeroOrMore(Parser child) {
        this.child = child;
    }

    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        TransactionElement transaction = context.getTokenStack().peek();
        ParserCursor startCursor = transaction.getCursor();

        List<Token> matchedTokens = new ArrayList<>();

        // 失敗するまで繰り返し
        while (true) {
            ParserCursor beforeAttempt = transaction.getCursor();
            Parsed parsed = child.parse(context);

            if (parsed.isFailed()) {
                // 失敗試行ぶんを巻き戻す
                transaction.setCursor(beforeAttempt);
                break;
            }

            matchedTokens.add(parsed.getRootToken());

            // 無限ループ検出
            ParserCursor afterAttempt = transaction.getCursor();
            if (afterAttempt.equals(beforeAttempt)) {
                // 子が空文字をマッチしたので終了
                break;
            }
        }

        // 0 回マッチでも token を作成
        Range range = new Range(
            startCursor.getCodePointIndex(),
            transaction.getCursor().getCodePointIndex()
        );
        Token token = new Token(this, range, context.source, matchedTokens);

        return new Parsed(token, Parsed.Status.succeeded);
    }
}
```

**手順**:
1. child parser のマッチをループ
2. 成功するたび token を収集
3. 失敗時は cursor 復元してループ終了
4. 0 回でも成功を返す
5. cursor が進まない場合は無限ループ回避で停止

### Meta Token と Terminal Token

Unlaxer は `CreateMetaTokenSpecifier` によって 2 モードを持ちます:

#### createMetaOff（コンパクトツリー）

終端パーサーのみ token を作成:

```
Input: "1+2"

Token Tree:
'1+2'
 '1' : DigitParser
 '+' : PlusParser
 '2' : DigitParser
```

**用途**: 終端トークン（字句情報）のみが欲しい場合

#### createMetaOn（フルツリー）

コンビネータを含むすべての parser が token を作成:

```
Input: "1+2"

Token Tree:
'1+2' : Chain
 '1' : OneOrMore
  '1' : DigitParser
 '+' : Choice
  '+' : PlusParser
 '2' : OneOrMore
  '2' : DigitParser
```

**用途**: AST 構築などで完全な構造情報が必要な場合

### 発展: Transaction 管理

トランザクションスタックはバックトラックの要です:

```java
// Unlaxer は transaction を自動管理するが、理解しておくと有用:

// 1. parser 開始
Deque<TransactionElement> stack = context.getTokenStack();
TransactionElement current = stack.peek();
ParserCursor savedCursor = current.getCursor();

// 2. パース実行
Parsed result = childParser.parse(context);

// 3a. 成功時 - 子 parser が cursor を進めている
// そのまま結果を使う

// 3b. 失敗時 - cursor 復元
if (result.isFailed()) {
    current.setCursor(savedCursor);
}
```

**transaction の保証**:
- 失敗 parser は cursor を進めない
- 成功 parser は cursor を進める
- 親 parser は子 parse 後の cursor を信頼できる

### デバッグ手法

#### Parse ログを有効化

```java
ParseContext context = new ParseContext(
    source,
    ParserDebugSpecifier.debug,
    TransactionDebugSpecifier.debug
);

// 詳細ログを生成:
// - parse.log: Parser 呼び出しと結果
// - transaction.log: Transaction push/pop 操作
// - token.log: Token 生成
// - combined.log: すべてを統合
```

#### カスタム Parser Listener

```java
public class MyListener implements ParserListener {
    @Override
    public void onBefore(Parser parser, ParseContext context) {
        System.out.println("Trying: " + parser.getClass().getSimpleName());
    }

    @Override
    public void onAfter(Parser parser, ParseContext context, Parsed result) {
        System.out.println("Result: " + result.status);
    }
}

// listener を登録
context.getParserListenerByName().put(
    new Name("MyListener"),
    new MyListener()
);
```

## Converting Parse Tree to AST

Unlaxer は `org.unlaxer.ast` パッケージを通じて強力な AST（抽象構文木）変換システムを提供します。これにより、パースツリーを、解釈やコンパイルに適したより意味的な木構造へ変換できます。

### 問題を理解する

パースツリーは文法構造をそのまま反映するため、冗長になりがちです:

```
Parse Tree for "1 + 2 + 3":
Chain
 ├─ OneOrMore (Number)
 │   └─ '1'
 ├─ ZeroOrMore
 │   ├─ Chain
 │   │   ├─ Choice (Operator)
 │   │   │   └─ '+'
 │   │   └─ OneOrMore (Number)
 │   │       └─ '2'
 │   └─ Chain
 │       ├─ Choice (Operator)
 │       │   └─ '+'
 │       └─ OneOrMore (Number)
 │           └─ '3'
```

AST はこれを意味構造に簡約します:

```
AST for "1 + 2 + 3":
'+'
 ├─ '+'
 │   ├─ '1'
 │   └─ '2'
 └─ '3'
```

### ASTMapper インターフェース

AST 変換の中核インターフェース:

```java
public interface ASTMapper {
    /**
     * Transform a parse tree token into an AST token
     */
    Token toAST(ASTMapperContext context, Token parsedToken);

    /**
     * Check if this mapper can handle the token
     */
    default boolean canASTMapping(Token parsedToken) {
        return parsedToken.parser.getClass() == getClass();
    }
}
```

### AST ノード種別

各ノードの意味的役割を定義します:

```java
public enum ASTNodeKind {
    Operator,                  // Binary/unary operators
    Operand,                   // Values, variables, literals
    ChoicedOperatorRoot,       // Root of operator choice
    ChoicedOperator,           // Individual operator in choice
    ChoicedOperandRoot,        // Root of operand choice
    ChoicedOperand,            // Individual operand in choice
    Space,                     // Whitespace (usually filtered)
    Comment,                   // Comments (usually filtered)
    Annotation,                // Annotations/decorators
    Other,                     // Other node types
    NotSpecified              // Not yet classified
}
```

### 組み込み AST パターン

#### 1. RecursiveZeroOrMoreBinaryOperator

`number (operator number)*` のような文法向け。

**Parse Tree**:
```
'1+2+3'
 ├─ '1' (number)
 ├─ '+' (operator)
 ├─ '2' (number)
 ├─ '+' (operator)
 └─ '3' (number)
```

**AST**（左結合木）:
```
'+'
 ├─ '+'
 │   ├─ '1'
 │   └─ '2'
 └─ '3'
```

**実装**:

```java
public class AdditionParser extends Chain
    implements RecursiveZeroOrMoreBinaryOperator {

    public AdditionParser() {
        super(
            Parser.get(NumberParser.class),
            new ZeroOrMore(
                new Chain(
                    Parser.get(PlusParser.class),
                    Parser.get(NumberParser.class)
                )
            )
        );
    }
}

// toAST は interface 側で自動提供される
```

#### 2. RecursiveZeroOrMoreOperator

`operand operator*` のような postfix/prefix 演算子向け。

**Parse Tree**:
```
'array[0][1]'
 ├─ 'array' (operand)
 ├─ '[0]' (operator)
 └─ '[1]' (operator)
```

**AST**:
```
'[1]'
 └─ '[0]'
     └─ 'array'
```

**実装**:

```java
public class SubscriptParser extends Chain
    implements RecursiveZeroOrMoreOperator {

    public SubscriptParser() {
        super(
            Parser.get(IdentifierParser.class),
            new ZeroOrMore(
                Parser.get(IndexOperatorParser.class)
            )
        );
    }
}
```

### 完全な AST 例

```java
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.posix.*;
import org.unlaxer.ast.*;
import org.unlaxer.context.*;

// Step 1: parser に AST node kind を付与
public class NumberParser extends OneOrMore implements StaticParser {
    public NumberParser() {
        super(DigitParser.class);
        // operand としてマーク
        addTag(ASTNodeKind.Operand.tag());
    }
}

public class PlusParser extends SingleCharacterParser implements StaticParser {
    @Override
    public boolean isMatch(char target) {
        return '+' == target;
    }

    // constructor または初期化で operator を付与
    // addTag(ASTNodeKind.Operator.tag());
}

public class MinusParser extends SingleCharacterParser implements StaticParser {
    @Override
    public boolean isMatch(char target) {
        return '-' == target;
    }

    // operator としてマーク
    // addTag(ASTNodeKind.Operator.tag());
}

// Step 2: AST mapper 付き parser を作成
public class ExpressionParser extends Chain
    implements RecursiveZeroOrMoreBinaryOperator {

    public ExpressionParser() {
        super(
            Parser.get(NumberParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(
                        Parser.get(PlusParser.class),
                        Parser.get(MinusParser.class)
                    ),
                    Parser.get(NumberParser.class)
                )
            )
        );
    }
}

// Step 3: parse して AST へ変換
public class ASTExample {
    public static void main(String[] args) {
        // Parse
        Parser parser = Parser.get(ExpressionParser.class);
        ParseContext context = new ParseContext(
            StringSource.createRootSource("1 + 2 - 3")
        );
        Parsed result = parser.parse(context);
        Token parseTree = result.getRootToken();

        // AST mapper context を作成
        ASTMapperContext astContext = ASTMapperContext.create(
            new ExpressionParser()
            // 必要なら mapper を追加
        );

        // AST に変換
        Token ast = astContext.toAST(parseTree);

        // 2 つの木を表示
        System.out.println("Parse Tree:");
        System.out.println(TokenPrinter.get(parseTree));

        System.out.println("\nAST:");
        System.out.println(TokenPrinter.get(ast));

        context.close();
    }
}
```

**出力**:

```
Parse Tree:
'1 + 2 - 3' : ExpressionParser
 '1' : NumberParser
  '1' : DigitParser
 ' + 2 - 3' : ZeroOrMore
  ' + 2' : Chain
   '+' : Choice
    '+' : PlusParser
   '2' : NumberParser
    '2' : DigitParser
  ' - 3' : Chain
   '-' : Choice
    '-' : MinusParser
   '3' : NumberParser
    '3' : DigitParser

AST:
'-' : MinusParser
 '+' : PlusParser
  '1' : NumberParser
   '1' : DigitParser
  '2' : NumberParser
   '2' : DigitParser
 '3' : NumberParser
  '3' : DigitParser
```

### カスタム AST Mapper

独自変換では `ASTMapper` を実装します:

```java
public class CustomFunctionCallParser extends Chain implements ASTMapper {

    public CustomFunctionCallParser() {
        super(
            Parser.get(IdentifierParser.class),  // function name
            Parser.get(LParenParser.class),
            Parser.get(ArgumentListParser.class),
            Parser.get(RParenParser.class)
        );
    }

    @Override
    public Token toAST(ASTMapperContext context, Token parsedToken) {
        TokenList children = parsedToken.getAstNodeChildren();

        // 意味要素を抽出
        Token functionName = children.get(0);  // identifier
        Token args = children.get(2);          // argument list

        // 意味要素のみで新 AST node を作成
        return functionName.newCreatesOf(
            context.toAST(functionName),
            context.toAST(args)
        );
    }
}
```

### AST のベストプラクティス

1. **終端 parser に tag を付与**: すべての終端 parser に適切な `ASTNodeKind` を設定
```java
addTag(ASTNodeKind.Operator.tag());
addTag(ASTNodeKind.Operand.tag());
```

2. **組み込みパターン活用**: `RecursiveZeroOrMoreBinaryOperator` / `RecursiveZeroOrMoreOperator` を使う

3. **再帰変換**: 子 token 処理時は常に `context.toAST()` を使う

4. **ノイズ除去**: AST から空白・コメント・構文記号を除去

5. **意味中心**: AST は文法形ではなくプログラム意味を表す

### AST における演算子優先順位

正しい優先順位のため、文法を階層化します:

```java
// expr   = term (('+' | '-') term)*
// term   = factor (('*' | '/') factor)*
// factor = number | '(' expr ')'

public class ExprParser extends LazyChain
    implements RecursiveZeroOrMoreBinaryOperator {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(TermParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(PlusParser.class, MinusParser.class),
                    Parser.get(TermParser.class)
                )
            )
        );
    }
}

public class TermParser extends Chain
    implements RecursiveZeroOrMoreBinaryOperator {
    public TermParser() {
        super(
            Parser.get(FactorParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(MultipleParser.class, DivisionParser.class),
                    Parser.get(FactorParser.class)
                )
            )
        );
    }
}
```

これにより、生成 AST で乗算が加算より強く結合されます。

## Scope Tree: Context-Dependent Parsing

Scope Tree 機能により、parser はパース中にコンテキスト情報を保存・取得できます。これは文脈依存言語や高度なパースシナリオで重要です。

### Scope Tree を理解する

Unlaxer は 2 種類のスコープを提供します:

1. **Parser-Scoped Storage**: 特定 parser インスタンスに紐づくデータ
2. **Global Scope**: 1 回の parse セッション全体で共有されるデータ

どちらも `ParseContext` からアクセスします。

### 基本的な Scope Tree 操作

```java
// パース中にデータ保存・取得が可能
ParseContext context = new ParseContext(source);

// parser に紐づけて保存
Parser myParser = /* ... */;
context.put(myParser, "some data");

// 取得
Optional<String> data = context.get(myParser, String.class);

// 名前付きキーで保存
Name variableName = Name.of("myVariable");
context.put(myParser, variableName, "value");
Optional<String> value = context.get(myParser, variableName, String.class);

// グローバルスコープ（特定 parser 非依存）
context.put(Name.of("globalVar"), "global value");
Optional<String> globalValue = context.get(Name.of("globalVar"), String.class);
```

### Use Case 1: 変数宣言と参照

変数宣言を追跡し、参照を検証します:

```java
public class VariableDeclarationParser extends Chain {

    public static final Name DECLARED_VARIABLES = Name.of("declaredVars");

    public VariableDeclarationParser() {
        super(
            Parser.get(TypeParser.class),
            Parser.get(IdentifierParser.class),
            Parser.get(SemicolonParser.class)
        );
    }

    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        Parsed result = super.parse(context, tokenKind, invertMatch);

        if (result.isSucceeded()) {
            Token root = result.getRootToken();
            Token identifier = root.getChildren().get(1);
            String varName = identifier.getConsumedString();

            // グローバルスコープに保存
            Set<String> declaredVars = context.get(DECLARED_VARIABLES, Set.class)
                .orElse(new HashSet<>());
            declaredVars.add(varName);
            context.put(DECLARED_VARIABLES, declaredVars);
        }

        return result;
    }
}

public class VariableReferenceParser extends IdentifierParser {

    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        Parsed result = super.parse(context, tokenKind, invertMatch);

        if (result.isSucceeded()) {
            String varName = result.getRootToken().getConsumedString();

            // 宣言済みか確認
            Set<String> declaredVars = context.get(
                VariableDeclarationParser.DECLARED_VARIABLES,
                Set.class
            ).orElse(Collections.emptySet());

            if (!declaredVars.contains(varName)) {
                // 未宣言変数 - エラーまたは警告
                System.err.println("Undeclared variable: " + varName);
            }
        }

        return result;
    }
}
```

### Use Case 2: ネストスコープ管理

ブロックスコープ言語向けにスコープレベルを追跡します:

```java
public class BlockParser extends LazyChain {

    public static final Name SCOPE_LEVEL = Name.of("scopeLevel");
    public static final Name SCOPE_VARIABLES = Name.of("scopeVariables");

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(LBraceParser.class),
            Parser.get(StatementsParser.class),
            Parser.get(RBraceParser.class)
        );
    }

    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        // 新スコープへ入る
        int currentLevel = context.get(SCOPE_LEVEL, Integer.class).orElse(0);
        context.put(SCOPE_LEVEL, currentLevel + 1);

        // このスコープ用の変数マップを作成
        Map<String, Token> scopeVars = new HashMap<>();
        context.put(this, SCOPE_VARIABLES, scopeVars);

        Parsed result = super.parse(context, tokenKind, invertMatch);

        // スコープを抜ける
        context.put(SCOPE_LEVEL, currentLevel);

        return result;
    }
}
```

### Use Case 3: シンボルテーブル構築

パース中に完全なシンボルテーブルを構築します:

```java
public class SymbolTableBuilder {

    public static class Symbol {
        String name;
        String type;
        int scopeLevel;
        Token declarationToken;

        public Symbol(String name, String type, int scopeLevel, Token token) {
            this.name = name;
            this.type = type;
            this.scopeLevel = scopeLevel;
            this.declarationToken = token;
        }
    }

    public static final Name SYMBOL_TABLE = Name.of("symbolTable");

    public static void addSymbol(ParseContext context, Symbol symbol) {
        Map<String, Symbol> table = context.get(SYMBOL_TABLE, Map.class)
            .orElse(new HashMap<>());
        table.put(symbol.name, symbol);
        context.put(SYMBOL_TABLE, table);
    }

    public static Optional<Symbol> lookupSymbol(ParseContext context, String name) {
        Map<String, Symbol> table = context.get(SYMBOL_TABLE, Map.class)
            .orElse(Collections.emptyMap());
        return Optional.ofNullable(table.get(name));
    }
}

public class FunctionDeclarationParser extends Chain {
    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        Parsed result = super.parse(context, tokenKind, invertMatch);

        if (result.isSucceeded()) {
            Token root = result.getRootToken();
            String functionName = extractFunctionName(root);
            String returnType = extractReturnType(root);
            int scopeLevel = context.get(BlockParser.SCOPE_LEVEL, Integer.class)
                .orElse(0);

            Symbol symbol = new Symbol(functionName, returnType, scopeLevel, root);
            SymbolTableBuilder.addSymbol(context, symbol);
        }

        return result;
    }
}
```

### Scope Tree のベストプラクティス

1. **名前付きキーを使う**: 型安全性と可読性のため `Name.of()` を使用
2. **後始末する**: スコープ終了時にデータを除去しメモリリークを防ぐ
3. **型安全を維持**: class 指定付き generic API で取得する
4. **キーを定数化**: スコープキーは static 定数で管理
5. **階層スコープ**: 階層データには parser 固有スコープを使う

## Backward Reference: Matching Previous Tokens

後方参照により、文書内で先にパースされた token を参照してマッチできます。これは XML タグの対応やパターンマッチのような構文で重要です。

### MatchedTokenParser

`MatchedTokenParser` は過去にマッチした token を検索し、それに一致するか検証します:

```java
public class MatchedTokenParser extends AbstractParser {

    // Constructor: 特定 parser が作った token を参照
    public MatchedTokenParser(Parser targetParser)

    // Constructor: predicate で token を参照
    public MatchedTokenParser(Predicate<Token> tokenPredicator)

    // With slicing: マッチ token の一部を切り出す
    public MatchedTokenParser(Parser targetParser, RangeSpecifier rangeSpecifier, boolean reverse)
}
```

### Use Case 1: XML 形式の対応タグ

開始タグと終了タグを対応させます:

```java
// Grammar: <tagname>content</tagname>
// Opening tag と closing tag は一致する必要がある

public class XmlElementParser extends Chain {

    public XmlElementParser() {
        super(
            Parser.get(OpeningTagParser.class),   // <tagname>
            Parser.get(ContentParser.class),       // content
            Parser.get(ClosingTagParser.class)     // </tagname>
        );
    }
}

public class OpeningTagParser extends Chain {
    public OpeningTagParser() {
        super(
            new MappedSingleCharacterParser('<'),
            Parser.get(IdentifierParser.class),  // Tag name
            new MappedSingleCharacterParser('>')
        );
    }
}

public class ClosingTagParser extends Chain {

    public ClosingTagParser() {
        super(
            new Chain(
                new MappedSingleCharacterParser('<'),
                new MappedSingleCharacterParser('/')
            ),
            // OpeningTagParser の identifier を参照
            new MatchedTokenParser(
                Parser.get(IdentifierParser.class)
            ),
            new MappedSingleCharacterParser('>')
        );
    }
}

// Usage
ParseContext context = new ParseContext(
    StringSource.createRootSource("<div>Hello</div>")
);
Parser parser = Parser.get(XmlElementParser.class);
Parsed result = parser.parse(context);

// Succeeds: <div>Hello</div>
// Fails:    <div>Hello</span>  (closing tag 不一致)
```

### Use Case 2: Here Document

heredoc 形式で区切り文字を一致させます:

```java
// Grammar: <<DELIMITER\ncontent\nDELIMITER

public class HereDocParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // Opening
            new Chain(
                new MappedSingleCharacterParser('<'),
                new MappedSingleCharacterParser('<'),
                Parser.get(IdentifierParser.class)  // Delimiter
            ),
            new LineBreakParser(),

            // Content（closing delimiter まで）
            new ZeroOrMore(
                new Chain(
                    new Not(
                        new MatchedTokenParser(
                            Parser.get(IdentifierParser.class)
                        )
                    ),
                    new WildCardCharacterParser()
                )
            ),

            // Closing delimiter（opening と一致必須）
            new MatchedTokenParser(
                Parser.get(IdentifierParser.class)
            )
        );
    }
}

// Example input:
// <<END
// This is the content
// Multiple lines
// END
```

### Use Case 3: カスタム区切り引用文字列

任意の区切りで対応する引用をマッチします:

```java
// Allow: q{content}, q[content], q(content), etc.

public class CustomQuotedStringParser extends Chain {

    static final Map<Character, Character> PAIRS = Map.of(
        '{', '}',
        '[', ']',
        '(', ')',
        '<', '>'
    );

    public CustomQuotedStringParser() {
        super(
            new MappedSingleCharacterParser('q'),
            Parser.get(DelimiterParser.class),  // Opening delimiter

            // Content
            new ZeroOrMore(
                new Chain(
                    new Not(
                        new MatchedTokenParser(
                            Parser.get(DelimiterParser.class)
                        ).effect(this::getClosingDelimiter)
                    ),
                    new WildCardCharacterParser()
                )
            ),

            // Closing delimiter（opening と対応）
            new MatchedTokenParser(
                Parser.get(DelimiterParser.class)
            ).effect(this::getClosingDelimiter)
        );
    }

    private String getClosingDelimiter(String opening) {
        char openChar = opening.charAt(0);
        char closeChar = PAIRS.getOrDefault(openChar, openChar);
        return String.valueOf(closeChar);
    }
}

// Matches: q{hello}, q[world], q(foo), q<bar>
```

### Use Case 4: パターンマッチ変数

パターンマッチでキャプチャした変数を参照します:

```java
// Grammar: pattern = value
// pattern で定義した変数が value で一致する必要がある

public class PatternMatchParser extends Chain {

    public PatternMatchParser() {
        super(
            Parser.get(PatternParser.class),    // Defines variables
            new MappedSingleCharacterParser('='),
            Parser.get(ValueParser.class)       // Must match pattern
        );
    }
}

public class ValueParser extends LazyChoice {

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // pattern 変数を参照
            new MatchedTokenParser(
                token -> token.getParser() instanceof VariableParser
            ),
            // またはリテラル値
            Parser.get(LiteralParser.class)
        );
    }
}

// Example:
// point(x, y) = point(10, 20)  // Succeeds, binds x=10, y=20
// point(x, y) = line(10, 20)   // Fails, structure mismatch
```

### 発展: マッチ token のスライス

マッチ token の一部を切り出します:

```java
// 角括弧なしでタグ名だけ抽出
MatchedTokenParser tagMatcher = new MatchedTokenParser(
    Parser.get(TagParser.class)
).slice(
    new RangeSpecifier(1, -1),  // 先頭・末尾文字を除外
    false
);

// 例: TagParser が "<div>" にマッチした場合、"div" にマッチ
```

### 後方参照のベストプラクティス

1. **Predicate を使う**: 複数 parser 種別をまたぐ柔軟なマッチ
2. **マッチをキャッシュ**: `MatchedTokenParser` は scope tree に結果キャッシュして効率化
3. **参照先を明記**: どの token を参照するかドキュメント化
4. **エラーハンドリング**: マッチ失敗時に明確なエラーを返す
5. **順序に注意**: 参照対象 parser が先に実行される設計にする

## Error Reporting with ErrorMessageParser

`ErrorMessageParser` を使うと、文法内に直接エラーメッセージを埋め込み、文脈依存のエラー報告を実現できます。

### 基本エラーメッセージ

```java
Parser parser = new Chain(
    Parser.get(DigitParser.class),
    Parser.get(PlusParser.class),
    new Choice(
        Parser.get(DigitParser.class),
        new ErrorMessageParser("Expected digit after '+' operator")
    )
);

ParseContext context = new ParseContext(
    StringSource.createRootSource("1+")
);
Parsed result = parser.parse(context);

// Parse は成功だが、エラーメッセージを保持
if (result.isSucceeded()) {
    List<ErrorMessage> errors = TokenPrinter.getErrorMessages(
        result.getRootToken()
    );

    for (ErrorMessage error : errors) {
        System.err.printf(
            "Error at position %d: %s%n",
            error.getRange().startIndexInclusive.positionInRoot().value(),
            error.getContent()
        );
    }
}
// Output: Error at position 2: Expected digit after '+' operator
```

### Expected-Hint モード（Choice フォールバック）

`Choice` のフォールバックとして「失敗させつつ、診断向けの期待ヒントだけを注入したい」場合は `ErrorMessageParser.expected(...)` を使います。

```java
Parser parser = new Chain(
    Parser.get(DigitParser.class),
    Parser.get(PlusParser.class),
    new Choice(
        Parser.get(DigitParser.class),
        ErrorMessageParser.expected("expected: digit after '+'")
    )
);

ParseContext context = new ParseContext(
    StringSource.createRootSource("1+")
);
Parsed result = parser.parse(context);

// Parse は失敗し、diagnostics から custom expected hint を参照できる。
ParseFailureDiagnostics diagnostics = context.getParseFailureDiagnostics();
```

### Use Case 1: 構文エラー回復

エラー後も継続して複数問題を検出します:

```java
public class StatementParser extends Choice {

    public StatementParser() {
        super(
            Parser.get(IfStatementParser.class),
            Parser.get(WhileStatementParser.class),
            Parser.get(ReturnStatementParser.class),
            // Fallback: エラー報告しつつ継続
            new Chain(
                new ErrorMessageParser("Invalid statement"),
                new ZeroOrMore(
                    new Chain(
                        new Not(Parser.get(SemicolonParser.class)),
                        new WildCardCharacterParser()
                    )
                ),
                new Optional(Parser.get(SemicolonParser.class))
            )
        );
    }
}

// Input: "if (x) { } invalid stuff; while (y) { }"
// "invalid stuff" でエラー報告しつつ継続
```

### Use Case 2: 必須要素の欠落

```java
public class FunctionCallParser extends Chain {

    public FunctionCallParser() {
        super(
            Parser.get(IdentifierParser.class),
            new Choice(
                Parser.get(LParenParser.class),
                new ErrorMessageParser("Missing '(' after function name")
            ),
            new Optional(Parser.get(ArgumentListParser.class)),
            new Choice(
                Parser.get(RParenParser.class),
                new ErrorMessageParser("Missing ')' in function call")
            )
        );
    }
}

// Input: "foo bar"
// Reports: Missing '(' after function name
```

### Use Case 3: 文脈依存エラーメッセージ

文脈に応じて異なるメッセージを返します:

```java
public class TypeAnnotationParser extends Chain {

    public TypeAnnotationParser() {
        super(
            Parser.get(ColonParser.class),
            new Choice(
                Parser.get(TypeNameParser.class),
                new ErrorMessageParser("Expected type name after ':'")
            )
        );
    }
}

public class VariableDeclarationParser extends Chain {

    public VariableDeclarationParser() {
        super(
            new Choice(
                new Chain(
                    Parser.get(VarKeywordParser.class),
                    Parser.get(IdentifierParser.class)
                ),
                new ErrorMessageParser("Variable declaration must start with 'var'")
            ),
            new Optional(Parser.get(TypeAnnotationParser.class)),
            new Choice(
                new Chain(
                    Parser.get(EqualsParser.class),
                    Parser.get(ExpressionParser.class)
                ),
                new ErrorMessageParser("Expected '=' and initializer")
            ),
            new Choice(
                Parser.get(SemicolonParser.class),
                new ErrorMessageParser("Missing ';' at end of declaration")
            )
        );
    }
}

// 各メッセージが文脈に即した原因を示す
```

### Use Case 4: 非推奨構文の警告

非推奨機能への警告としてエラーメッセージを活用します:

```java
public class OldStyleLoopParser extends Chain {

    public OldStyleLoopParser() {
        super(
            new ErrorMessageParser(
                "WARNING: Old-style loop syntax is deprecated. " +
                "Use 'for item in collection' instead."
            ),
            Parser.get(RepeatKeywordParser.class),
            Parser.get(NumberParser.class),
            Parser.get(TimesKeywordParser.class),
            Parser.get(BlockParser.class)
        );
    }
}

// Input: "repeat 5 times { ... }"
// Warning: Old-style loop syntax is deprecated...
// ただし後方互換のため parse は成功
```

### エラーメッセージ抽出

```java
// Method 1: TokenPrinter を使う
List<ErrorMessage> errors = TokenPrinter.getErrorMessages(rootToken);

for (ErrorMessage error : errors) {
    System.err.printf(
        "Line %d, Column %d: %s%n",
        error.getRange().startIndexInclusive.lineNumber().value,
        error.getRange().startIndexInclusive.positionInLine().value,
        error.getContent()
    );
}

// Method 2: ErrorMessageParser を直接使う
List<RangedContent<String>> errors =
    ErrorMessageParser.getRangedContents(rootToken, ErrorMessageParser.class);

for (RangedContent<String> error : errors) {
    // 範囲情報付きで処理
    CursorRange range = error.getRange();
    String message = error.getContent();
    // ...
}

// Method 3: カスタム走査
void findErrors(Token token, List<ErrorMessage> errors) {
    if (token.getParser() instanceof ErrorMessageParser) {
        ErrorMessageParser emp = (ErrorMessageParser) token.getParser();
        errors.add(new ErrorMessage(
            token.getSource().cursorRange(),
            emp.get()
        ));
    }

    for (Token child : token.getChildren()) {
        findErrors(child, errors);
    }
}
```

### エラーメッセージのベストプラクティス

1. **具体性**: 何が期待されていたかを明示する
2. **位置情報**: cursor range は自動付与される
3. **回復戦略**: `Optional` / `ZeroOrMore` と組み合わせて継続
4. **複数エラー収集**: 1 回の parse で問題をできるだけ拾う
5. **Error と Warning の区別**: メッセージ内容で重大度を表現
6. **ユーザー視点**: 利用者が修正しやすい文言にする

### 完全なエラーレポート例

```java
public class LanguageParserWithErrors {

    public static void main(String[] args) {
        String source = """
            var x = 10
            if (x > 5 {
                print(x
            }
            var y
            """;

        Parser parser = Parser.get(ProgramParser.class);
        ParseContext context = new ParseContext(
            StringSource.createRootSource(source)
        );

        Parsed result = parser.parse(context);

        if (result.isSucceeded()) {
            List<ErrorMessage> errors = TokenPrinter.getErrorMessages(
                result.getRootToken()
            );

            if (errors.isEmpty()) {
                System.out.println("✓ Parse successful with no errors");
            } else {
                System.out.println("⚠ Parse succeeded with errors:");
                for (ErrorMessage error : errors) {
                    printError(source, error);
                }
            }
        } else {
            System.out.println("✗ Parse failed completely");
        }

        context.close();
    }

    static void printError(String source, ErrorMessage error) {
        int line = error.getRange().startIndexInclusive.lineNumber().value;
        int col = error.getRange().startIndexInclusive.positionInLine().value;

        System.out.printf("%d:%d - %s%n", line, col, error.getContent());

        // エラー位置を行上に表示
        String[] lines = source.split("\n");
        if (line <= lines.length) {
            System.out.println(lines[line - 1]);
            System.out.println(" ".repeat(col) + "^");
        }
        System.out.println();
    }
}

// Output:
// ⚠ Parse succeeded with errors:
// 1:10 - Missing ';' at end of declaration
// var x = 10
//           ^
//
// 2:11 - Missing ')' after condition
// if (x > 5 {
//            ^
//
// 3:15 - Missing ')' in function call
//     print(x
//                ^
//
// 5:6 - Expected '=' and initializer
// var y
//       ^
```



Unlaxer のアーキテクチャは、カスタム言語や DSL 向け Language Server Protocol（LSP）実装に非常に適しています。

### なぜ LSP に Unlaxer か?

1. **インクリメンタルパース**: parse tree 構造により効率的な再解析が可能
2. **位置追跡**: すべての token に行/列追跡が組み込み
3. **エラー回復**: 不完全/不正入力でも穏当な処理が可能
4. **リッチなメタデータ**: token が意味解析に有用な parser 情報を保持

### 基本 LSP 実装

Unlaxer を使った LSP サーバーの土台例:

```java
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.context.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class UnlaxerLanguageServer implements LanguageServer,
                                               LanguageClientAware {

    private LanguageClient client;
    private final TextDocumentService textDocumentService;
    private final WorkspaceService workspaceService;

    // Document cache
    private final Map<String, DocumentState> documents = new HashMap<>();

    public UnlaxerLanguageServer() {
        this.textDocumentService = new UnlaxerTextDocumentService(this);
        this.workspaceService = new UnlaxerWorkspaceService(this);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(
            InitializeParams params) {

        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        capabilities.setCompletionProvider(new CompletionOptions());
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setDiagnosticProvider(new DiagnosticRegistrationOptions());

        return CompletableFuture.completedFuture(
            new InitializeResult(capabilities)
        );
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    // Document state management
    static class DocumentState {
        String uri;
        String content;
        Parsed parsed;
        List<Diagnostic> diagnostics;
        long version;

        DocumentState(String uri, String content, long version) {
            this.uri = uri;
            this.content = content;
            this.version = version;
        }
    }

    // Parse document and cache results
    void parseDocument(String uri, String content, long version) {
        try {
            Parser parser = createYourLanguageParser();
            ParseContext context = new ParseContext(
                StringSource.createRootSource(content)
            );
            Parsed result = parser.parse(context);

            List<Diagnostic> diagnostics = new ArrayList<>();

            if (result.isFailed()) {
                // parse error を LSP diagnostics に変換
                Diagnostic diagnostic = new Diagnostic();
                diagnostic.setSeverity(DiagnosticSeverity.Error);
                diagnostic.setMessage("Parse error");
                // cursor 位置に基づいて range を設定
                diagnostic.setRange(createRange(context));
                diagnostics.add(diagnostic);
            }

            DocumentState state = new DocumentState(uri, content, version);
            state.parsed = result;
            state.diagnostics = diagnostics;
            documents.put(uri, state);

            // diagnostics をクライアントへ送信
            client.publishDiagnostics(
                new PublishDiagnosticsParams(uri, diagnostics)
            );

            context.close();
        } catch (Exception e) {
            // parsing 例外処理
        }
    }

    private Parser createYourLanguageParser() {
        // 言語の root parser を返す
        return Parser.get(YourLanguageParser.class);
    }

    private Range createRange(ParseContext context) {
        // Unlaxer 位置情報を LSP Range に変換
        ParserCursor cursor = context.getTokenStack().peek().getCursor();
        LineNumber line = cursor.lineNumber();
        CodePointIndexInLine column = cursor.positionInLine();

        Position pos = new Position(
            line.value - 1,  // LSP は 0-based
            column.value
        );
        return new Range(pos, pos);
    }
}

// Text Document Service
class UnlaxerTextDocumentService implements TextDocumentService {

    private final UnlaxerLanguageServer server;

    UnlaxerTextDocumentService(UnlaxerLanguageServer server) {
        this.server = server;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem doc = params.getTextDocument();
        server.parseDocument(doc.getUri(), doc.getText(), doc.getVersion());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String content = params.getContentChanges().get(0).getText();
        long version = params.getTextDocument().getVersion();
        server.parseDocument(uri, content, version);
    }

    @Override
    public CompletableFuture<List<CompletionItem>> completion(
            CompletionParams params) {

        String uri = params.getTextDocument().getUri();
        DocumentState doc = server.documents.get(uri);

        if (doc == null || doc.parsed == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        // cursor 位置の token を取得
        Position pos = params.getPosition();
        Token tokenAtCursor = findTokenAtPosition(
            doc.parsed.getRootToken(),
            pos.getLine() + 1,  // 1-based へ変換
            pos.getCharacter()
        );

        // 文脈に応じて補完候補を生成
        List<CompletionItem> items = generateCompletions(tokenAtCursor);

        return CompletableFuture.completedFuture(items);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        DocumentState doc = server.documents.get(uri);

        if (doc == null) {
            return CompletableFuture.completedFuture(null);
        }

        Position pos = params.getPosition();
        Token token = findTokenAtPosition(
            doc.parsed.getRootToken(),
            pos.getLine() + 1,
            pos.getCharacter()
        );

        if (token != null) {
            // hover 情報作成
            String content = String.format(
                "Token: %s\nType: %s\nText: %s",
                token.getParser().getClass().getSimpleName(),
                token.getParser().getClass().getName(),
                token.getConsumedString()
            );

            Hover hover = new Hover();
            hover.setContents(new MarkupContent("markdown", content));
            return CompletableFuture.completedFuture(hover);
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<? extends DocumentSymbol>> documentSymbol(
            DocumentSymbolParams params) {

        String uri = params.getTextDocument().getUri();
        DocumentState doc = server.documents.get(uri);

        if (doc == null || doc.parsed == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        // token tree を document symbols に変換
        List<DocumentSymbol> symbols = extractSymbols(
            doc.parsed.getRootToken()
        );

        return CompletableFuture.completedFuture(symbols);
    }

    private Token findTokenAtPosition(Token root, int line, int character) {
        // token tree を走査して位置にある token を取得
        if (root == null) return null;

        // この token 範囲内か判定
        // （実装は位置追跡方式に依存）

        // 子を再帰探索
        for (Token child : root.getChildren()) {
            Token found = findTokenAtPosition(child, line, character);
            if (found != null) return found;
        }

        return null;
    }

    private List<CompletionItem> generateCompletions(Token context) {
        List<CompletionItem> items = new ArrayList<>();

        // 例: キーワード補完
        CompletionItem item = new CompletionItem("if");
        item.setKind(CompletionItemKind.Keyword);
        item.setDetail("if statement");
        items.add(item);

        // context に応じた補完ロジックを追加

        return items;
    }

    private List<DocumentSymbol> extractSymbols(Token token) {
        List<DocumentSymbol> symbols = new ArrayList<>();

        // 例: 関数定義を抽出
        if (token.getParser() instanceof FunctionDefParser) {
            DocumentSymbol symbol = new DocumentSymbol();
            symbol.setName(extractFunctionName(token));
            symbol.setKind(SymbolKind.Function);
            symbol.setRange(tokenToRange(token));
            symbol.setSelectionRange(tokenToRange(token));
            symbols.add(symbol);
        }

        // 子を再帰処理
        for (Token child : token.getChildren()) {
            symbols.addAll(extractSymbols(child));
        }

        return symbols;
    }

    private Range tokenToRange(Token token) {
        // Unlaxer token range を LSP Range に変換
        // 簡易版
        Position start = new Position(0, token.getRange().start.value);
        Position end = new Position(0, token.getRange().end.value);
        return new Range(start, end);
    }

    private String extractFunctionName(Token token) {
        // 子 token から関数名抽出
        return token.getConsumedString();
    }
}

// Workspace Service
class UnlaxerWorkspaceService implements WorkspaceService {
    private final UnlaxerLanguageServer server;

    UnlaxerWorkspaceService(UnlaxerLanguageServer server) {
        this.server = server;
    }
}
```

### Unlaxer を使った LSP 機能

#### 1. シンタックスハイライト

token type を意味ハイライトに使います:

```java
public SemanticTokens getSemanticTokens(String uri) {
    DocumentState doc = documents.get(uri);
    List<SemanticToken> tokens = new ArrayList<>();

    traverseTokens(doc.parsed.getRootToken(), (token) -> {
        SemanticTokenType type = mapParserToTokenType(
            token.getParser()
        );
        tokens.add(new SemanticToken(
            token.getRange().start.value,
            token.getRange().end.value - token.getRange().start.value,
            type
        ));
    });

    return new SemanticTokens(tokens);
}
```

#### 2. 定義へ移動（Go to Definition）

パース中にシンボル定義を追跡します:

```java
private Map<String, Token> symbolTable = new HashMap<>();

public CompletableFuture<Location> definition(DefinitionParams params) {
    Token token = findTokenAtPosition(...);

    if (token.getParser() instanceof IdentifierParser) {
        String name = token.getConsumedString();
        Token definition = symbolTable.get(name);

        if (definition != null) {
            return CompletableFuture.completedFuture(
                tokenToLocation(definition)
            );
        }
    }

    return CompletableFuture.completedFuture(null);
}
```

#### 3. コード折りたたみ

parser 階層を折りたたみ領域に使います:

```java
public List<FoldingRange> getFoldingRanges(String uri) {
    DocumentState doc = documents.get(uri);
    List<FoldingRange> ranges = new ArrayList<>();

    traverseTokens(doc.parsed.getRootToken(), (token) -> {
        // ブロック・関数・クラス等を fold
        if (isFoldableParser(token.getParser())) {
            ranges.add(tokenToFoldingRange(token));
        }
    });

    return ranges;
}
```

### LSP ベストプラクティス

1. **Incremental Updates**: parse 結果をキャッシュし、変更領域のみ再解析
2. **Error Recovery**: `Optional` / `ZeroOrMore` で堅牢にする
3. **Position Mapping**: Unlaxer の組み込み位置追跡を活用
4. **Symbol Table**: パース時に構築し検索効率を上げる
5. **Async Processing**: 背景スレッドで parse を実行



### 他言語のパーサーコンビネータとの比較

#### Haskell Parsec / Megaparsec

**共通点**:
- モナドベースの合成（Java ではメソッドチェーンで実現）
- バックトラック対応
- エラーレポート

**相違点**:
- Unlaxer: オブジェクト指向、クラスベース parser
- Parsec: 関数型、高階関数中心
- Unlaxer: 明示的 transaction stack
- Parsec: State モナドによる暗黙管理

#### Scala Parser Combinators

**共通点**:
- 演算子ベース合成（`~`, `|`）
- リッチな combinator ライブラリ

**相違点**:
- Unlaxer: 名前付きメソッド（`Chain`, `Choice`）
- Scala: 記号演算子（`~`, `|`, `~>`）
- Unlaxer: 既定でフル parse tree
- Scala: 中間結果を捨てる設計も可能

#### JavaScript/TypeScript Parsimmon / Arcsecond

**共通点**:
- チェーンしやすい fluent API
- 変換用 `.map()`

**相違点**:
- Unlaxer: 状態を持つ ParseContext
- JS ライブラリ: ステートレス parser が中心
- Unlaxer: Java の静的型
- JS ライブラリ: 動的型（または TypeScript）

### パーサージェネレータ（ANTLR, Bison など）との比較

**Parser Combinator の利点**（Unlaxer）:
- 別途文法ファイルが不要
- 文法そのものが実行可能な Java コード
- IDE 支援が全面的に使える（補完、リファクタ、デバッグ）
- 文法内で Java ロジックを直接使える
- カスタム parser で拡張しやすい

**Parser Generator の利点**:
- 既定のエラーメッセージ品質が高いことが多い
- 通常はより高性能（LR/LALR 系）
- 文法そのものがドキュメントになる
- 大規模・複雑文法に向く

**Unlaxer を使うべき場面**:
- 組み込み DSL
- 小〜中規模文法
- プロトタイピング
- Java コードとの密結合が必要
- 文法にも IDE サポートが欲しい

**parser generator を使うべき場面**:
- 大規模・複雑文法
- 最高性能が必要
- 標準言語実装（SQL, JavaScript など）
- 文法ドキュメントを分離管理したい

### Unlaxer の独自機能

1. **RELAX NG 由来の語彙**: XML 開発者に馴染みやすい
2. **transaction ベースのバックトラック**: 明示的で追跡しやすい
3. **scope tree**: 複雑文法向け parser 固有コンテキスト
4. **後方参照**: 文脈依存パースをサポート
5. **meta token 制御**: compact tree / full tree を選択可能
6. **包括的ログ**: パース過程を詳細トレース

## Best Practices

### 1. 終端にはシングルトン Parser を使う

```java
// Good - インスタンスを再利用
Parser digit = Parser.get(DigitParser.class);

// Less efficient - 毎回新規インスタンスを作る
Parser digit = new DigitParser();
```

### 2. 複雑文法では名前付き Parser を使う

```java
Parser ifStmt = new Chain(/* ... */);
ifStmt.setName(new Name("IfStatement"));

// token tree とエラーメッセージで識別しやすい
```

### 3. 再帰には Lazy 評価を使う

```java
// 再帰文法では常に lazy 評価を使う
Supplier<Parser> exprSupplier = () -> {
    return new Choice(
        term,
        new Chain(lparen, Parser.get(exprSupplier), rparen)
    );
};
Parser expr = Parser.get(exprSupplier);
```

### 4. 適切な createMeta モードを選ぶ

```java
// 字句解析/トークナイズ用途 - createMetaOff
ParseContext lexContext = new ParseContext(
    source,
    CreateMetaTokenSpecifier.createMetaOff
);

// AST 構築用途 - createMetaOn
ParseContext astContext = new ParseContext(
    source,
    CreateMetaTokenSpecifier.createMetaOn
);
```

### 5. 段階的にテストする

```java
// 文法レベルごとに個別テスト
@Test
public void testTerm() {
    Parser term = createTermParser();
    // term 単体をテスト
}

@Test
public void testExpression() {
    Parser expr = createExpressionParser();
    // 式全体をテスト
}
```

## Requirements

- Java 17 以上

## Building

```bash
./mvnw clean install
```

## Testing

```bash
./mvnw test
```

## License

MIT License

Copyright (c) 2025

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Resources

- [Maven Central Repository](https://search.maven.org/search?q=g:org.unlaxer)
- [RELAX NG Specification](http://relaxng.org/)

## Contributing

コントリビューション歓迎です。Issue と Pull Request をぜひ送ってください。

## Author

RELAX NG のエレガントなスキーマ言語に着想を得て作成されました。
