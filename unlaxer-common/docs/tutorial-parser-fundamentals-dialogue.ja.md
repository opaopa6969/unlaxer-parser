[English](./tutorial-parser-fundamentals-dialogue.en.md) | [日本語](./tutorial-parser-fundamentals-dialogue.ja.md)

---

# unlaxer-parser チュートリアル: パーサーの基礎から実践まで

> 会話劇形式で学ぶ Parser Combinator の世界
>
> 登場人物:
> - **先輩** -- unlaxer-parser の作者。パーサー理論に精通し、時にユーモアを交える
> - **後輩** -- 聡明だがパーサー理論は初めて。素朴な疑問を次々と投げかける

---

## 目次

- [Part 1: パーサーとは何か](#part-1-パーサーとは何か)
- [Part 2: パース手法の世界](#part-2-パース手法の世界)
- [Part 3: Parser Combinator の考え方](#part-3-parser-combinator-の考え方)
- [Part 4: Terminal Parsers（端末パーサー）](#part-4-terminal-parsers端末パーサー)
- [Part 5: Combinator Parsers（結合子）](#part-5-combinator-parsers結合子)
- [Part 6: 左結合・右結合・演算子優先順位](#part-6-左結合右結合演算子優先順位)
- [Part 7: Token と Parse Tree](#part-7-token-と-parse-tree)
- [Part 8: 自分のパーサーを作る](#part-8-自分のパーサーを作る)
- [Part 9: AST フィルタリングとスコープ](#part-9-ast-フィルタリングとスコープ)
- [Part 10: エラーハンドリングとデバッグ](#part-10-エラーハンドリングとデバッグ)
- [Part 11: 応用 -- UBNF への道](#part-11-応用--ubnf-への道)
- [Part 12: 高度なパーサー -- 知られざるクラスたち](#part-12-高度なパーサー--知られざるクラスたち)
- [Appendix A: パーサー用語集](#appendix-a-パーサー用語集)
- [Appendix B: unlaxer-parser 全パーサー一覧](#appendix-b-unlaxer-parser-全パーサー一覧)

---

## Part 1: パーサーとは何か

[次: Part 2 パース手法の世界 →](#part-2-パース手法の世界)

---

**後輩**: 先輩、今日からパーサーについて勉強したいんですけど、そもそもパーサーって何ですか？ プログラムの中で文字列を処理するだけなら `String.split()` とか正規表現で十分じゃないですか？

**先輩**: いい質問だね。まず「パーサー (parser)」の本質を一言で言うと、**文字列を構造化データに変換する**プログラムのことだよ。

**後輩**: 構造化データ？ JSON みたいなものですか？

**先輩**: そう、JSON はまさにいい例だ。たとえば次の文字列を考えてみよう。

```
3 + 4 * 2
```

**先輩**: これを人間が見れば「3 に、4かける2 の結果を足す」と分かる。でもコンピュータにとっては、最初はただのバイト列だ。パーサーの仕事は、この平坦な文字列から**木構造 (tree)** を取り出すことなんだ。

```
      (+)
      / \
     3  (*)
        / \
       4   2
```

**後輩**: なるほど。演算子の優先順位まで考慮した木になるんですね。`*` が先に計算されるから、`*` のほうが木の深いところにある。

**先輩**: その通り。この木を「構文木」あるいは「パースツリー (parse tree)」と呼ぶ。パーサーがないと、プログラミング言語も、設定ファイルも、SQLも、HTMLも、何もかも処理できない。

---

### 正規表現の限界

**後輩**: でも先輩、正規表現でも結構複雑なパターンマッチングができますよね？ メールアドレスの検証とか。

**先輩**: 確かに正規表現は強力だ。でも決定的な弱点がある。**ネスト構造を扱えない**んだ。

**後輩**: ネスト構造？

**先輩**: たとえば括弧の対応を考えてみよう。

```
((1 + 2) * (3 + 4))
```

**先輩**: 正規表現で「正しく対応する括弧」を検出できるかい？

**後輩**: えっと... `\(.*\)` だと最初の `(` と最後の `)` にマッチするだけで、中のネストは分からないですね。

**先輩**: そう。正規表現は「有限オートマトン (finite automaton)」に基づいている。有限オートマトンには「スタック」がないから、括弧の深さを数えられない。理論的に、正規言語はネストされた構造を認識できないことが証明されている。

**後輩**: じゃあ、HTMLのタグのネストとかも正規表現では無理なんですか？

**先輩**: その通り。有名なStack Overflowの回答で「HTMLを正規表現でパースしてはいけない」というのがあるけど、あれは冗談じゃなくて理論的な根拠があるんだ。正規表現で処理できるのは「正規言語 (regular language)」だけ。括弧のマッチングや再帰的な構造は「文脈自由言語 (context-free language)」で、これには別のツールが必要になる。

**後輩**: その「別のツール」がパーサーなんですね。

**先輩**: 正確に言うと、パーサーは文脈自由文法に基づいて動作する。正規表現の上位互換だと思ってもらっていい。正規表現でできることはパーサーでも全てできるし、パーサーにはさらに再帰やネストを扱う力がある。

---

### 文脈自由文法 (CFG) と BNF 記法

**後輩**: 「文脈自由文法」って名前だけは聞いたことがあります。具体的にはどういうものですか？

**先輩**: 文脈自由文法 (Context-Free Grammar、略して CFG) は、言語の構造を形式的に定義する方法だ。4つの要素からなる。

1. **端末記号 (terminal symbols)** -- 実際の文字やトークン。例: `+`, `-`, `0`~`9`
2. **非端末記号 (non-terminal symbols)** -- 構造を表す名前。例: `Expression`, `Term`, `Factor`
3. **生成規則 (production rules)** -- 非端末記号を端末記号や他の非端末記号で置き換えるルール
4. **開始記号 (start symbol)** -- 文法全体の入り口となる非端末記号

**後輩**: 抽象的でちょっとピンときません...

**先輩**: じゃあ具体例で見せよう。足し算と掛け算ができる電卓の文法を BNF (Backus-Naur Form) 記法で書くとこうなる。

```bnf
<expression> ::= <term> (('+' | '-') <term>)*
<term>       ::= <factor> (('*' | '/') <factor>)*
<factor>     ::= NUMBER | '(' <expression> ')'
```

**後輩**: おお、なんか見覚えがあります。`::=` は「～は～として定義される」という意味ですか？

**先輩**: そう。左辺の非端末記号が、右辺のパターンに展開される。`|` は「または」、`*` は「0回以上の繰り返し」。大事なのは、`<factor>` の定義の中に `<expression>` が出てくるところだ。

**後輩**: あ、再帰してる！ `expression` → `term` → `factor` → `(expression)` って、循環してますね。

**先輩**: それこそが文脈自由文法の力だ。この再帰があるから、`((1 + 2) * (3 + (4 * 5)))` のような任意の深さのネストが表現できる。正規表現にはこれができない。

**後輩**: BNF以外にも似たような記法はあるんですか？

**先輩**: あるよ。EBNF (Extended BNF) はBNFを拡張して、繰り返し `{...}` やオプション `[...]` を直接書けるようにしたものだ。ISO/IEC 14977で標準化されている。unlaxer-parser が内部で使っている UBNF (Unlaxer BNF) もEBNFの一種と言える。

```ebnf
expression = term , { ("+" | "-") , term } ;
term       = factor , { ("*" | "/") , factor } ;
factor     = number | "(" , expression , ")" ;
```

---

### パーサーの歴史

**後輩**: パーサーっていつ頃から使われているんですか？

**先輩**: パーサーの歴史は意外と長い。コンピュータサイエンスの黎明期にまで遡る。

**後輩**: そんなに古いんですか。

**先輩**: 1950年代にノーム・チョムスキーが形式言語理論を体系化した。彼の「チョムスキー階層」が、正規言語と文脈自由言語の違いを明確にした。1960年代に入ると、プログラミング言語のコンパイラを作るために、パーサーの自動生成ツールが生まれてくる。

主要なマイルストーンを挙げると:

- **1965年**: Donald Knuth が LR パーサーの理論を発表
- **1975年**: Stephen Johnson が **yacc** (Yet Another Compiler Compiler) を開発。UNIX上で最初の実用的なパーサージェネレータ
- **1985年**: **GNU Bison** が yacc の互換ツールとして登場
- **1992年**: Terence Parr が **ANTLR** (ANother Tool for Language Recognition) を開発開始。LL(k) パーサーを生成
- **2004年**: Bryan Ford が **PEG** (Parsing Expression Grammar) を提唱
- **2013年**: ANTLR4 が ALL(*) アルゴリズムを導入

**後輩**: yacc は名前だけ聞いたことがあります。「Yet Another Compiler Compiler」って、すでに他のツールがあったんですか？

**先輩**: そう、compiler-compiler (コンパイラのコンパイラ) という概念自体は yacc 以前からあった。でも yacc が最も広く使われた。C言語の文法定義にも使われたし、多くのUNIXツールが yacc で書かれたパーサーを使っている。

**後輩**: ANTLR は Java の世界ではよく聞きますね。

**先輩**: ANTLR は教育でも産業でも広く使われている。Java、Python、C# など多くの言語のパーサーを生成できる。ただし ANTLR はパーサージェネレータで、文法定義ファイル (.g4) からパーサーのソースコードを自動生成するアプローチだ。unlaxer-parser はこれとは違うアプローチを取っている。それについては Part 3 で詳しく話そう。

---

### 構文木 (Parse Tree) vs 抽象構文木 (AST)

**後輩**: さっき「構文木」という言葉が出ましたけど、AST (Abstract Syntax Tree) とは違うんですか？

**先輩**: いい質問だ。この違いは重要だから、しっかり理解しておこう。

**構文木 (Parse Tree / Concrete Syntax Tree / CST)** は、文法のルールを**そのまま**反映した木だ。文法に登場するすべての記号がノードとして現れる。

**後輩**: 「そのまま」というのは？

**先輩**: 例を見せよう。`3 + 4 * 2` を先ほどの文法でパースした場合の構文木はこうなる。

```
Expression
├── Term
│   └── Factor
│       └── NUMBER: "3"
├── "+"
├── Term
│   ├── Factor
│   │   └── NUMBER: "4"
│   ├── "*"
│   └── Factor
│       └── NUMBER: "2"
```

**先輩**: 一方、**抽象構文木 (AST)** は、構文木から**意味に関係ない情報を省いた**木だ。括弧や区切り文字、中間的なルールノードなど、計算に不要な部分を取り除く。

```
BinaryOp(+)
├── Number(3)
└── BinaryOp(*)
    ├── Number(4)
    └── Number(2)
```

**後輩**: あ、ASTのほうがすっきりしてますね。`Expression` とか `Term` とか `Factor` という中間ノードがなくなって、演算子と数値だけになっている。

**先輩**: その通り。ASTは「何を計算するか」という**意味 (semantics)** に集中した表現だ。CST は「どう書かれていたか」という**構文 (syntax)** をそのまま保持した表現。

| 観点 | CST (構文木) | AST (抽象構文木) |
|------|-------------|----------------|
| 情報量 | 全ての構文情報を保持 | 意味に必要な情報のみ |
| ノード数 | 多い | 少ない |
| 括弧・区切り文字 | ノードとして存在 | 省略される |
| 用途 | フォーマッタ、リファクタリング | 評価、コード生成 |

**後輩**: どちらを使うべきなんですか？

**先輩**: 目的による。コードの評価 (実行) やコード生成なら AST で十分。でも、ソースコードのフォーマット (インデント整形) やリファクタリングツールを作るなら、空白や括弧の情報も必要だから CST が必要になる。

**先輩**: unlaxer-parser は面白いアプローチを取っていて、まず CST (構文木) を生成して、そこから `filteredChildren` というフィルタリング機構で AST に相当するビューを取り出せるようになっている。つまり、**1つのパース結果から CST と AST の両方にアクセスできる**んだ。

**後輩**: 一石二鳥ですね！ どうやってフィルタリングするんですか？

**先輩**: `ASTNode` と `NotASTNode` というマーカーを使う。これについては Part 9 で詳しく説明するよ。

**後輩**: 楽しみです。でもまず、パーサーの全体像をもう少し掴みたいです。

**先輩**: よし、じゃあ次はパースの手法の世界を見ていこう。

---

[← 目次に戻る](#目次) | [次: Part 2 パース手法の世界 →](#part-2-パース手法の世界)

---

## Part 2: パース手法の世界

[← Part 1: パーサーとは何か](#part-1-パーサーとは何か) | [次: Part 3 Parser Combinator →](#part-3-parser-combinator-の考え方)

---

**後輩**: 先輩、パーサーにはいろんな種類があるって聞いたんですけど、どういう分類があるんですか？

**先輩**: 大きく分けて2つの流派がある。**トップダウン (top-down)** と **ボトムアップ (bottom-up)** だ。

---

### トップダウン vs ボトムアップ

**後輩**: トップダウンとボトムアップ？ 経営用語みたいですね。

**先輩**: まさにその比喩がぴったりだ。

**トップダウンパーサー** は、文法の開始記号（一番上のルール）から出発して、入力文字列に向かって下に展開していく。「この文字列は Expression だろう」→「Expression は Term からなるはずだ」→「Term は Factor からなるはずだ」→「Factor は数字のはずだ」→ 実際の文字を確認、という流れ。

**ボトムアップパーサー** は逆に、入力文字列の個々の文字やトークンから出発して、それらを文法のルールに従って上に集約していく。「`3` は NUMBER だ」→「NUMBER は Factor だ」→「Factor は Term だ」→ ...

**後輩**: 直感的にはトップダウンのほうが分かりやすそうですね。

**先輩**: その通り。トップダウンパーサーは人間の思考に近いから、手で書きやすい。一方、ボトムアップパーサーは扱える文法の範囲が広いという利点がある。

---

### 再帰下降パーサー (Recursive Descent)

**後輩**: 「手で書きやすい」パーサーってどんなものですか？

**先輩**: 最も基本的なトップダウンパーサーが**再帰下降パーサー (recursive descent parser)** だ。文法の各ルールを1つの関数として実装する。

```java
// <expression> ::= <term> (('+' | '-') <term>)*
double parseExpression() {
    double result = parseTerm();
    while (currentChar() == '+' || currentChar() == '-') {
        char op = currentChar();
        advance();
        double right = parseTerm();
        if (op == '+') result += right;
        else result -= right;
    }
    return result;
}

// <term> ::= <factor> (('*' | '/') <factor>)*
double parseTerm() {
    double result = parseFactor();
    while (currentChar() == '*' || currentChar() == '/') {
        char op = currentChar();
        advance();
        double right = parseFactor();
        if (op == '*') result *= right;
        else result /= right;
    }
    return result;
}

// <factor> ::= NUMBER | '(' <expression> ')'
double parseFactor() {
    if (currentChar() == '(') {
        advance(); // skip '('
        double result = parseExpression();
        expect(')');
        return result;
    }
    return parseNumber();
}
```

**後輩**: あ、本当に文法のルールがそのまま関数になってますね！ `parseFactor()` の中で `parseExpression()` を呼んでいるのが再帰ですか？

**先輩**: そう。文法が再帰的だから、パーサーも再帰的になる。これが「再帰下降」の名前の由来だ。文法の各非端末記号に対応する関数を用意して、お互いを呼び合う。

**後輩**: これなら自分でも書けそうです。

**先輩**: 実際、多くの実用的なパーサーがこの方式で書かれている。GCC の C++ パーサーも、V8 (JavaScriptエンジン) のパーサーも、再帰下降ベースだ。シンプルで、エラーメッセージも出しやすい。

---

### LL(k) パーサー

**後輩**: LL(k) パーサーって何ですか？ L が2つあるのは何か意味が？

**先輩**: LL(k) の意味はこうだ。

- 最初の **L**: 入力を **L**eft to right (左から右へ) 読む
- 2番目の **L**: **L**eftmost derivation (左端導出) を行う
- **(k)**: 先読み (lookahead) として **k** 個のトークンを見る

**後輩**: 左端導出って何ですか？

**先輩**: 文法のルールを適用するとき、常に一番左の非端末記号を先に展開していく方法だ。トップダウンパーサーは基本的にこの方式。

**後輩**: k 個の先読みというのは？

**先輩**: たとえば LL(1) パーサーは、次の1文字 (または1トークン) だけを見て、どのルールを適用するか決める。LL(2) なら2文字先まで見る。

```
// LL(1) の判断例
// 現在位置の次の文字が '(' なら factor → '(' expression ')' を選ぶ
// 数字なら factor → NUMBER を選ぶ
```

**先輩**: ANTLR は元々 LL(k) パーサーを生成するツールだった。ANTLR4 では ALL(*) という改良版になって、必要に応じて何文字でも先読みできるようになった。

**後輩**: 先読みの数が多いほど強力なんですか？

**先輩**: 一般的にはそう。でも先読みを増やすと計算量も増える。LL(1) で十分な文法なら LL(1) を使うのが効率的だ。

---

### LR パーサー

**後輩**: ボトムアップのほうの代表的なパーサーは何ですか？

**先輩**: **LR パーサー** だ。

- **L**: 入力を **L**eft to right に読む
- **R**: **R**ightmost derivation (右端導出) を逆順に行う

**後輩**: さっきと最初の L は同じで、R が違うんですね。右端導出を「逆順に」？

**先輩**: ボトムアップだからね。実際にはルールを「適用」するのではなく「還元 (reduce)」する。LR パーサーの核心は **シフト・還元 (shift-reduce)** というメカニズムだ。

**後輩**: シフト・還元？

**先輩**: スタックを使う。2つの操作を繰り返す:

1. **シフト (shift)**: 入力からトークンを1つ読んでスタックに積む
2. **還元 (reduce)**: スタックの上にある要素が文法のルールの右辺と一致したら、左辺の非端末記号に置き換える

```
入力: 3 + 4 * 2

ステップ  スタック        入力        アクション
1         (空)           3 + 4 * 2   シフト
2         3              + 4 * 2     還元: 3 → Factor → Term
3         Term           + 4 * 2     シフト
4         Term +         4 * 2       シフト
5         Term + 4       * 2         還元: 4 → Factor
6         Term + Factor  * 2         シフト (還元しない！ * のほうが優先)
7         Term + Factor *  2         シフト
8         Term + Factor * 2          還元: 2 → Factor
9         Term + Factor * Factor     還元: Factor * Factor → Term
10        Term + Term                還元: Term + Term → Expression
11        Expression                 完了！
```

**後輩**: おおー、スタックで管理するんですね。でもステップ5で「還元しない」って判断はどうやるんですか？

**先輩**: いい疑問だ。LR パーサーは**パーサーテーブル**という大きな表を事前に計算しておく。このテーブルが「今のスタック状態と次の入力トークンの組み合わせで、シフトするか還元するか」を教えてくれる。

**先輩**: yacc や Bison がまさにこのパーサーテーブルを自動生成するツールだ。文法定義を入れると、テーブルを含むC言語のパーサーコードが出力される。

**後輩**: テーブルを事前に計算するから高速なんですね。

**先輩**: そう。LR パーサーは線形時間 O(n) で動作する。ただし、テーブルが巨大になりがちで、デバッグが困難という欠点がある。yacc が出すエラーメッセージ「shift/reduce conflict」に悩まされた開発者は数知れない。

---

### PEG (Parsing Expression Grammar)

**後輩**: PEG ってよく聞くんですけど、CFG とは違うんですか？

**先輩**: PEG (Parsing Expression Grammar) は 2004年に Bryan Ford が提唱した文法形式だ。CFG と似ているけど、決定的な違いがある。**順序付き選択 (ordered choice)** だ。

**後輩**: 順序付き選択？

**先輩**: CFG の選択 `A | B` は「A でも B でもいい」という**曖昧な**選択だ。入力がAにもBにもマッチする場合、どちらを選ぶかは文法だけでは決まらない。

一方、PEG の選択 `A / B` は「**まず A を試し、A が失敗したら B を試す**」という順序がある。A が成功したら B は試さない。

```
# CFG の選択 (曖昧)
A | B    ... 入力が両方にマッチしたらどうする？

# PEG の選択 (順序付き)
A / B    ... まず A を試す。成功したら B は見ない。
```

**後輩**: なるほど、PEG なら常に決定的なんですね。曖昧さがない。

**先輩**: その通り。PEG のもう1つの特徴は**バックトラック (backtracking)** だ。A を試してみて失敗したら、読み進めた位置を元に戻して B を試す。

**後輩**: バックトラックって、性能的に大丈夫なんですか？ 最悪のケースでは指数時間になりそうですけど...

**先輩**: 鋭い。素朴なバックトラックだと確かにそうなる。そこで登場するのが **Packrat パーサー** だ。

---

### Packrat Parsing -- メモ化で線形時間を保証

**先輩**: Packrat parsing は PEG のバックトラックを効率化する手法で、Ford 自身が提案した。アイデアは単純で、**メモ化 (memoization)** だ。

**後輩**: メモ化って、動的計画法で使うあれですか？ 一度計算した結果をキャッシュしておく。

**先輩**: そう。各パーサーが各位置で試行した結果を記録しておく。同じパーサーが同じ位置で再度呼ばれたら、キャッシュから結果を返す。

```
位置0: ExpressionParser → 成功(consumed=7) [キャッシュ]
位置0: TermParser → 成功(consumed=1) [キャッシュ]
位置2: TermParser → 成功(consumed=5) [キャッシュ]
...
```

**後輩**: これで各位置・各パーサーの組み合わせは最大1回しか計算されないから、全体で O(n * m) (n=入力長、m=パーサー数) で、入力長に対して線形ですね。

**先輩**: 正確にはメモリ消費が O(n * m) になるというトレードオフがある。メモリを多く使う代わりに時間を線形に抑える。

**後輩**: unlaxer-parser はメモ化を使っているんですか？

**先輩**: `ParseContext` に `doMemoize` というフラグがあって、メモ化を有効にできるようになっている。ただし全てのケースでメモ化が有効とは限らないので、オプションとして用意している形だね。

---

### GLR パーサー

**後輩**: GLR って何ですか？

**先輩**: **GLR (Generalized LR)** パーサーは、LR パーサーの拡張で、**曖昧な文法**も扱える。LR パーサーでは「shift/reduce conflict」が起きたら文法を修正するしかないけど、GLR ではパーサーが「分裂」して両方の可能性を並行して追跡する。

**後輩**: 並行して追跡？ パーサーが分身するみたいですね。

**先輩**: いい表現だ。内部的にはグラフ構造のスタック (Graph-Structured Stack, GSS) を使って、複数の解析パスを効率的に管理する。C++ のような曖昧な構文を持つ言語のパーサーには GLR が使われることがある。

**後輩**: なんだかパーサーの世界って奥が深いですね...

---

### unlaxer-parser はなぜ PEG ベースなのか

**後輩**: で、unlaxer-parser はどの方式なんですか？

**先輩**: unlaxer-parser は **PEG ベースの Parser Combinator** だ。

**後輩**: なんで PEG を選んだんですか？

**先輩**: 理由はいくつかある。

**1. 決定的で曖昧さがない**

PEG の順序付き選択により、パース結果が常に一意に決まる。「この文法は曖昧ではないか？」と心配する必要がない。

**2. 直感的に理解しやすい**

「まず A を試して、ダメなら B」というのは人間の思考に自然に近い。

**3. Parser Combinator との相性が良い**

PEG の各構成要素（順序付き選択、連接、繰り返し、先読み、否定先読み）が、そのまま Java のクラスとして実装できる。

**4. パーサージェネレータが不要**

ANTLR や yacc のように文法ファイルからコードを生成する必要がない。Java のコードとして直接パーサーを書ける。IDE の補完やリファクタリングがそのまま使える。

**5. 段階的に拡張できる**

小さなパーサーから始めて、必要に応じて組み合わせて大きくしていける。

**後輩**: メリットはよく分かりました。デメリットはないんですか？

**先輩**: もちろんある。

- **左再帰 (left recursion)** が直接書けない。これは Part 6 で詳しく説明する
- バックトラックのコストがある（メモ化で軽減可能）
- CFG で表現できるが PEG で表現できない言語が理論的に存在する（実用上はほぼ問題にならない）

**後輩**: 左再帰が書けないのは大きそうですけど...

**先輩**: 実用的な回避方法があるから安心して。unlaxer-parser では繰り返しパターン (`ZeroOrMore`) を使って自然に表現できるようになっている。

---

### パース手法の比較表

| 手法 | 方向 | 文法 | 曖昧さ | 計算量 | 代表的ツール |
|------|------|------|--------|--------|-------------|
| 再帰下降 | トップダウン | LL相当 | なし | ケースによる | 手書き |
| LL(k) | トップダウン | LL(k) | なし | O(n) | ANTLR |
| LR | ボトムアップ | LR(k) | なし | O(n) | yacc, Bison |
| PEG | トップダウン | PEG | なし (順序付き) | O(n) (Packrat) | unlaxer, PEG.js |
| GLR | ボトムアップ | CFG全般 | 許容 | O(n^3) 最悪 | Elkhound, Tree-sitter |
| Earley | どちらでもない | CFG全般 | 許容 | O(n^3) 最悪 | MARPA |

**後輩**: こうして比較すると、PEG は「トップダウンの分かりやすさ」と「線形時間の効率」を両立しているんですね。

**先輩**: そう。unlaxer-parser は PEG の利点を活かしつつ、Java の型システムを使って安全にパーサーを組み立てられるようにした Parser Combinator だ。次はその Parser Combinator の考え方を見ていこう。

---

[← Part 1: パーサーとは何か](#part-1-パーサーとは何か) | [次: Part 3 Parser Combinator →](#part-3-parser-combinator-の考え方)

---

## Part 3: Parser Combinator の考え方

[← Part 2: パース手法の世界](#part-2-パース手法の世界) | [次: Part 4 Terminal Parsers →](#part-4-terminal-parsers端末パーサー)

---

**後輩**: 先輩、「Parser Combinator」って何ですか？ パーサーを組み合わせる...コンビネータ？

**先輩**: そう、直訳すると「パーサー結合子」だ。アイデアはシンプルで、**小さなパーサーを部品として作り、それらを組み合わせて大きなパーサーを構成する**という手法だ。

**後輩**: レゴブロックみたいな感じですか？

**先輩**: まさにその通り。レゴの個々のブロックが「端末パーサー (terminal parser)」で、ブロック同士をつなげるルールが「コンビネータ (combinator)」だ。

---

### 起源: Haskell の Parsec

**後輩**: Parser Combinator はいつ頃から使われているんですか？

**先輩**: 概念自体は1990年代に関数型プログラミングの世界で発展した。特に有名なのが **Parsec** だ。Haskell で書かれた Parser Combinator ライブラリで、Daan Leijen が2001年に発表した。

**後輩**: Haskell って関数型言語ですよね。Parser Combinator と関数型は何か関係があるんですか？

**先輩**: 深い関係がある。関数型プログラミングでは「関数を値として扱う」のが自然だ。パーサーも関数の一種だから、パーサーを引数に取ってパーサーを返す「高階関数」が簡単に書ける。

```haskell
-- Haskell (Parsec) の例
expr :: Parser Double
expr = do
  t <- term
  rest t
  where
    rest acc = (do char '+'; t <- term; rest (acc + t))
           <|> (do char '-'; t <- term; rest (acc - t))
           <|> return acc
```

**後輩**: `<|>` が選択のコンビネータですか？

**先輩**: そう。Parsec では `<|>` が「A を試して失敗したら B を試す」という PEG の順序付き選択に相当する。`do` 記法で順接 (Chain) を表現する。

---

### Scala の Parser Combinator

**後輩**: Scala にも似たようなものがありますよね？

**先輩**: Scala は標準ライブラリに Parser Combinator が含まれていた時期がある（後に分離された）。Scala はJVM上の言語で、演算子オーバーロードがサポートされているから、Haskell に近い見た目で書ける。

```scala
// Scala の例
def expr: Parser[Double] = term ~ rep("+" ~ term | "-" ~ term) ^^ { ... }
def term: Parser[Double] = factor ~ rep("*" ~ factor | "/" ~ factor) ^^ { ... }
def factor: Parser[Double] = number | "(" ~> expr <~ ")"
```

**後輩**: `~` が順接で、`|` が選択、`rep` が繰り返しですか？

**先輩**: その通り。`~` は Chain に、`|` は Choice に、`rep` は ZeroOrMore に対応する。unlaxer-parser の設計はこれらの概念をJavaで実現したものと言える。

---

### Java での実現 -- unlaxer の設計

**後輩**: でも Java にはHaskellやScalaのような演算子オーバーロードがないですよね。どうやって Parser Combinator を実現するんですか？

**先輩**: Java では**クラスの継承とコンポジション**を使う。各コンビネータをクラスとして定義して、コンストラクタで子パーサーを受け取る。

```java
// Haskell:  expr <|> term
// Scala:    expr | term
// unlaxer:  new Choice(exprParser, termParser)

// Haskell:  a >> b >> c
// Scala:    a ~ b ~ c
// unlaxer:  new Chain(aParser, bParser, cParser)

// Haskell:  many a
// Scala:    rep(a)
// unlaxer:  new ZeroOrMore(aParser)
```

**後輩**: Java だと見た目はちょっと冗長ですけど、やっていることは同じなんですね。

**先輩**: そう。見た目の簡潔さでは Haskell に劣るけど、Java の利点もある。

1. **IDE サポート** -- IntelliJ や Eclipse で補完、リファクタリング、デバッグが使える
2. **型安全** -- コンパイル時にパーサーの構成ミスを検出できる
3. **パフォーマンス** -- JVM の最適化が効く
4. **エコシステム** -- Java の膨大なライブラリと組み合わせられる

---

### Parser.get() -- シングルトンでパーサーを取得

**後輩**: unlaxer-parser のコードを見ていると `Parser.get(SomeParser.class)` というパターンをよく見かけるんですが、これは何ですか？

**先輩**: unlaxer-parser では、パーサーは基本的に**シングルトン**として管理される。`Parser.get()` はパーサーのインスタンスを取得するファクトリメソッドだ。

```java
// パーサーのシングルトンを取得
NumberParser numberParser = Parser.get(NumberParser.class);

// 内部的には ParserFactoryByClass が管理
public static <T extends Parser> T get(Class<T> clazz) {
    return ParserFactoryByClass.get(clazz);
}
```

**後輩**: なぜシングルトンなんですか？ 毎回 `new` しちゃダメなんですか？

**先輩**: 理由は2つある。

**1. メモリ効率**: パーサーは状態を持たない (stateless) ことが多い。同じ文法ルールに対して何度もインスタンスを作る必要がない。

**2. 循環参照の解決**: これが重要な理由だ。`Expression` → `Factor` → `(Expression)` のような循環参照がある場合、`new` でインスタンスを作ると無限ループになる。シングルトンにしておけば、すでに作成済みのインスタンスが返されるから循環を断ち切れる。

**後輩**: あ、なるほど！ 文法の再帰構造に対応するために必要なんですね。

**先輩**: ただし、`Parser.newInstance()` というメソッドもあって、これは意図的に新しいインスタンスを作りたいときに使う。tinyexpression の `AbstractNumberFactorParser` で括弧の中身を定義するときに使われている。

```java
// AbstractNumberFactorParser.java
parsers.add(new ParenthesesParser(
    Parser.newInstance(expresionParserClazz)
));
```

---

### Lazy vs Constructed -- なぜ遅延評価が必要か

**後輩**: `LazyChain` と `Chain`、`LazyChoice` と `Choice` って、それぞれ何が違うんですか？ 先輩のコードには `Lazy` 版がたくさんあるんですが。

**先輩**: 核心的な違いは**子パーサーの初期化タイミング**だ。

**`Chain` (Constructed版)** は、コンストラクタで子パーサーを**即座に**受け取る:

```java
// 即座に子パーサーが決まる
new Chain(parserA, parserB, parserC)
```

**`LazyChain` (Lazy版)** は、子パーサーの取得を **遅延** する。`getLazyParsers()` メソッドが呼ばれるまで子パーサーは作成されない:

```java
public class MyParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        // ここで初めて子パーサーを構築
        return new Parsers(
            Parser.get(ParserA.class),
            Parser.get(ParserB.class)
        );
    }
}
```

**後輩**: なぜ遅延が必要なんですか？

**先輩**: さっき話した**循環参照**の問題だ。電卓の文法を思い出してほしい:

```
Expression → Term → Factor → '(' Expression ')'
```

もし全てのパーサーがコンストラクタで子パーサーを要求したら:
1. `ExpressionParser` を作ろうとする → `TermParser` が必要
2. `TermParser` を作ろうとする → `FactorParser` が必要
3. `FactorParser` を作ろうとする → `ExpressionParser` が必要
4. `ExpressionParser` を作ろうとする → ... (無限ループ！)

**後輩**: あ、鶏と卵の問題ですね！

**先輩**: `LazyChain` や `LazyChoice` を使えば、パーサーオブジェクト自体は先に作成できる。子パーサーの参照解決は後から（実際にパースが始まるときに）行われる。

```
1. ExpressionParser のインスタンスを作成 (子パーサーはまだ未解決)
2. TermParser のインスタンスを作成 (子パーサーはまだ未解決)
3. FactorParser のインスタンスを作成 (子パーサーはまだ未解決)
4. パース開始時に getLazyParsers() が呼ばれ、相互参照が解決
```

**後輩**: なるほど、だから tinyexpression のパーサーは全部 `LazyChain` や `LazyChoice` を継承しているんですね。

**先輩**: 一方で、循環参照がない場合（例えば `Chain(wordA, wordB)` のような単純なケース）は `Constructed` 版を使っても問題ない。実際、`AbstractNumberExpressionParser` の中で `ZeroOrMore` や `WhiteSpaceDelimitedChain` は `new` で直接作られている。

```java
// AbstractNumberExpressionParser.java
parsers.add(new ZeroOrMore(
    new WhiteSpaceDelimitedChain(
        new Choice(
            Parser.get(PlusParser.class),
            Parser.get(MinusParser.class)
        ),
        Parser.get(termParserClazz)
    )
));
```

**後輩**: 循環する可能性がある「外枠」は Lazy で、循環しない「中身」は Constructed でもOK、ということですね。

**先輩**: 完璧な理解だ。

---

[← Part 2: パース手法の世界](#part-2-パース手法の世界) | [次: Part 4 Terminal Parsers →](#part-4-terminal-parsers端末パーサー)

---

## Part 4: Terminal Parsers（端末パーサー）

[← Part 3: Parser Combinator →](#part-3-parser-combinator-の考え方) | [次: Part 5 Combinator Parsers →](#part-5-combinator-parsers結合子)

---

**後輩**: 先輩、端末パーサーって何ですか？

**先輩**: 端末パーサー (terminal parser) は、**文字列を直接マッチングする**最も基本的なパーサーだ。レゴブロックの喩えで言えば、個々のブロックそのものがこれにあたる。他のパーサーを含まず、入力文字列に対して「マッチするか否か」を判定する。

**後輩**: つまり、パーサー階層の「葉」にあたるものですね。

**先輩**: その通り。unlaxer-parser では `TerminalSymbol` インターフェースを実装したクラスが端末パーサーだ。全部で数十種類ある。カテゴリごとに紹介していこう。

---

### WordParser -- リテラル文字列マッチ

**後輩**: 一番基本的なパーサーは何ですか？

**先輩**: `WordParser` だ。指定した文字列と完全に一致するかを判定する。

```java
// "hello" という文字列にマッチするパーサー
WordParser helloParser = new WordParser("hello");

// 大文字小文字を無視するバージョン
WordParser helloIgnoreCase = new WordParser("hello", true);
```

**後輩**: シンプルですね。正規表現の固定文字列マッチみたいなものですか。

**先輩**: そう。`WordParser` は `Source` オブジェクトの中身をコードポイント単位で比較する。内部では `Slicer` クラスを使って効率的に部分文字列を切り出している。

**後輩**: tinyexpression ではどこで使われていますか？

**先輩**: たとえば `PlusParser` や `MinusParser` は WordParser を内部で使っている。`+` や `-` という1文字のリテラルにマッチする。

---

### SingleCharacterParser / MappedSingleCharacterParser -- 1文字マッチ

**後輩**: 1文字だけマッチするパーサーもあるんですか？

**先輩**: ある。`SingleCharacterParser` は抽象クラスで、`isMatch(char target)` メソッドをオーバーライドして使う。

```java
public abstract class SingleCharacterParser extends AbstractTokenParser
    implements TerminalSymbol {

    public abstract boolean isMatch(char target);

    @Override
    public Token getToken(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        Source peeked = parseContext.peek(tokenKind, new CodePointLength(1));
        Token token =
            peeked.isPresent() && (invertMatch ^ isMatch(peeked.charAt(0))) ?
                new Token(tokenKind, peeked, this) :
                Token.empty(tokenKind, parseContext.getCursor(TokenKind.consumed), this);
        return token;
    }
}
```

**後輩**: `invertMatch` って否定マッチですか？ マッチ結果を反転させる？

**先輩**: そう。`invertMatch` が `true` のとき、`isMatch()` が `false` を返す文字にマッチする。Not パーサーと組み合わせて使われる。

**先輩**: `MappedSingleCharacterParser` は `SingleCharacterParser` の変種で、マッチした文字に対して変換 (mapping) を適用できる。

---

### NumberParser -- 数値リテラル

**後輩**: 数値のパースは複雑そうですね。整数だけじゃなくて小数もあるし...

**先輩**: `NumberParser` はまさにその複雑さを吸収してくれるパーサーだ。実装を見てみよう。

```java
public class NumberParser extends LazyChain implements StaticParser {

    static final Parser digitParser = new DigitParser();
    static final Parser signParser = new SignParser();
    static final Parser pointParser = new PointParser();
    static final OneOrMore digitsParser = new OneOrMore(Name.of("any-digit"), digitParser);

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // + or -
            new Optional(Name.of("optional-signParser"), signParser),
            new Choice(
                // 12.3
                new Chain(Name.of("digits-point-digits"), digitsParser, pointParser, digitsParser),
                // 12.
                new Chain(Name.of("digits-point"), digitsParser, pointParser),
                // 12
                new Chain(Name.of("digits"), digitsParser),
                // .3
                new Chain(Name.of("point-digits"), pointParser, digitsParser)
            ),
            // e-3
            new Optional(ExponentParser.class)
        );
    }
}
```

**後輩**: おおー、これ自体がコンビネータで構成されてますね！ 端末パーサーとは言いつつ、内部構造がある。

**先輩**: いい観察だ。`NumberParser` は技術的には `LazyChain` を継承しているから非端末パーサーなんだけど、利用者から見れば「数値リテラルにマッチする1つの部品」として使える。パーサーの抽象化の力だね。

**後輩**: マッチする形式をまとめると:
- `+3.14` (符号付き小数)
- `-42` (負の整数)
- `12.` (整数＋ドット)
- `.5` (ドット＋小数部)
- `1.5e-3` (指数表記)

**先輩**: そう。Choice の順序にも注目してほしい。`digits-point-digits` が `digits-point` より先に来ている。PEG の順序付き選択だから、より長いパターンを先に試す必要がある。もし `digits-point` を先に置くと、`12.3` に対して `12.` だけでマッチしてしまう。

**後輩**: あ、それは大事ですね！ PEG特有の注意点ですか。

**先輩**: そう。Part 10 の「よくあるミス」でも触れるけど、Choice の順序は PEG パーサーを書くときの最も重要な注意点の1つだ。

---

### IdentifierParser (clang/) -- C言語風識別子

**後輩**: 変数名のパースはどうやるんですか？

**先輩**: `clang` パッケージの `IdentifierParser` がC言語スタイルの識別子をパースする。

```java
public class IdentifierParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(AlphabetUnderScoreParser.class),
            new ZeroOrMore(AlphabetNumericUnderScoreParser.class)
        );
    }
}
```

**後輩**: 「英字またはアンダースコアで始まり、英数字またはアンダースコアが0回以上続く」ですね。C/Java/Python 共通のルールだ。

**先輩**: BNF で書くと:

```bnf
<identifier> ::= [a-zA-Z_] [a-zA-Z0-9_]*
```

**先輩**: `AlphabetUnderScoreParser` と `AlphabetNumericUnderScoreParser` はPOSIX系のパーサーで、それぞれ文字の種類を判定する。

---

### SingleQuotedParser / DoubleQuotedParser -- 引用符文字列

**後輩**: 文字列リテラル（ダブルクォートで囲まれたもの）はどうやってパースするんですか？

**先輩**: `DoubleQuotedParser` と `SingleQuotedParser` がある。

```java
// "hello world" のような二重引用符文字列にマッチ
DoubleQuotedParser dqParser = new DoubleQuotedParser();

// 'hello world' のような単一引用符文字列にマッチ
SingleQuotedParser sqParser = new SingleQuotedParser();
```

**後輩**: エスケープ文字（`\"` みたいなの）も扱えますか？

**先輩**: `EscapeInQuotedParser` というパーサーがあって、バックスラッシュによるエスケープシーケンスを処理する。`QuotedParser` が内部でこれを使っている。

---

### EndOfSourceParser / StartOfSourceParser -- 境界

**後輩**: 入力の最初や最後にマッチするパーサーもあるんですか？

**先輩**: ある。正規表現の `^` と `$` に相当する。

```java
// 入力の先頭にマッチ（文字は消費しない）
StartOfSourceParser start = new StartOfSourceParser();

// 入力の末尾にマッチ
EndOfSourceParser end = new EndOfSourceParser();
```

**後輩**: 「文字は消費しない」というのはどういう意味ですか？

**先輩**: パーサーが「マッチした」と判定しても、カーソル位置を進めない。つまり後続のパーサーは同じ位置から読み始める。正規表現のゼロ幅アサーションと同じ概念だ。

---

### POSIX系パーサー (21種)

**後輩**: POSIX系って何ですか？

**先輩**: POSIX文字クラスに対応するパーサー群だ。`org.unlaxer.parser.posix` パッケージに入っている。正規表現の `[:alpha:]` とか `[:digit:]` に相当する。

| パーサー | マッチ対象 | 正規表現相当 |
|---------|----------|-------------|
| `DigitParser` | 数字 0-9 | `[0-9]` |
| `AlphabetParser` | 英字 a-zA-Z | `[a-zA-Z]` |
| `AlphabetNumericParser` | 英数字 | `[a-zA-Z0-9]` |
| `AlphabetUnderScoreParser` | 英字 + _ | `[a-zA-Z_]` |
| `AlphabetNumericUnderScoreParser` | 英数字 + _ | `[a-zA-Z0-9_]` |
| `UpperParser` | 大文字 A-Z | `[A-Z]` |
| `LowerParser` | 小文字 a-z | `[a-z]` |
| `SpaceParser` | 空白文字 | `\s` |
| `BlankParser` | スペース・タブ | `[ \t]` |
| `PunctuationParser` | 句読点記号 | `[[:punct:]]` |
| `ControlParser` | 制御文字 | `[[:cntrl:]]` |
| `GraphParser` | 可視文字 | `[[:graph:]]` |
| `PrintParser` | 印刷可能文字 | `[[:print:]]` |
| `AsciiParser` | ASCII文字 | `[\x00-\x7F]` |
| `XDigitParser` | 16進数字 | `[0-9a-fA-F]` |
| `WordParser` (posix) | 英数字 + _ | `\w` |
| `ColonParser` | コロン : | `:` |
| `CommaParser` | カンマ , | `,` |
| `DotParser` | ドット . | `\.` |
| `HashParser` | ハッシュ # | `#` |
| `SemiColonParser` | セミコロン ; | `;` |

**後輩**: こんなにたくさんあるんですね。全部 `SingleCharacterParser` の派生クラスですか？

**先輩**: 基本的にそう。各パーサーが `isMatch(char target)` をオーバーライドして、対象の文字種かどうかを判定する。

```java
// DigitParser の例
public class DigitParser extends SingleCharacterParser {
    @Override
    public boolean isMatch(char target) {
        return Character.isDigit(target);
    }
}
```

---

### ASCII系パーサー (12種)

**後輩**: ASCII系パーサーはPOSIX系とは別にあるんですか？

**先輩**: `org.unlaxer.parser.ascii` パッケージだ。特定のASCII記号文字にマッチするパーサーが揃っている。

| パーサー | マッチ文字 |
|---------|----------|
| `PlusParser` | `+` |
| `MinusParser` | `-` |
| `PointParser` | `.` |
| `GreaterThanParser` | `>` |
| `LessThanParser` | `<` |
| `EqualParser` | `=` |
| `DivisionParser` | `/` |
| `SlashParser` | `/` |
| `BackSlashParser` | `\` |
| `DoubleQuoteParser` | `"` |
| `LeftParenthesisParser` | `(` |
| `RightParenthesisParser` | `)` |

**後輩**: `DivisionParser` と `SlashParser` って同じ文字 `/` じゃないですか？

**先輩**: そう。名前が違うのは意味的な区別のためだ。数式の中で使うなら `DivisionParser`（除算）、パスの区切りで使うなら `SlashParser`。パーサーの名前はパースツリーのノードにも反映されるから、意味を区別したいときに別クラスにする。

---

### WildCard系パーサー

**後輩**: WildCard系のパーサーもあるんですね。ワイルドカードって、何にでもマッチするもの？

**先輩**: そう。正規表現の `.` や `.*` に相当するパーサーだ。

| パーサー | 動作 | 正規表現相当 |
|---------|------|-------------|
| `WildCardCharacterParser` | 任意の1文字 | `.` |
| `WildCardStringParser` | 任意の文字列（終端パーサーまで） | `.*?` (非貪欲) |
| `WildCardLineParser` | 行末まで任意の文字列 | `.*$` |
| `WildCardInterleaveParser` | 順不同マッチ用ワイルドカード | (特殊) |

**後輩**: `WildCardStringParser` は非貪欲マッチなんですか？

**先輩**: そう。`WildCardStringParser` は `WildCardStringTerninatorParser` と組み合わせて使い、「ここからここまでの間の何でも」というパターンを表現する。貪欲にしてしまうと入力の最後まで食べてしまうからね。

---

### 実例: tinyexpression での端末パーサーの使われ方

**後輩**: tinyexpression では具体的にどの端末パーサーが使われていますか？

**先輩**: 主要なものを挙げよう。

**数値リテラル**: `NumberParser` が `AbstractNumberFactorParser` の選択肢の1つとして使われる:

```java
// AbstractNumberFactorParser.java
parsers.add(NumberParser.class);
```

**変数名**: `NumberVariableParser` が内部で `IdentifierParser` 相当のパターンを使う:

```java
parsers.add(NumberVariableParser.class);
```

**演算子**: `PlusParser`, `MinusParser`, `MultipleParser`, `DivisionParser` が tinyexpression のパーサーパッケージで定義されている。これらは `WordParser` を内部で使用。

**後輩**: tinyexpression の `PlusParser` は `org.unlaxer.parser.ascii.PlusParser` とは別物ですか？

**先輩**: いい質問だ。tinyexpression には独自の `PlusParser` があって、`org.unlaxer.tinyexpression.parser.PlusParser` というクラスだ。これは tinyexpression の文脈で空白処理など追加の振る舞いを持つことがある。同じ名前でもパッケージが違えば別のクラスだ。

---

[← Part 3: Parser Combinator →](#part-3-parser-combinator-の考え方) | [次: Part 5 Combinator Parsers →](#part-5-combinator-parsers結合子)

---

## Part 5: Combinator Parsers（結合子）

[← Part 4: Terminal Parsers →](#part-4-terminal-parsers端末パーサー) | [次: Part 6 左結合・右結合 →](#part-6-左結合右結合演算子優先順位)

---

**後輩**: 端末パーサーが「レゴのブロック」なら、コンビネータは「ブロックをつなげるルール」ですよね。どんなコンビネータがありますか？

**先輩**: unlaxer-parser には多彩なコンビネータが用意されている。全て `org.unlaxer.parser.combinator` パッケージにある。1つずつ見ていこう。

---

### LazyChain (順接) -- A B C を順番にマッチ

**後輩**: 一番基本的なコンビネータは何ですか？

**先輩**: `LazyChain` (と `Chain`) だ。**順接** -- 複数のパーサーを順番に適用して、**全てが成功したら全体が成功**する。

```bnf
A B C
```

```java
public class MyParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(AParser.class),
            Parser.get(BParser.class),
            Parser.get(CParser.class)
        );
    }
}

// Constructed 版 (即時)
Parser myParser = new Chain(aParser, bParser, cParser);
```

**後輩**: A, B, C が全部マッチしないとダメなんですね。

**先輩**: そう。A がマッチしたが B が失敗したら、Chain 全体が失敗して、A が消費した分もロールバックされる。これが PEG のバックトラックだ。

**後輩**: ロールバック？

**先輩**: `ParseContext` のトランザクション機構を使っている。`begin()` でトランザクションを開始して、全ての子パーサーが成功したら `commit()`、途中で失敗したら `rollback()` してカーソル位置を元に戻す。

```
入力: "hello world"
Chain(WordParser("hello"), SpaceParser, WordParser("world"))

1. begin()
2. "hello" にマッチ → カーソルを5進める
3. " " にマッチ → カーソルを1進める
4. "world" にマッチ → カーソルを5進める
5. 全て成功 → commit() → 全体で11文字消費
```

```
入力: "hello earth"
Chain(WordParser("hello"), SpaceParser, WordParser("world"))

1. begin()
2. "hello" にマッチ → カーソルを5進める
3. " " にマッチ → カーソルを1進める
4. "world" にマッチしない → 失敗
5. rollback() → カーソルを元の位置に戻す
```

---

### LazyChoice (選択) -- A | B | C のどれか

**後輩**: 「AかBかC」みたいな選択はどうやるんですか？

**先輩**: `LazyChoice` (と `Choice`) だ。PEG の**順序付き選択**を実現する。子パーサーを先頭から順に試して、最初にマッチしたものを採用する。

```bnf
A | B | C
```

```java
public class MyChoiceParser extends LazyChoice {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(AParser.class),
            Parser.get(BParser.class),
            Parser.get(CParser.class)
        );
    }
}

// Constructed 版
Parser myParser = new Choice(aParser, bParser, cParser);
```

**後輩**: 先頭から順番に試すんですよね。順番を間違えるとまずいことになりますか？

**先輩**: なる。典型的な問題は「短いパターンが長いパターンを食べてしまう」ケースだ。

```java
// ダメな例
new Choice(
    new WordParser("if"),        // "if" にマッチ
    new WordParser("ifdef")      // "ifdef" は試されない！
)

// 正しい例
new Choice(
    new WordParser("ifdef"),     // 長いほうを先に
    new WordParser("if")
)
```

**後輩**: "ifdef" の先頭4文字が "if" にマッチしてしまうんですね。PEGは最初に成功した選択肢を採用するから...

**先輩**: その通り。これは PEG パーサーを書くときの最も重要な注意点の1つだ。**長いパターンを先に置く**、覚えておいて。

---

### ZeroOrMore -- { A } -- 0回以上の繰り返し

**後輩**: 繰り返しのコンビネータはありますか？

**先輩**: `ZeroOrMore` (と `LazyZeroOrMore`) が0回以上の繰り返しだ。EBNFの `{A}` や正規表現の `A*` に相当する。

```bnf
{ A }
```

```java
// 0回以上の数字にマッチ
Parser digits = new ZeroOrMore(Parser.get(DigitParser.class));

// Lazy版
public class MyRepeater extends LazyZeroOrMore {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(Parser.get(SomeParser.class));
    }
}
```

**後輩**: 0回でもOKということは、何もマッチしなくても成功するんですか？

**先輩**: そう。`ZeroOrMore` は常に成功する。マッチするものがなければ、空のトークンリストを返して成功する。

**後輩**: 内部的にはどうなっているんですか？

**先輩**: `LazyOccurs` の派生クラスで、`min()` が0、`max()` が `Integer.MAX_VALUE` を返す:

```java
public abstract class LazyZeroOrMore extends LazyOccurs {
    @Override
    public int min() { return 0; }

    @Override
    public int max() { return Integer.MAX_VALUE; }
}
```

**後輩**: `max()` が `Integer.MAX_VALUE` って、事実上無制限ですね。

**先輩**: そう。ただし注意点がある。**ZeroOrMore の中身が空文字列にマッチするパーサーだと無限ループになる**。

```java
// 危険！ Optional は空文字列にマッチするので、ZeroOrMore が永遠に回り続ける
new ZeroOrMore(new Optional(someParser))  // 無限ループ！
```

**後輩**: それは怖いですね...

**先輩**: unlaxer-parser では各イテレーションでカーソルが進んだかを確認して、進まなかった場合はループを終了する安全策が入っている。でも設計時に気をつけるに越したことはない。

---

### OneOrMore -- A+ -- 1回以上

**後輩**: 「1回以上」のバージョンもありますか？

**先輩**: `OneOrMore` (と `LazyOneOrMore`) だ。最低1回はマッチしないと失敗する。正規表現の `A+` に相当。

```java
// 1回以上の数字 → 数字列
Parser oneOrMoreDigits = new OneOrMore(Name.of("digits"), Parser.get(DigitParser.class));
```

**先輩**: `NumberParser` の中で `digitsParser` がまさにこれだ:

```java
static final OneOrMore digitsParser = new OneOrMore(Name.of("any-digit"), digitParser);
```

**後輩**: `min()` が1で `max()` が `Integer.MAX_VALUE` ですね。

```java
public abstract class LazyOneOrMore extends LazyOccurs {
    @Override
    public int min() { return 1; }

    @Override
    public int max() { return Integer.MAX_VALUE; }
}
```

---

### Optional / ZeroOrOne -- [ A ] -- あってもなくても

**後輩**: 「あってもなくてもいい」パーサーは？

**先輩**: `Optional` (別名 `ZeroOrOne`) だ。EBNFの `[A]` や正規表現の `A?` に相当。

```java
// 符号（+/-）はあってもなくてもいい
Parser optionalSign = new Optional(Name.of("optional-sign"), signParser);
```

**先輩**: `NumberParser` で使われている:

```java
new Optional(Name.of("optional-signParser"), signParser)
```

**後輩**: 0回か1回だけのマッチですね。`ZeroOrMore` と似てるけど最大1回。

**先輩**: 内部的には `min()=0`, `max()=1` だ。`Optional` は必ず成功する。マッチしなくても成功として扱われる。

---

### NonOrdered (Interleave) -- 順不同

**後輩**: 「順番はどうでもいいけど、全部出現してほしい」というパターンはどうやるんですか？

**先輩**: `NonOrdered` だ。RelaxNG の interleave パターンと同じ概念で、子パーサーを**任意の順序**でマッチさせる。

```java
// A, B, C がどの順番で出てきてもOK
Parser nonOrdered = new NonOrdered(aParser, bParser, cParser);

// "B A C" でも "C B A" でも "A B C" でもマッチ
```

**後輩**: 便利ですね！ HTMLの属性みたいに、順番が決まっていないものに使えそうです。

**先輩**: その通り。ただし全ての子パーサーがそれぞれ1回ずつマッチする必要がある。1つでも欠けると失敗する。

---

### Not -- 否定先読み

**後輩**: 「この文字でないこと」を確認するパーサーはありますか？

**先輩**: `Not` パーサーだ。PEG の否定先読み `!A` に相当する。**文字を消費しない**で、子パーサーがマッチしないことを確認する。

```java
// 次の文字が数字でないことを確認（文字は消費しない）
Parser notDigit = new Not(Parser.get(DigitParser.class));
```

**先輩**: 内部実装を見てみよう:

```java
public class Not extends ConstructedSingleChildParser {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        parseContext.startParse(this, parseContext, tokenKind, invertMatch);
        parseContext.begin(this);

        Parsed parsed = getChild().parse(parseContext, TokenKind.matchOnly, invertMatch);

        if (parsed.isSucceeded()) {
            // 子パーサーが成功 → Not は失敗。ロールバック
            parseContext.rollback(this);
            return Parsed.FAILED;
        }

        // 子パーサーが失敗 → Not は成功（文字は消費しない）
        Parsed committed = new Parsed(parseContext.commit(this, TokenKind.matchOnly));
        return committed;
    }
}
```

**後輩**: 子パーサーの結果を反転させて、しかも `TokenKind.matchOnly` で文字を消費しないんですね。

**先輩**: そう。`Not` は「ここにこのパターンが来ないこと」を確認するアサーションだ。正規表現の否定先読み `(?!...)` と同じ。

---

### MatchOnly -- 先読み（消費しない）

**後輩**: 「文字を消費しないで確認する」のは `Not` だけですか？

**先輩**: `MatchOnly` もある。これは肯定先読みだ。PEG の `&A` に相当する。子パーサーがマッチすることを確認するが、**カーソルは進めない**。

```java
// 次が数字であることを確認するが、数字は消費しない
Parser lookahead = new MatchOnly(Parser.get(DigitParser.class));
```

**先輩**: 内部では `TokenKind.matchOnly` で子パーサーを呼ぶ:

```java
public class MatchOnly extends ConstructedSingleChildParser implements MetaFunctionParser {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        parseContext.begin(this);
        Parsed parsed = getChild().parse(parseContext, TokenKind.matchOnly, invertMatch);
        if (parsed.isFailed()) {
            parseContext.rollback(this);
            return Parsed.FAILED;
        }
        // マッチしたが消費しない
        ...
    }
}
```

**後輩**: `Not` と `MatchOnly` の関係は:

| パーサー | 子が成功 | 子が失敗 | 消費 |
|---------|---------|---------|------|
| `MatchOnly` | 成功 | 失敗 | しない |
| `Not` | 失敗 | 成功 | しない |

**先輩**: 完璧な理解だ。

---

### Repeat -- 回数指定の繰り返し

**後輩**: 「ちょうど3回」とか「2回から5回」みたいな繰り返しは？

**先輩**: `Repeat` (と `LazyRepeat`) だ。`min` と `max` を指定できる。

```java
// ちょうど3回の数字 (例: "123")
Parser threeDigits = new Repeat(3, 3, digitParser);

// 2回から5回の数字 (例: "12", "12345")
Parser twoToFiveDigits = new Repeat(2, 5, digitParser);
```

**後輩**: `ZeroOrMore` は `Repeat(0, MAX)` で、`OneOrMore` は `Repeat(1, MAX)` で、`Optional` は `Repeat(0, 1)` と同じですね。

**先輩**: その通り。実際、内部的には全て `LazyOccurs` の派生クラスとして `min()` と `max()` で表現されている。

---

### ASTNode / NotASTNode -- AST フィルタリング

**後輩**: `ASTNode` と `NotASTNode` って何ですか？

**先輩**: パースツリーのどのノードを AST (抽象構文木) に含めるかを制御するラッパーだ。

```java
// このパーサーのトークンは AST に含まれる
Parser astVisible = new ASTNode(someParser);

// このパーサーのトークンは AST に含まれない（括弧や空白など）
Parser astHidden = new NotASTNode(someParser);
```

**後輩**: Part 1 で出てきた「CST と AST の両方にアクセスできる」仕組みですね。

**先輩**: そう。`ASTNode` でマークされたパーサーが生成したトークンだけが `Token.filteredChildren` に含まれる。`NotASTNode` でマークされたものは `filteredChildren` からは除外されるが、元の `children` (original children) にはある。

**後輩**: 括弧 `(` `)` は構文的には必要だけど、AST的には不要ですもんね。

**先輩**: さらに `ASTNodeRecursive` と `NotASTNodeRecursive` もある。これらは子孫ノードにも再帰的に AST マーキングを伝播させる。また `ASTNodeRecursiveGrandChildren` は孫以降にのみ適用する変種だ。

---

### WhiteSpaceDelimitedChain -- 空白区切り

**後輩**: プログラミング言語では空白が区切りとして使われることが多いですよね。空白を自動的にスキップするコンビネータはありますか？

**先輩**: `WhiteSpaceDelimitedChain` (と `WhiteSpaceDelimitedLazyChain`) だ。子パーサーの間に空白（スペース、タブ、改行）が入ることを許容する Chain だ。

```java
// "3 + 4" も "3+4" も "3  +  4" もマッチ
Parser expr = new WhiteSpaceDelimitedChain(
    numberParser,
    new WordParser("+"),
    numberParser
);
```

**後輩**: 空白は自動的にスキップされるんですか？

**先輩**: 正確には、各子パーサーの間にオプションの空白パーサーが挿入される。空白があってもなくてもマッチする。

**先輩**: tinyexpression の `AbstractNumberExpressionParser` で使われている:

```java
parsers.add(new ZeroOrMore(
    new WhiteSpaceDelimitedChain(
        new Choice(
            Parser.get(PlusParser.class),
            Parser.get(MinusParser.class)
        ),
        Parser.get(termParserClazz)
    )
));
```

**後輩**: `WhiteSpaceDelimitedChain` の中に `Choice` が入って、さらに `ZeroOrMore` で繰り返す。コンビネータの組み合わせですね。

---

### 実例: tinyexpression の NumberExpressionParser

**後輩**: ここまで学んだコンビネータを使って、tinyexpression の式パーサーの全体像を見たいです。

**先輩**: いいね。BNF で書くとこうだ:

```bnf
<expression> ::= <term> (('+' | '-') <term>)*
<term>       ::= <factor> (('*' | '/') <factor>)*
<factor>     ::= NUMBER | VARIABLE | '(' <expression> ')' | sin(...) | cos(...) | ...
```

**先輩**: unlaxer-parser のコードで見ると:

```java
// AbstractNumberExpressionParser -- <expression>
public Parsers getLazyParsers(boolean withNakedVariable) {
    Parsers parsers = new Parsers();

    // <term>
    parsers.add(termParserClazz);

    // (('+' | '-') <term>)*
    parsers.add(new ZeroOrMore(
        new WhiteSpaceDelimitedChain(
            new Choice(
                Parser.get(PlusParser.class),   // '+'
                Parser.get(MinusParser.class)    // '-'
            ),
            Parser.get(termParserClazz)          // <term>
        )
    ));

    return parsers;
}
```

```java
// AbstractNumberTermParser -- <term>
public Parsers getLazyParsers(boolean withNakedVariable) {
    Parsers parsers = new Parsers();

    // <factor>
    parsers.add(Parser.get(factorParserClazz));

    // (('*' | '/') <factor>)*
    parsers.add(new ZeroOrMore(
        new WhiteSpaceDelimitedChain(
            new Choice(
                Parser.get(MultipleParser.class),   // '*'
                Parser.get(DivisionParser.class)     // '/'
            ),
            Parser.get(factorParserClazz)            // <factor>
        )
    ));

    return parsers;
}
```

```java
// AbstractNumberFactorParser -- <factor>
public Parsers getLazyParsers(boolean withNakedVariable) {
    Parsers parsers = new Parsers();

    parsers.add(NumberSideEffectExpressionParser.class);  // 副作用式
    parsers.add(NumberIfExpressionParser.class);           // if式
    parsers.add(StrictTypedNumberMatchExpressionParser.class); // match式
    parsers.add(NumberParser.class);                        // 数値リテラル
    parsers.add(NumberVariableParser.class);                // $変数名

    if (withNakedVariable) {
        parsers.add(ExclusiveNakedVariableParser.class);   // 裸の変数名
    }

    parsers.add(new ParenthesesParser(                     // '(' <expression> ')'
        Parser.newInstance(expresionParserClazz)
    ));

    parsers.add(SinParser.class);     // sin(...)
    parsers.add(CosParser.class);     // cos(...)
    parsers.add(TanParser.class);     // tan(...)
    parsers.add(SquareRootParser.class); // sqrt(...)
    parsers.add(MinParser.class);     // min(...)
    parsers.add(MaxParser.class);     // max(...)
    parsers.add(RandomParser.class);  // random()

    return parsers;
}
```

**後輩**: おおー、これで `3 + 4 * sin(0.5)` みたいな式がパースできるんですね。

**先輩**: そう。各パーサーが小さな責務を持ち、コンビネータで組み合わさって大きなパーサーになる。これが Parser Combinator の力だ。

---

### コンビネータの一覧表

| コンビネータ | BNF/PEG相当 | 説明 | Lazy版 |
|------------|-------------|------|--------|
| `Chain` | `A B C` | 順接（全て成功で成功） | `LazyChain` |
| `Choice` | `A / B / C` | 順序付き選択（最初の成功を採用） | `LazyChoice` |
| `ZeroOrMore` | `A*` / `{A}` | 0回以上の繰り返し | `LazyZeroOrMore` |
| `OneOrMore` | `A+` | 1回以上の繰り返し | `LazyOneOrMore` |
| `Optional` | `A?` / `[A]` | 0回または1回 | `LazyOptional` |
| `ZeroOrOne` | `A?` | Optional の別名 | `LazyZeroOrOne` |
| `Repeat` | `A{m,n}` | 回数指定の繰り返し | `LazyRepeat` |
| `Not` | `!A` | 否定先読み | -- |
| `MatchOnly` | `&A` | 肯定先読み | -- |
| `NonOrdered` | (interleave) | 順不同マッチ | -- |
| `ASTNode` | -- | ASTフィルタ(含む) | -- |
| `NotASTNode` | -- | ASTフィルタ(除外) | -- |
| `WhiteSpaceDelimitedChain` | -- | 空白区切り順接 | `WhiteSpaceDelimitedLazyChain` |
| `Flatten` | -- | ネストの平坦化 | -- |
| `Reverse` | -- | 逆順マッチ | -- |
| `TagWrapper` | -- | タグ付与 | `RecursiveTagWrapper` |
| `ParserWrapper` | -- | パーサーのラッピング | -- |
| `ContainerParser` | -- | パーサーの入れ物 | -- |
| `PropagationStopper` | -- | 伝播の停止 | -- |

---

[← Part 4: Terminal Parsers →](#part-4-terminal-parsers端末パーサー) | [次: Part 6 左結合・右結合 →](#part-6-左結合右結合演算子優先順位)

---

## Part 6: 左結合・右結合・演算子優先順位

[← Part 5: Combinator Parsers →](#part-5-combinator-parsers結合子) | [次: Part 7 Token と Parse Tree →](#part-7-token-と-parse-tree)

---

**後輩**: 先輩、`3 - 2 - 1` の答えって何ですか？

**先輩**: 0だよ。`(3 - 2) - 1 = 0`。

**後輩**: そうですよね。でも `3 - (2 - 1) = 2` にはなりませんよね。なぜ左から計算するんですか？

**先輩**: いい疑問だ。これが**左結合 (left associativity)** だ。引き算や割り算は左結合と定義されている。同じ優先順位の演算子が並んだとき、**左から順に結合**する。

---

### 左結合 (Left Associativity)

**先輩**: 左結合の演算子:

```
3 - 2 - 1   =   (3 - 2) - 1   =   0
12 / 4 / 3  =   (12 / 4) / 3  =   1
```

**後輩**: つまり左結合だと、木構造はこうなるんですね:

```
左結合: 3 - 2 - 1

        (-)
       /   \
     (-)    1
    /   \
   3     2
```

**先輩**: そう。左のほうが木の深いところにある。「先に計算される」ものが深い位置に来る。

---

### 右結合 (Right Associativity)

**後輩**: 右結合の演算子もあるんですか？

**先輩**: ある。代表的なのは**べき乗 (exponentiation)** だ。

```
2 ^ 3 ^ 4   =   2 ^ (3 ^ 4)   =   2 ^ 81   =   2417851639229258349412352
```

**後輩**: え、右から計算するんですか！ `(2^3)^4 = 8^4 = 4096` とは全然違う結果ですね。

**先輩**: 右結合の木構造:

```
右結合: 2 ^ 3 ^ 4

   (^)
  /   \
 2    (^)
     /   \
    3     4
```

**後輩**: 右のほうが深い位置にある。

**先輩**: 他の右結合演算子としては、代入演算子 `=` がある。`a = b = c = 5` は `a = (b = (c = 5))` と解釈される。

---

### 演算子優先順位 (Operator Precedence)

**後輩**: `3 + 4 * 2` で `*` が先に計算されるのは、優先順位の問題ですよね。

**先輩**: そう。**演算子優先順位 (operator precedence)** は、異なる種類の演算子がある場合に、どの演算子を先に結合するかを決めるルールだ。

```
一般的な優先順位（高い順）:
1. 括弧 ()
2. べき乗 ^
3. 単項演算子 +x, -x
4. 乗除 *, /
5. 加減 +, -
6. 比較 <, >, <=, >=
7. 等値 ==, !=
8. 論理AND &&
9. 論理OR ||
10. 代入 =
```

**後輩**: 優先順位が高い演算子ほど「先に計算される」= 木の深い位置に来る、ということですね。

**先輩**: その通り。パーサーの文法でこの優先順位を表現するには、**文法の階層構造**を使う。

---

### tinyexpression での実装

**先輩**: tinyexpression では次の階層になっている:

```
NumberExpressionParser (+, -)    ... 優先度 低い（木の上）
    └── NumberTermParser (*, /)  ... 優先度 中（木の中間）
        └── NumberFactorParser   ... 優先度 高い（木の下）
            ├── NUMBER
            ├── VARIABLE
            └── '(' Expression ')'
```

**後輩**: 「Expression が Term を含み、Term が Factor を含む」という入れ子構造で優先順位を表現しているんですね。

**先輩**: そう。文法のルール自体が優先順位をエンコードしている。`*` と `/` は `Term` のレベルで処理されるから、`+` や `-` の `Expression` レベルより先に結合する。

```
入力: 3 + 4 * 2

Expression のパース:
├── Term のパース: "3" → Factor(3) のみ → Term = 3
├── "+"
└── Term のパース: "4 * 2"
    ├── Factor(4)
    ├── "*"
    └── Factor(2)
    → Term = 4 * 2

結果の木:
    Expression(+)
    ├── Term → Factor(3)
    └── Term(*)
        ├── Factor(4)
        └── Factor(2)
```

**後輩**: なるほど。`*` が `Term` の中で処理されるから、木のより深い位置に来る。

---

### なぜ左再帰 (Left Recursion) が PEG で問題になるか

**後輩**: え、左再帰ってなんですか？

**先輩**: 文法のルールで、非端末記号が自分自身を**左端**に含む場合、それを「左再帰 (left recursion)」と呼ぶ。

```bnf
# 左再帰の例（直接左再帰）
<expression> ::= <expression> '+' <term> | <term>
```

**後輩**: `expression` の定義の最初に `expression` が来てますね。

**先輩**: これは数学的には正しい文法で、左結合の加算を表現している。LR パーサー（yacc/Bison）ではこの書き方が自然に使える。

しかし PEG（やトップダウンパーサー全般）では大問題になる。なぜなら:

```
expression を解析するには...
→ まず expression を解析する必要がある
  → まず expression を解析する必要がある
    → まず expression を解析する必要がある
      → ... (無限再帰！)
```

**後輩**: あ、終わらない！ 自分自身を呼び出すとこで、何も消費しないまま再帰してしまうんですね。

**先輩**: その通り。PEG ベースのパーサーでは左再帰は**スタックオーバーフロー**を引き起こす。

---

### unlaxer での回避方法（繰り返しパターン）

**後輩**: じゃあどうやって左結合の演算子を表現するんですか？

**先輩**: **繰り返しパターン**に書き換える。左再帰の文法:

```bnf
# 左再帰（PEGでは使えない）
<expression> ::= <expression> '+' <term> | <term>
```

これを繰り返しに変換:

```bnf
# 繰り返し（PEGで使える）
<expression> ::= <term> ('+' <term>)*
```

**後輩**: おおー、意味的には同じことを表現しているんですね。

**先輩**: そう。`<term> ('+' <term>)*` は「最初に1つの term があり、その後に `'+' term` が0回以上繰り返される」という意味だ。

tinyexpression の `AbstractNumberExpressionParser` がまさにこのパターン:

```java
// <expression> ::= <term> (('+' | '-') <term>)*
parsers.add(termParserClazz);  // 最初の <term>
parsers.add(new ZeroOrMore(     // (('+' | '-') <term>)*
    new WhiteSpaceDelimitedChain(
        new Choice(
            Parser.get(PlusParser.class),
            Parser.get(MinusParser.class)
        ),
        Parser.get(termParserClazz)
    )
));
```

**後輩**: `ZeroOrMore` が左再帰の代わりをしているんですね。

**先輩**: そう。このパターンは PEG ベースのパーサーで**演算子優先順位パーサー**を作る標準的な手法だ。

---

### AST での結合性の表現

**後輩**: 繰り返しパターンだとパースツリーは平坦なリストになりますよね。左結合の木構造はどうやって作るんですか？

**先輩**: パースツリーの段階では確かに平坦なリストになる:

```
入力: 3 - 2 - 1

パースツリー:
Expression
├── Term(3)      ← 最初の term
├── "-"
├── Term(2)      ← 繰り返しの1回目
├── "-"
└── Term(1)      ← 繰り返しの2回目
```

**先輩**: これを左結合の AST に変換するのは、パース後の**AST変換 (AST mapping)** のフェーズで行う。unlaxer-parser には `RecursiveZeroOrMoreBinaryOperator` や `OperatorOperandPattern` といったユーティリティがあって、この変換を支援する。

```
左結合に変換:
    (-)
   /   \
 (-)    1
/   \
3    2
```

**後輩**: パースとAST変換は別のフェーズなんですね。

**先輩**: そう。パーサーは「文字列 → パースツリー」の変換に集中して、「パースツリー → AST」の変換は別の層で行う。関心の分離だ。

**後輩**: 右結合も同じパターンですか？

**先輩**: 右結合は少し違う。繰り返しパターンでパースした後、右から結合する:

```
入力: 2 ^ 3 ^ 4

パースツリー:
Power
├── Factor(2)
├── "^"
├── Factor(3)
├── "^"
└── Factor(4)

右結合に変換:
   (^)
  /   \
 2    (^)
     /   \
    3     4
```

**先輩**: この変換も AST マッピング層で行える。`@leftAssoc` と `@rightAssoc` のようなアノテーションで結合性を指定する仕組みがある。

---

### 優先順位の追加

**後輩**: もし新しい演算子を追加したくなったらどうするんですか？

**先輩**: 文法の階層にレベルを追加する。例えば、べき乗 `^` を追加するなら:

```
Expression (+, -)       ← 最低優先度
  └── Term (*, /)       ← 中優先度
      └── Power (^)     ← 高優先度（新規追加）
          └── Factor    ← 最高優先度
```

```java
// PowerParser (新規)
public class PowerParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        Parsers parsers = new Parsers();
        parsers.add(Parser.get(FactorParser.class));
        parsers.add(new ZeroOrMore(
            new WhiteSpaceDelimitedChain(
                new WordParser("^"),
                Parser.get(FactorParser.class)
            )
        ));
        return parsers;
    }
}
```

**後輩**: そして Term の中の Factor を Power に差し替える？

**先輩**: その通り。`Term` は `Factor` の代わりに `Power` を参照するように変更する。階層を1段深くすることで、優先順位が1つ高い演算子を追加できる。

**後輩**: パーサーの階層構造 = 演算子の優先順位、というのが直感的で分かりやすいです。

---

[← Part 5: Combinator Parsers →](#part-5-combinator-parsers結合子) | [次: Part 7 Token と Parse Tree →](#part-7-token-と-parse-tree)

---

## Part 7: Token と Parse Tree

[← Part 6: 左結合・右結合 →](#part-6-左結合右結合演算子優先順位) | [次: Part 8 自分のパーサーを作る →](#part-8-自分のパーサーを作る)

---

**後輩**: パーサーがパースした結果って、具体的にはどんなデータ構造になるんですか？

**先輩**: unlaxer-parser のパース結果は3つのクラスで構成される: `Token`, `ParseContext`, `Parsed` だ。

---

### Token クラスの構造

**先輩**: `Token` はパースツリーのノードだ。これが最も重要なクラスと言っていい。

```java
public class Token implements Serializable {

    public final Source source;                  // マッチしたソーステキスト
    public Parser parser;                        // このトークンを生成したパーサー
    public Optional<Token> parent;               // 親トークン
    private final TokenList originalChildren;    // 全ての子トークン
    public final TokenList filteredChildren;     // ASTフィルタ後の子トークン
    public final TokenKind tokenKind;            // トークンの種類
}
```

**後輩**: `parser` フィールドがあるんですね。どのパーサーが生成したトークンか分かる。

**先輩**: そう。これが非常に便利で、パースツリーを走査するときに「このノードは NumberParser が生成したものか？」という判定ができる。

```java
// トークンが特定のパーサーで生成されたか確認
if (token.parser instanceof NumberParser) {
    // 数値リテラルのトークン
    String numberText = token.source.toString();
}
```

---

### originalChildren vs filteredChildren

**後輩**: `originalChildren` と `filteredChildren` の違いは何ですか？

**先輩**: `originalChildren` は文法の全てのルールが生成した子トークンを含む。括弧、空白、キーワードなど全て。

`filteredChildren` は `ASTNode` / `NotASTNode` マーカーに基づいてフィルタリングされた子トークンだ。AST的に意味のあるノードだけが含まれる。

```
入力: (3 + 4) * 2

originalChildren:
ParenthesesParser
├── WordParser: "("           ← NotASTNode
├── ExpressionParser           ← ASTNode
│   ├── TermParser
│   │   └── FactorParser
│   │       └── NumberParser: "3"
│   ├── PlusParser: "+"
│   └── TermParser
│       └── FactorParser
│           └── NumberParser: "4"
└── WordParser: ")"           ← NotASTNode

filteredChildren:
ParenthesesParser
└── ExpressionParser          ← 括弧が除外された
    ├── NumberParser: "3"
    ├── PlusParser: "+"
    └── NumberParser: "4"
```

**後輩**: なるほど、`filteredChildren` を使えば括弧を気にせず意味的な処理ができるんですね。

---

### Source -- マッチしたテキスト

**後輩**: `Source` って何ですか？

**先輩**: `Source` はテキストデータを表す抽象だ。入力文字列全体を表す `StringSource` と、その部分文字列を表す部分 Source がある。

```java
Token token = ...;

// マッチしたテキストを取得
String matched = token.source.toString();

// テキストの長さ
int length = token.source.codePointLength().value();
```

**先輩**: `Source` はコードポイント単位で文字を扱うから、絵文字やサロゲートペアも正しく処理できる。

**後輩**: Java の `String` は UTF-16 だから、サロゲートペアがあると `String.length()` と文字数が合わなくなりますよね。

**先輩**: そう。unlaxer-parser は内部で `CodePointIndex` や `CodePointLength` を使って、コードポイントベースの位置管理を行っている。

---

### ParseContext -- パース状態の管理

**後輩**: `ParseContext` はどんなクラスですか？

**先輩**: `ParseContext` はパースの実行状態を管理するクラスだ。入力ソースとカーソル位置を保持し、トランザクション機構を提供する。

```java
public class ParseContext implements
    Closeable, Transaction,
    ParserListenerContainer,
    GlobalScopeTree, ParserContextScopeTree {

    boolean doMemoize;                      // メモ化の有効/無効
    public final Source source;             // 入力ソース
    boolean createMetaToken = true;         // メタトークン生成

    final Deque<TransactionElement> tokenStack = new ArrayDeque<>();  // トランザクションスタック
}
```

**後輩**: トランザクションって、データベースのトランザクションと同じ概念ですか？

**先輩**: 似ている。パーサーの try-and-rollback を実現するための仕組みだ。

**Transaction モデル**:

```
1. begin(parser)    -- トランザクション開始。現在のカーソル位置を保存
2. (子パーサーたちがパースを試みる)
3a. commit(parser)  -- 成功。トークンを確定して、カーソル位置を進める
3b. rollback(parser) -- 失敗。カーソル位置を保存時の位置に戻す
```

**後輩**: PEG のバックトラックがこのトランザクションで実現されているんですね。

**先輩**: そう。`Choice` パーサーは各選択肢を試すたびに `begin` → (パース) → 失敗したら `rollback`、成功したら `commit` する。

```
Choice(A, B, C) のパース:

begin()
  A を試す → 失敗 → rollback()
begin()
  B を試す → 成功 → commit()
  → Choice 全体が成功
```

---

### カーソル位置の管理

**後輩**: カーソル位置はどう管理されていますか？

**先輩**: `ParseContext` は内部にカーソルオブジェクトを持っていて、パースが進むたびにカーソルが前進する。`peek()` メソッドで現在位置の文字を読み取り、トークンの消費によってカーソルが進む。

```java
// 現在位置から1文字覗き見る
Source peeked = parseContext.peek(tokenKind, new CodePointLength(1));

// トランザクションの commit 時にカーソルが進む
```

**後輩**: `peek` は読むだけで消費しないんですか？

**先輩**: `TokenKind` による。`TokenKind.consumed` なら消費（commit 時にカーソルが進む）。`TokenKind.matchOnly` なら先読みだけでカーソルは進まない。

---

### Parsed -- パース結果

**後輩**: `Parsed` クラスは何を表しますか？

**先輩**: パーサーの `parse()` メソッドの戻り値だ。パースが成功したか失敗したかの情報と、成功した場合のトークンを含む。

```java
public class Parsed extends Committed {

    public enum Status {
        succeeded,   // パース成功
        stopped,     // 停止（成功扱い）
        failed;      // パース失敗

        public boolean isSucceeded() {
            return this == succeeded || this == stopped;
        }
    }

    public Status status;

    public static final Parsed FAILED = new Parsed(Status.failed);
    public static final Parsed SUCCEEDED = new Parsed(Status.succeeded);
}
```

**後輩**: `stopped` って何ですか？ 成功でも失敗でもない？

**先輩**: 「ここまでパースできたが、これ以上は進めない」という状態だ。`isSucceeded()` は `true` を返すから、成功の一種として扱われる。部分パースの結果を示すのに使う。

**後輩**: パース結果の使い方を見せてください。

**先輩**: こんな感じだ:

```java
// パーサーを取得
Parser parser = Parser.get(NumberExpressionParser.class);

// 入力を用意
Source source = StringSource.createSource("3 + 4 * 2");
ParseContext context = new ParseContext(source);

// パース実行
Parsed parsed = parser.parse(context);

// 結果を確認
if (parsed.isSucceeded()) {
    Token rootToken = parsed.getToken();
    System.out.println("マッチしたテキスト: " + rootToken.source);

    // 子トークンを走査
    for (Token child : rootToken.filteredChildren) {
        System.out.println("子ノード: " + child.parser.getClass().getSimpleName());
    }
}
```

---

### パースツリーの走査

**後輩**: パースツリーを走査するには？

**先輩**: `Token` クラスには走査用のメソッドがいくつかある。

```java
// 最初の子孫ノードを検索（深さ優先）
Optional<Token> found = token.findFirstDescendant(
    t -> t.parser instanceof NumberParser
);

// 条件に合う全ての子孫ノードを検索
List<Token> allNumbers = token.findDescendants(
    t -> t.parser instanceof NumberParser
);
```

**後輩**: Java の Stream API みたいに使えるんですね。

**先輩**: `TokenPredicators` というユーティリティクラスもあって、よく使う述語をまとめている。

```java
// パーサーのクラスで検索
Predicate<Token> isNumber = TokenPredicators.byParserClass(NumberParser.class);

// 検索実行
List<Token> numbers = token.findDescendants(isNumber);
```

---

### パースツリーの可視化

**後輩**: パースツリーをデバッグ用に表示する方法はありますか？

**先輩**: `TokenPrinter` と `ParsedPrinter` がある。

```java
// トークンツリーを文字列で表示
String tree = TokenPrinter.get(token, 0, OutputLevel.detail, false);
System.out.println(tree);
```

**先輩**: 出力は次のようなインデントされたツリー形式になる:

```
NumberExpressionParser "3 + 4 * 2"
  NumberTermParser "3"
    NumberFactorParser "3"
      NumberParser "3"
  PlusParser "+"
  NumberTermParser "4 * 2"
    NumberFactorParser "4"
      NumberParser "4"
    MultipleParser "*"
    NumberFactorParser "2"
      NumberParser "2"
```

**後輩**: これがあればパーサーの動作を確認しやすいですね。

---

[← Part 6: 左結合・右結合 →](#part-6-左結合右結合演算子優先順位) | [次: Part 8 自分のパーサーを作る →](#part-8-自分のパーサーを作る)

---

## Part 8: 自分のパーサーを作る

[← Part 7: Token と Parse Tree →](#part-7-token-と-parse-tree) | [次: Part 9 AST フィルタリング →](#part-9-ast-フィルタリングとスコープ)

---

**後輩**: 先輩、そろそろ自分でパーサーを書いてみたいです！

**先輩**: よし、ステップバイステップで簡単な電卓パーサーをゼロから作ろう。

---

### Step 1: LazyChain を継承してシンプルなパーサーを作る

**後輩**: まず何から始めればいいですか？

**先輩**: 最も基本的な非端末パーサーは `LazyChain` を継承して作る。`getLazyParsers()` メソッドで子パーサーのリストを返すだけでいい。

```java
package com.example.calculator;

import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.elementary.NumberParser;

// 数値リテラルだけをパースする最もシンプルなパーサー
public class NumberLiteralParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            NumberParser.class  // unlaxer 組み込みの数値パーサーを使う
        );
    }
}
```

**後輩**: `Parsers` のコンストラクタにクラスを渡すだけでいいんですか？

**先輩**: そう。`Parsers` はクラスを受け取ると、内部で `Parser.get()` を使ってシングルトンインスタンスを取得する。インスタンスを直接渡すこともできる:

```java
return new Parsers(
    Parser.get(NumberParser.class)  // インスタンスを渡す方式
);
```

---

### Step 2: getLazyParsers() で子パーサーを返す

**後輩**: もう少し複雑なパーサーを作るにはどうすればいいですか？

**先輩**: 子パーサーを複数返せばいい。例えば「符号付き数値」をパースするパーサー:

```java
public class SignedNumberParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // 符号（あってもなくてもいい）
            new Optional(
                new Choice(
                    new WordParser("+"),
                    new WordParser("-")
                )
            ),
            // 数値
            Parser.get(NumberParser.class)
        );
    }
}
```

**後輩**: `Optional` の中に `Choice` が入って、さらに `WordParser` が入る。コンビネータを入れ子にしていくんですね。

**先輩**: そう。このように**組み合わせ (composition)** でパーサーを構築するのが Parser Combinator の本質だ。

---

### Step 3: Parser.get(MyParser.class) でシングルトン取得

**後輩**: 作ったパーサーはどうやって使うんですか？

**先輩**: `Parser.get()` でシングルトンインスタンスを取得する。

```java
// パーサーのインスタンスを取得
SignedNumberParser parser = Parser.get(SignedNumberParser.class);

// 直接 new してもいいが、循環参照がある場合はシングルトンを使うべき
SignedNumberParser parser2 = new SignedNumberParser(); // 非推奨
```

**後輩**: 他のパーサーから参照するときもこの方法ですか？

**先輩**: そう。`getLazyParsers()` の中でも `Parser.get()` を使う:

```java
public class SomeOtherParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(SignedNumberParser.class),  // シングルトン取得
            new WordParser("end")
        );
    }
}
```

---

### Step 4: テストの書き方

**後輩**: パーサーのテストはどう書くんですか？

**先輩**: `ParseContext` を作って `parse()` を呼び、結果を確認する。

```java
import org.unlaxer.Parsed;
import org.unlaxer.Source;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SignedNumberParserTest {

    @Test
    void testPositiveNumber() {
        Parser parser = Parser.get(SignedNumberParser.class);
        Source source = StringSource.createSource("42");
        ParseContext context = new ParseContext(source);

        Parsed parsed = parser.parse(context);

        assertTrue(parsed.isSucceeded());
        assertEquals("42", parsed.getToken().source.toString());
    }

    @Test
    void testNegativeNumber() {
        Parser parser = Parser.get(SignedNumberParser.class);
        Source source = StringSource.createSource("-3.14");
        ParseContext context = new ParseContext(source);

        Parsed parsed = parser.parse(context);

        assertTrue(parsed.isSucceeded());
        assertEquals("-3.14", parsed.getToken().source.toString());
    }

    @Test
    void testInvalidInput() {
        Parser parser = Parser.get(SignedNumberParser.class);
        Source source = StringSource.createSource("abc");
        ParseContext context = new ParseContext(source);

        Parsed parsed = parser.parse(context);

        assertTrue(parsed.isFailed());
    }
}
```

**後輩**: シンプルですね。`ParseContext` を作って、`parse()` を呼んで、結果を `isSucceeded()` で確認するだけ。

**先輩**: テストでは他にもこんな確認ができる:

```java
// マッチしたテキストの長さを確認
assertEquals(5, parsed.getToken().source.codePointLength().value());

// 子トークンの数を確認
assertEquals(2, parsed.getToken().filteredChildren.size());

// 特定のパーサーの子トークンを確認
Token numberToken = parsed.getToken().findFirstDescendant(
    t -> t.parser instanceof NumberParser
).orElseThrow();
```

---

### Step 5: LazyChoice で選択肢を作る

**後輩**: 次は「数値か、括弧で囲まれた式」のような選択を作りたいです。

**先輩**: `LazyChoice` を使う:

```java
public class FactorParser extends LazyChoice {

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // 数値リテラル
            NumberParser.class,
            // 括弧で囲まれた式: '(' Expression ')'
            new ParenthesesParser(
                Parser.newInstance(ExpressionParser.class)
            )
        );
    }
}
```

**後輩**: `Parser.newInstance()` を使っていますね。`Parser.get()` じゃないのはなぜですか？

**先輩**: `ParenthesesParser` の中の `ExpressionParser` は、この `FactorParser` 自体とは独立したインスタンスが必要な場合がある。括弧の中の式は「新しいコンテキスト」として扱いたいからだ。tinyexpression の `AbstractNumberFactorParser` と同じパターンだね。

---

### Step 6: ZeroOrMore で繰り返しを作る

**後輩**: 演算子と項の繰り返しはどう作りますか？

**先輩**: 先ほどの tinyexpression パターンと同じだ:

```java
public class TermParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // 最初の Factor
            FactorParser.class,
            // ('*' | '/') Factor の繰り返し
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(
                    new Choice(
                        new WordParser("*"),
                        new WordParser("/")
                    ),
                    Parser.get(FactorParser.class)
                )
            )
        );
    }
}
```

---

### Step 7: 簡単な電卓パーサーをゼロから作る

**後輩**: 全部つなげて完全な電卓パーサーを作ってみたいです。

**先輩**: よし、全クラスを見せよう。文法はこうだ:

```bnf
Expression = Term { ('+' | '-') Term }
Term       = Factor { ('*' | '/') Factor }
Factor     = NUMBER | '(' Expression ')'
```

**CalcExpressionParser.java**:

```java
package com.example.calculator;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.WhiteSpaceDelimitedChain;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.WordParser;

public class CalcExpressionParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        // Expression = Term { ('+' | '-') Term }
        return new Parsers(
            CalcTermParser.class,
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(
                    new Choice(
                        new WordParser("+"),
                        new WordParser("-")
                    ),
                    Parser.get(CalcTermParser.class)
                )
            )
        );
    }
}
```

**CalcTermParser.java**:

```java
package com.example.calculator;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.WhiteSpaceDelimitedChain;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.WordParser;

public class CalcTermParser extends LazyChain {

    @Override
    public Parsers getLazyParsers() {
        // Term = Factor { ('*' | '/') Factor }
        return new Parsers(
            CalcFactorParser.class,
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(
                    new Choice(
                        new WordParser("*"),
                        new WordParser("/")
                    ),
                    Parser.get(CalcFactorParser.class)
                )
            )
        );
    }
}
```

**CalcFactorParser.java**:

```java
package com.example.calculator;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.elementary.NumberParser;
import org.unlaxer.parser.elementary.ParenthesesParser;

public class CalcFactorParser extends LazyChoice {

    @Override
    public Parsers getLazyParsers() {
        // Factor = NUMBER | '(' Expression ')'
        return new Parsers(
            NumberParser.class,
            new ParenthesesParser(
                Parser.newInstance(CalcExpressionParser.class)
            )
        );
    }
}
```

**後輩**: 3つのクラスだけで電卓のパーサーが完成するんですね！

**先輩**: そう。テストを書いてみよう:

```java
@Test
void testSimpleExpression() {
    Parser parser = Parser.get(CalcExpressionParser.class);
    Source source = StringSource.createSource("3 + 4 * 2");
    ParseContext context = new ParseContext(source);

    Parsed parsed = parser.parse(context);

    assertTrue(parsed.isSucceeded());
    assertEquals("3 + 4 * 2", parsed.getToken().source.toString());
}

@Test
void testNestedParentheses() {
    Parser parser = Parser.get(CalcExpressionParser.class);
    Source source = StringSource.createSource("(1 + 2) * (3 + 4)");
    ParseContext context = new ParseContext(source);

    Parsed parsed = parser.parse(context);

    assertTrue(parsed.isSucceeded());
}

@Test
void testDeeplyNested() {
    Parser parser = Parser.get(CalcExpressionParser.class);
    Source source = StringSource.createSource("((1 + 2) * 3) / (4 - (5 + 6))");
    ParseContext context = new ParseContext(source);

    Parsed parsed = parser.parse(context);

    assertTrue(parsed.isSucceeded());
}
```

**後輩**: 正規表現ではこんなことできませんでしたよね。任意の深さの括弧のネストをパースできるのは、パーサーならではですね。

**先輩**: その通り。3つのクラスと数十行のコードで、正規表現の壁を超えた。これが Parser Combinator の力だ。

---

### パーサー作成のベストプラクティス

**先輩**: いくつかのベストプラクティスをまとめておこう。

**1. クラス名は文法のルール名に合わせる**

```
Expression → ExpressionParser
Term       → TermParser
Factor     → FactorParser
```

**2. 循環参照がある場合は Lazy 版を使う**

```java
// Expression → ... → Factor → '(' Expression ')'  の循環
// → LazyChain, LazyChoice を使う
```

**3. Choice の順序に注意**

```java
// 長いパターンを先に
new Choice(
    longPattern,    // 先に試す
    shortPattern    // 後に試す
)
```

**4. StaticParser で定数化**

```java
// 繰り返し使うパーサーは static フィールドに
static final Parser digitParser = new DigitParser();
static final OneOrMore digitsParser = new OneOrMore(Name.of("digits"), digitParser);
```

**5. Name を付けてデバッグしやすく**

```java
new Optional(Name.of("optional-sign"), signParser)
new OneOrMore(Name.of("digits"), digitParser)
```

**後輩**: `Name.of()` は何のためですか？

**先輩**: パーサーに名前を付けておくと、パースツリーの表示やデバッグ時に「このノードは何のためのパーサーが生成したか」が分かりやすくなる。`NumberParser` の中で `Name.of("any-digit")` や `Name.of("digits-point-digits")` が使われているのはこのためだ。

---

[← Part 7: Token と Parse Tree →](#part-7-token-と-parse-tree) | [次: Part 9 AST フィルタリング →](#part-9-ast-フィルタリングとスコープ)

---

## Part 9: AST フィルタリングとスコープ

[← Part 8: 自分のパーサーを作る →](#part-8-自分のパーサーを作る) | [次: Part 10 エラーハンドリング →](#part-10-エラーハンドリングとデバッグ)

---

**後輩**: 先輩、Part 1 で「CST と AST の両方にアクセスできる」と聞きましたが、具体的にどう使うんですか？

**先輩**: unlaxer-parser の AST フィルタリングの仕組みを詳しく見ていこう。

---

### ASTNode vs NotASTNode

**先輩**: `ASTNode` と `NotASTNode` は、パーサーをラップして「このパーサーが生成するトークンをASTに含めるか含めないか」を指定するマーカーだ。

```java
// このパーサーのトークンは AST に含まれる
Parser important = new ASTNode(someParser);

// このパーサーのトークンは AST に含まれない
Parser syntaxOnly = new NotASTNode(someParser);
```

**後輩**: 具体的にはどういう場面で使い分けるんですか？

**先輩**: 例えばセミコロン `;` は構文的には必要だけど、意味的には不要だ:

```java
public class StatementParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            new ASTNode(ExpressionParser.class),     // 式は意味がある → AST に含む
            new NotASTNode(new WordParser(";"))       // セミコロンは構文的役割のみ → 除外
        );
    }
}
```

**後輩**: パースツリー全体 (CST) にはセミコロンのトークンがあるけど、`filteredChildren` (AST) にはないということですね。

---

### NodeKind enum

**先輩**: 内部的には `NodeKind` という enum で管理されている:

```java
public enum NodeKind {
    node,      // AST に含む
    notNode    // AST に含まない
}
```

**後輩**: シンプルですね。

**先輩**: `NodeReduceMarker` というインターフェースを通じて、各パーサーが自分の `NodeKind` を報告する。

---

### filteredChildren vs 全 children

**後輩**: `filteredChildren` と `originalChildren` の使い分けをもう少し詳しく教えてください。

**先輩**: こんな場面を考えよう。`if (condition) { body }` をパースした場合:

```
originalChildren (全ての子):
IfStatementParser
├── WordParser: "if"         ← キーワード
├── WordParser: "("          ← 開き括弧
├── ConditionParser: "x > 0" ← 条件
├── WordParser: ")"          ← 閉じ括弧
├── WordParser: "{"          ← 開きブレース
├── BodyParser: "return 1"   ← 本体
└── WordParser: "}"          ← 閉じブレース

filteredChildren (ASTノードのみ):
IfStatementParser
├── ConditionParser: "x > 0"
└── BodyParser: "return 1"
```

**後輩**: フォーマッタを書くなら `originalChildren` を使い、評価器やコード生成器を書くなら `filteredChildren` を使うわけですね。

**先輩**: その通り。1つのパース結果から、目的に応じて異なるビューを取得できる。

---

### ScopeTree -- 変数スコープの管理

**後輩**: 変数のスコープ管理もパーサーでやるんですか？

**先輩**: `ParseContext` は `GlobalScopeTree` と `ParserContextScopeTree` を実装していて、パース時にスコープ情報を管理できる。

```java
public class ParseContext implements
    Closeable, Transaction,
    GlobalScopeTree, ParserContextScopeTree {

    Map<Parser, Map<Name, Object>> scopeTreeMapByParser = new HashMap<>();
}
```

**後輩**: パーサーごとにスコープを持てるんですね。

**先輩**: そう。変数宣言パーサーがスコープに変数を登録し、変数参照パーサーがスコープから変数を検索する。tinyexpression の `NumberVariableMatchedWithVariableDeclarationParser` がこの仕組みを使っている。

---

### SuggestsCollectorParser -- コード補完のための提案収集

**後輩**: コード補完にも使えるんですか？

**先輩**: `SuggestsCollectorParser` というパーサーがあって、パース中に「ここで何が入力可能か」という候補を収集する。

```java
public interface SuggestsCollectorParser {
    // パース失敗時にも、どこまでマッチしたか、次に何が期待されるかを提供
}
```

**後輩**: LSP (Language Server Protocol) のオートコンプリートに使えそうですね。

**先輩**: まさにその用途だ。unlaxer-parser から LSP サーバーを構築する仕組みがある。`SuggestableParser` インターフェースを実装したパーサーは、自分が受け付ける候補 (`Suggests`) を返せる。

```java
public interface SuggestableParser {
    Suggests getSuggests();
}
```

**後輩**: パーサーの定義がそのまま入力補完のデータソースになるんですね。一石二鳥どころか一石三鳥だ。

---

[← Part 8: 自分のパーサーを作る →](#part-8-自分のパーサーを作る) | [次: Part 10 エラーハンドリング →](#part-10-エラーハンドリングとデバッグ)

---

## Part 10: エラーハンドリングとデバッグ

[← Part 9: AST フィルタリング →](#part-9-ast-フィルタリングとスコープ) | [次: Part 11 応用 →](#part-11-応用--ubnf-への道)

---

**後輩**: パーサーを書いていてエラーになったとき、どうデバッグすればいいですか？

**先輩**: パーサーのデバッグは独特の難しさがある。でも unlaxer-parser にはいくつかのツールがある。

---

### ParseException の読み方

**後輩**: `ParseException` が出たときはどう読めばいいですか？

**先輩**: `ParseException` にはパースが失敗した位置と、期待されていたパーサーの情報が含まれる。

```
ParseException: Expected NumberParser at position 5
Input: "3 + + 4"
              ^  ← ここで失敗
```

**後輩**: 位置情報があるのは助かりますね。

**先輩**: `ErrorMessageParser` というパーサーもあって、特定の位置でカスタムエラーメッセージを生成できる。文法の特定の箇所で分かりやすいエラーメッセージを出したいときに使う。

---

### 部分パースと consumed 長

**後輩**: パースが途中で止まった場合、どこまでマッチしたかは分かりますか？

**先輩**: `Parsed` の `Status.stopped` や、マッチしたトークンの `source` の長さから判断できる。

```java
Parsed parsed = parser.parse(context);

if (parsed.isSucceeded()) {
    int consumed = parsed.getToken().source.codePointLength().value();
    int total = source.codePointLength().value();

    if (consumed < total) {
        System.out.println("部分マッチ: " + consumed + " / " + total + " 文字");
        System.out.println("残り: " + source.toString().substring(consumed));
    }
}
```

**後輩**: 入力の全部をパースできたかどうかを、consumed の長さで確認するんですね。

**先輩**: そう。完全なパースを保証したい場合は、文法の最後に `EndOfSourceParser` を加える:

```java
public class CompleteExpressionParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            CalcExpressionParser.class,
            EndOfSourceParser.class  // 入力の末尾まで消費されたことを確認
        );
    }
}
```

---

### ParserPrinter -- パーサー階層の可視化

**後輩**: パーサーの構造自体を確認する方法はありますか？

**先輩**: `ParserPrinter` でパーサーの階層構造を文字列化できる。

```java
Parser parser = Parser.get(CalcExpressionParser.class);
String hierarchy = ParserPrinter.get(parser, OutputLevel.detail);
System.out.println(hierarchy);
```

**後輩**: 文法の構造がツリー表示されるんですね。パーサーが想定通りに構成されているか確認できる。

---

### よくあるミスとその対処法

**先輩**: パーサーを書いていてよく遭遇するミスをまとめておこう。

---

#### ミス1: LazyChoice の順序間違い

**先輩**: さっきも話したけど、PEG の順序付き選択で最も多いバグだ。

```java
// ダメな例: 短いキーワードが長いキーワードを食べてしまう
new Choice(
    new WordParser("else"),
    new WordParser("elseif")   // "elseif" は "else" にマッチしてしまい、到達しない
)

// 正しい例: 長いパターンを先に
new Choice(
    new WordParser("elseif"),  // 先に試す
    new WordParser("else")     // "elseif" が失敗した場合のみ試す
)
```

**後輩**: これは正規表現の`|`(最長一致)とは逆の挙動ですね。PEGは「最初に成功したもの」を採用するから。

**先輩**: そう。経験則として:
- キーワードは長いものを先に
- より限定的なパターンを先に
- より一般的なパターンを後に

---

#### ミス2: ZeroOrMore の無限ループ

**先輩**: `ZeroOrMore` の中身が空文字列にマッチするパーサーだと、各イテレーションでカーソルが進まないから、無限ループになる可能性がある。

```java
// 危険: Optional は空文字列にマッチしうる
new ZeroOrMore(
    new Optional(someParser)  // someParser がマッチしない → Optional は空で成功
                               // → ZeroOrMore は次のイテレーションへ → 無限ループ！
)

// 安全: OneOrMore なら最低1文字は消費する
new ZeroOrMore(
    new OneOrMore(someParser)  // 最低1回マッチしないと失敗 → 安全
)
```

**後輩**: unlaxer-parser にはこの無限ループの検出機構はありますか？

**先輩**: カーソルが進まなかったイテレーションを検出して、ループを終了する安全策が入っている。でも設計時に避けるのがベストだ。

---

#### ミス3: 循環参照で Lazy 版を使い忘れ

**先輩**: これも多い。

```java
// ダメな例: Chain で循環参照
public class ExprParser extends Chain {  // Chain は Constructed
    // コンストラクタで子パーサーが必要
    // → FactorParser のコンストラクタが ExprParser を必要
    // → 無限再帰！
}

// 正しい例: LazyChain を使う
public class ExprParser extends LazyChain {  // Lazy版
    @Override
    public Parsers getLazyParsers() {
        // 遅延評価 → 循環参照OK
        return new Parsers(
            FactorParser.class
        );
    }
}
```

**後輩**: 循環参照があったら `Lazy` 版。覚えました。

---

#### ミス4: 空白の扱いを忘れる

**先輩**: プログラミング言語ではトークン間に空白がある。空白をスキップする仕組みを入れ忘れると、`3+4` はパースできるが `3 + 4` はパースできない、ということになる。

```java
// ダメな例: 空白を考慮していない
new Chain(numberParser, new WordParser("+"), numberParser)
// "3+4" → OK, "3 + 4" → NG

// 正しい例: WhiteSpaceDelimitedChain を使う
new WhiteSpaceDelimitedChain(numberParser, new WordParser("+"), numberParser)
// "3+4" → OK, "3 + 4" → OK, "3  +  4" → OK
```

---

#### ミス5: EndOfSource を付け忘れる

**後輩**: これは先ほど出ましたね。

**先輩**: そう。`EndOfSourceParser` がないと、入力の一部だけパースして「成功」と報告してしまう。

```java
// "3 + 4 @@@" をパース
// EndOfSource なし → "3 + 4" でマッチして成功（"@@@" は無視）
// EndOfSource あり → "@@@" が残っているのでマッチ失敗
```

---

### デバッグのコツ

**先輩**: 最後に、パーサーのデバッグのコツをいくつか。

**1. 小さなパーサーから始める**

いきなり大きな文法を書かない。最小のパーサーから始めて、1つずつ機能を追加していく。追加するたびにテストを書いて確認。

**2. ParserListener を活用する**

`ParseContext` に `ParserListener` を追加すると、各パーサーの `parse()` が呼ばれるたびにコールバックを受け取れる。パースの流れをトレースできる。

**3. 失敗するテストケースを最小化する**

パースに失敗する入力があったら、なるべく短い入力に縮小する。`"abc def ghi"` で失敗するなら、`"abc"` だけで試してみる。

**4. Choice の各選択肢を個別にテストする**

`Choice(A, B, C)` が期待通りに動かないとき、A, B, C をそれぞれ単独でテストする。どの選択肢が問題かを特定してから、順序を調整する。

---

[← Part 9: AST フィルタリング →](#part-9-ast-フィルタリングとスコープ) | [次: Part 11 応用 →](#part-11-応用--ubnf-への道)

---

## Part 11: 応用 -- UBNF への道

[← Part 10: エラーハンドリング →](#part-10-エラーハンドリングとデバッグ) | [次: Part 12 高度なパーサー →](#part-12-高度なパーサー--知られざるクラスたち)

---

**後輩**: 先輩、ここまで Java のコードでパーサーを書いてきましたけど、もっと簡単に文法を定義する方法はないんですか？

**先輩**: あるよ。unlaxer-parser には **UBNF (Unlaxer BNF)** という文法定義言語がある。手書きの Java パーサーと同じことを、より簡潔な記法で書ける。

---

### 手書きパーサーから UBNF 文法への移行

**後輩**: さっきの電卓パーサーを UBNF で書くとどうなりますか？

**先輩**: こんな感じだ:

```ubnf
Expression = Term { ('+' | '-') Term } ;
Term       = Factor { ('*' | '/') Factor } ;
Factor     = NUMBER | '(' Expression ')' ;
```

**後輩**: え、これだけですか？ Java で3つのクラス、数十行書いていたものが3行に！

**先輩**: UBNF では文法を宣言的に書ける。UBNF コンパイラがこの定義から自動的に `LazyChain`, `LazyChoice`, `ZeroOrMore` などのパーサーオブジェクトを生成する。

---

### 同じ言語を手書きと UBNF で書く比較

**先輩**: 比較表を作ってみよう。

| 観点 | 手書き (Java) | UBNF |
|------|-------------|------|
| 記述量 | 多い (3クラス, 50+行) | 少ない (3行) |
| 柔軟性 | 高い (任意の Java コードが書ける) | 文法定義に限定 |
| カスタム処理 | パース中に任意の処理を挟める | 標準的なパースのみ |
| IDE サポート | Java の全 IDE 機能 | UBNF 用のエディタサポート |
| デバッグ | Java デバッガで直接ステップ実行 | 生成されたコードのデバッグ |
| パフォーマンス | 最適化の余地がある | 標準的な性能 |

---

### いつ手書き、いつ UBNF？

**後輩**: どっちを使えばいいんですか？

**先輩**: 目安はこうだ:

**UBNF を使うべきとき**:
- 文法が標準的な BNF/EBNF で表現できる
- 高速にプロトタイピングしたい
- 文法の変更が頻繁
- パースとは別に LSP (言語サーバー) や DAP (デバッガ) も欲しい

**手書きを使うべきとき**:
- パース中にカスタムロジックが必要（スコープ解決、型検査など）
- パフォーマンスの細かいチューニングが必要
- 標準的でない構文（インデントベースの言語など）
- 既存の Java コードベースに統合する

**後輩**: tinyexpression は手書きですよね。なぜですか？

**先輩**: tinyexpression はパーサーに多くのカスタムロジックが含まれている。`VariableTypeSelectable`, `TypedParser`, `SideEffectExpressionParser` など、パース中に型情報の解決やスコープ管理を行っている。こういった高度な処理は手書きのほうが柔軟に対応できる。

**後輩**: でも純粋な「文法定義」の部分は UBNF でも書けるんですよね。

**先輩**: そう。実際、unlaxer-dsl では UBNF 文法から LSP サーバーと DAP サーバーまで自動生成する仕組みがある。

---

### unlaxer-dsl への案内

**後輩**: もっと詳しく知りたいです。

**先輩**: unlaxer-dsl のチュートリアル `tutorial-ubnf-to-lsp-dap-dialogue.ja.md` で UBNF の詳しい使い方を解説している。そちらでは:

1. UBNF 文法の全構文
2. UBNF から LSP サーバーの生成
3. UBNF から DAP (デバッグアダプタ) の生成
4. VS Code 拡張の自動生成
5. 実際の言語を UBNF で定義する例

を会話劇形式で学べる。

**後輩**: パーサーの基礎を学んだ今なら、UBNF もスムーズに理解できそうです。

**先輩**: その通り。UBNF を理解するには、まず「パーサーが内部でどう動いているか」を知っていることが大前提だ。このチュートリアルで学んだ知識があれば、UBNF はただの「便利なショートカット」として理解できるだろう。

---

### まとめ: パーサーの学習ロードマップ

**先輩**: 最後にこのチュートリアルで学んだことのロードマップを整理しておこう。

```
Part 1:  パーサーとは何か
         文字列 → 構造化データ、正規表現の限界、CFG/BNF
                ↓
Part 2:  パース手法
         トップダウン vs ボトムアップ、PEG、Packrat
                ↓
Part 3:  Parser Combinator
         小さいパーサーを組み合わせる、Lazy vs Constructed
                ↓
Part 4:  端末パーサー
         WordParser, NumberParser, IdentifierParser, POSIX/ASCII
                ↓
Part 5:  コンビネータ
         Chain, Choice, ZeroOrMore, Optional, Not, MatchOnly
                ↓
Part 6:  演算子
         左結合/右結合、優先順位、左再帰の回避
                ↓
Part 7:  Token と ParseTree
         Token, ParseContext, Parsed, ツリー走査
                ↓
Part 8:  実践
         電卓パーサーをゼロから作る
                ↓
Part 9:  AST
         フィルタリング、スコープ、コード補完
                ↓
Part 10: デバッグ
         よくあるミス、デバッグツール
                ↓
Part 11: 応用
         UBNF への移行、LSP/DAPの自動生成
```

**後輩**: ありがとうございます、先輩。パーサーの世界がこんなに深いとは思いませんでした。でも、unlaxer-parser のおかげで、その深さを怖がらずに入っていけそうです。

**先輩**: パーサーは一見難しく見えるけど、結局は「小さな部品を組み合わせる」という、ソフトウェア工学の基本に忠実な技術だ。1つ1つのパーサーを理解して、組み合わせていけば、どんな言語でもパースできるようになる。

**後輩**: まずは電卓パーサーを拡張して、変数や関数を追加してみます。

**先輩**: いいね。困ったら tinyexpression のソースコードを参考にするといい。あれは実際に動いている Parser Combinator の教科書みたいなものだから。

---

[← Part 10: エラーハンドリング →](#part-10-エラーハンドリングとデバッグ) | [次: Part 12 高度なパーサー →](#part-12-高度なパーサー--知られざるクラスたち)

---

## Part 12: 高度なパーサー -- 知られざるクラスたち

[← Part 11: 応用 →](#part-11-応用--ubnf-への道) | [次: Appendix A 用語集 →](#appendix-a-パーサー用語集)

---

**先輩**: 実は僕も忘れてたんだけど...unlaxer-parser にはまだ紹介してないパーサーが結構あるんだ。

**後輩**: え、まだあるんですか？Part 11 まで来て全部カバーしたと思ってました。

**先輩**: いや、実は自分でも「あれ、こんなクラスあったっけ？」ってなるやつがいくつかある。ユーティリティ系の、縁の下の力持ちたちだ。

**後輩**: 作者が忘れるレベルのパーサー...逆に気になります。

**先輩**: じゃあ、1つずつ思い出しながら紹介していこう。

---

### 12.1 Flatten -- ネストの平坦化

**先輩**: まずは Flatten。これは Chain の中の Chain を1レベル平坦にするパーサーだ。

**後輩**: 平坦にする、というのは？

**先輩**: 例えば、Chain(Chain(A, B), C) だと Token tree が3レベルの深さになる。Flatten を使うと、子パーサーの children をそのまま自分の children にするから、ツリーが1レベル浅くなる。

```java
// Before: Chain(Chain(A, B), C) → Token tree is 3 levels deep
// After:  Flatten(Chain(A, B)) → A, B are direct children, tree is 2 levels
```

**後輩**: パーサー階層が深くなりすぎた時のリファクタリングに使えそうですね。

**先輩**: その通り。AST が無駄にネストしてると処理しにくいからね。Flatten で綺麗に整理できる。

---

### 12.2 Reverse -- 逆順マッチ

**先輩**: 次は Reverse。名前の通り、Chain の子パーサーを逆順にする。

**後輩**: 逆順？内部的に Collections.reverse() で子リストを反転するってことですか？

**先輩**: そう。普通は左から右にマッチしていくけど、優先度の低いパーサーを先に試したいときに使える。

**後輩**: 順番を変えたいなら最初からそう書けばいいのでは...

**先輩**: プログラム的にパーサーを組み立てる場合、後から順番を変えたくなることがあるんだよ。そういう時に便利だ。

---

### 12.3 TagWrapper / RecursiveTagWrapper -- タグでASTをコントロール

**先輩**: これは重要なやつだ。TagWrapper は1つのパーサーにタグ（メタデータ）を付与したり除去したりする。

**後輩**: タグというのは？

**先輩**: AST フィルタリングで使う情報だ。TagWrapperAction で add か remove を指定する。ASTNode と NotASTNode は実はこれの具象クラスなんだ。

**後輩**: あ、Part 9 で出てきた ASTNode がここに繋がるんですね！

**先輩**: そう。そして RecursiveTagWrapper は子孫まで再帰的にタグを適用する。RecursiveMode で再帰範囲を制御できる。ALL_CHILDREN なら全子孫、DIRECT_CHILDREN なら直接の子だけ。

```java
// ASTNode(parser) → このパーサーのTokenがfilteredChildrenに含まれる
// NotASTNode(parser) → このパーサーのTokenがfilteredChildrenから除外される
// ASTNodeRecursive(parser) → 子孫全てがfilteredChildrenに含まれる
```

**後輩**: ASTNodeRecursive まであるんですか。AST の制御が細かくできるんですね。

---

### 12.4 ParserWrapper -- パラメータの強制上書き

**先輩**: ParserWrapper は、親から伝播される TokenKind と invertMatch を無視して固定値を使うパーサーだ。

**後輩**: 伝播を無視する？

**先輩**: 例えば QuotedParser の内部で使われている。親が matchOnly モードでも、中のパーサーは consumed モードで動いてほしい場面があるんだ。

```java
// 親が matchOnly モードでも、中のパーサーは consumed モードで動く
new ParserWrapper(name, innerParser, TokenKind.consumed, false)
```

**後輩**: MatchOnly との違いは何ですか？

**先輩**: MatchOnly は「消費しない」を強制する。ParserWrapper は「どんなモードを強制するか」を選べる。より汎用的なコントロールだね。

---

### 12.5 ContainerParser\<T\> -- パーサーじゃない物をパースツリーに入れる

**先輩**: ContainerParser は面白いやつだ。NoneChildParser を継承していて、子パーサーがない。代わりに get() で任意の型 T のデータを返す。

**後輩**: パーサーなのにパースしないんですか？

**先輩**: パースツリーにエラーメッセージやメタデータを載せるための仕組みだ。ErrorMessageParser がこれの実例だよ。

```java
// ErrorMessageParser extends ContainerParser<String>
// → パース中にエラーメッセージをTokenとして挿入
```

**後輩**: なるほど、パースツリーをデータの入れ物としても使えるわけですね。

---

### 12.6 PropagationStopper -- 伝播の制御（全4種）

**先輩**: これは一番忘れてたやつだなw

**後輩**: 先輩が笑いながら言うと不安になります...

**先輩**: Parse 時に TokenKind と invertMatch が親から子に伝播するという話は覚えてる？Stopper はこの伝播を止めるパーサーだ。全部で4種類ある。

| クラス | TokenKind | invertMatch | 用途 |
|--------|-----------|-------------|------|
| AllPropagationStopper | 停止→consumed | 停止→false | 完全遮断 |
| DoCounsumePropagationStopper | 停止→consumed | 通過 | 消費モード強制 |
| InvertMatchPropagationStopper | 通過 | 停止→false | 反転ロジック無効化 |
| NotPropagatableSource | 通過 | 反転 | 論理NOT |

**後輩**: DoCounsume... 先輩、これ typo ですよね？Consume の。

**先輩**: うん、Consume の typo。でも直すと API 互換性壊れるから残してる。

**後輩**: 歴史的経緯ってやつですね...

**先輩**: プログラマーあるあるだ。

---

### 12.7 Ordered -- NonOrdered の反対

**先輩**: Ordered は Chain とほぼ同じだけど「順序が重要」であることを明示するマーカーだ。

**後輩**: Chain と何が違うんですか？

**先輩**: 機能的にはほぼ同じ。でも NonOrdered（Interleave）と対比して使うことで、意図が明確になる。NonOrdered はどの順番でも OK、Ordered は必ず左から右だ。

**後輩**: ドキュメンテーションとしての意味が大きいんですね。

**先輩**: コードは読む人のために書くものだからね。

---

### 12.8 ChildOccursWithTerminator -- 終端付き繰り返し

**先輩**: ChildOccursWithTerminator は ZeroOrMore, OneOrMore, Optional, Repeat の共通基底クラスだ。

**後輩**: 繰り返し系のパーサーの親玉ですね。

**先輩**: そう。特徴的なのは terminator（終端パーサー）を持てること。「この文字が来るまで繰り返す」というパターンが表現できる。

```java
// 例: セミコロンが来るまで要素を繰り返す
new ZeroOrMore(elementParser, () -> Parser.get(SemiColonParser.class))
```

**後輩**: terminator があると、繰り返しの終了条件を明示できるんですね。

**先輩**: CSV のパースや、区切り文字付きリストの処理で重宝するよ。

---

### 12.9 MatchOnly vs Not -- 先読みの双子

**先輩**: MatchOnly と Not は先読み（lookahead）の双子だ。どちらも入力を消費しない。

**後輩**: 先読みって、PEG の & と ! ですよね？

**先輩**: その通り。具体例を見てみよう。

```
入力: "hello"

MatchOnly(WordParser("hello"))
  → 成功（ただし消費しない。カーソルは "hello" の先頭のまま）

Not(WordParser("hello"))
  → 失敗（子が成功したので Not は失敗）

Not(WordParser("world"))
  → 成功（子が失敗したので Not は成功。消費しない）
```

**後輩**: MatchOnly が正の先読み、Not が負の先読みですね。

**先輩**: 正確だ。MatchOnly = positive lookahead、Not = negative lookahead。PEG の & と ! にそのまま対応する。

---

### 12.10 MappedSingleCharacterParserHolder -- 文字クラスのカスタマイズ

**先輩**: 最後は MappedSingleCharacterParserHolder。MappedSingleCharacterParser のラッパーだ。

**後輩**: 名前が長いですね...

**先輩**: 機能はシンプルだよ。newWithout() で特定の文字を除外した新しいパーサーを作れる。

```java
// アルファベットだけど 'x' は除外
AlphabetParser alphabet = Parser.get(AlphabetParser.class);
MappedSingleCharacterParserHolder holder = new MappedSingleCharacterParserHolder(alphabet);
Parser noX = holder.newWithout('x');
```

**後輩**: 正規表現の `[a-wyz]` みたいなことがプログラム的にできるんですね。

**先輩**: そう。文字クラスを動的にカスタマイズしたい場面で使える。

---

**後輩**: こんなにユーティリティパーサーがあったんですね。全部で10個...

**先輩**: 普段は Chain とか Or とかの主役級パーサーだけで事足りるけど、凝ったことをしようとすると、こういう脇役たちが効いてくる。

**後輩**: 忘れられてたパーサーたちに光が当たって良かったです。

**先輩**: 作者として反省してる。次のバージョンではドキュメントもちゃんと整備しないとな。

---

[← Part 11: 応用 →](#part-11-応用--ubnf-への道) | [次: Appendix A 用語集 →](#appendix-a-パーサー用語集)

---

## Appendix A: パーサー用語集

[← Part 12: 高度なパーサー →](#part-12-高度なパーサー--知られざるクラスたち) | [次: Appendix B 全パーサー一覧 →](#appendix-b-unlaxer-parser-全パーサー一覧)

---

| 用語 | 英語 | 説明 |
|------|------|------|
| パーサー | Parser | 文字列を構造化データ（構文木）に変換するプログラム |
| 再帰下降 | Recursive Descent | 文法の各ルールを関数として実装するトップダウンパース手法 |
| 左再帰 | Left Recursion | 非端末記号が自分自身を左端に含む文法パターン。PEGでは直接書けない |
| 先読み | Lookahead | 入力を先に覗いて解析の方向を決定すること。文字は消費しない |
| バックトラック | Backtracking | パースに失敗したときカーソルを戻してやり直すこと |
| メモ化 | Memoization | パース結果をキャッシュして再計算を避ける最適化手法 |
| Packrat | Packrat Parsing | メモ化により線形時間を保証する PEG パース手法 |
| PEG | Parsing Expression Grammar | 順序付き選択を持つ文法形式。2004年にBryan Fordが提唱 |
| CFG | Context-Free Grammar | 文脈自由文法。再帰的な構造を表現できる形式文法 |
| BNF | Backus-Naur Form | 文脈自由文法を記述するための標準的な記法 |
| EBNF | Extended BNF | BNF に繰り返しやオプションの記法を追加した拡張版 |
| UBNF | Unlaxer BNF | unlaxer-parser 独自の EBNF 拡張 |
| LL | Left-to-right, Leftmost | 左から読んで左端導出を行うトップダウンパーサー族 |
| LR | Left-to-right, Rightmost | 左から読んで右端導出（の逆）を行うボトムアップパーサー族 |
| GLR | Generalized LR | LR の一般化。曖昧な文法も扱える |
| AST | Abstract Syntax Tree | 抽象構文木。意味に必要な情報のみを保持する木構造 |
| CST | Concrete Syntax Tree | 具象構文木（パースツリー）。構文情報を全て保持する木構造 |
| Token | Token | パーサーが生成するパースツリーのノード |
| パースツリー | Parse Tree | パーサーが生成する木構造。CSTと同義 |
| シフト・還元 | Shift-Reduce | LR パーサーの基本操作。トークンをスタックに積む(shift)か、ルールに還元する(reduce) |
| 左結合 | Left Associative | 同じ優先順位の演算子を左から結合する。例: `a-b-c = (a-b)-c` |
| 右結合 | Right Associative | 同じ優先順位の演算子を右から結合する。例: `a^b^c = a^(b^c)` |
| 演算子優先順位 | Operator Precedence | 異なる演算子間の結合の強さの順序 |
| 端末記号 | Terminal Symbol | 文法において、それ以上展開されない最小の記号。文字やキーワード |
| 非端末記号 | Non-terminal Symbol | 文法において、他の記号に展開される記号。ルール名 |
| 生成規則 | Production Rule | 非端末記号を端末記号や他の非端末記号で定義するルール |
| 導出 | Derivation | 生成規則を適用して開始記号から文字列を生成する過程 |
| 順序付き選択 | Ordered Choice | PEG の選択演算子 `/`。先に書かれた候補を優先する |
| コンビネータ | Combinator | パーサーを引数に取り、新しいパーサーを返す高階関数/クラス |
| シングルトン | Singleton | 1つのクラスに対して1つだけのインスタンスを持つパターン |
| 遅延評価 | Lazy Evaluation | 値が必要になるまで計算を遅延させる手法 |
| 循環参照 | Circular Reference | AがBを参照し、BがAを参照するような相互依存 |
| トランザクション | Transaction | begin/commit/rollback で状態変更を管理する仕組み |

---

[← Part 11: 応用 →](#part-11-応用--ubnf-への道) | [次: Appendix B 全パーサー一覧 →](#appendix-b-unlaxer-parser-全パーサー一覧)

---

## Appendix B: unlaxer-parser 全パーサー一覧

[← Appendix A: 用語集 →](#appendix-a-パーサー用語集)

---

### 端末パーサー (Terminal Parsers)

#### elementary パッケージ (`org.unlaxer.parser.elementary`)

| クラス名 | 種類 | 説明 |
|---------|------|------|
| `WordParser` | リテラル | 指定した文字列と完全一致 |
| `SingleCharacterParser` | 1文字(抽象) | 1文字マッチの基底クラス |
| `MappedSingleCharacterParser` | 1文字(変換付き) | マッチした文字に変換を適用 |
| `NumberParser` | 数値 | 整数・小数・指数表記の数値 |
| `SignParser` | 記号 | `+` または `-` の符号 |
| `ExponentParser` | 数値 | `e-3`, `E+5` のような指数部 |
| `SingleQuotedParser` | 文字列 | シングルクォート文字列 |
| `DoubleQuotedParser` | 文字列 | ダブルクォート文字列 |
| `QuotedParser` | 文字列 | 引用符文字列の基底 |
| `EscapeInQuotedParser` | 文字列 | エスケープシーケンス処理 |
| `SingleQuoteParser` | 記号 | シングルクォート文字 |
| `SingleStringParser` | リテラル | 1文字のリテラル |
| `EndOfSourceParser` | 境界 | 入力の末尾 |
| `StartOfSourceParser` | 境界 | 入力の先頭 |
| `EndOfLineParser` | 境界 | 行末 |
| `StartOfLineParser` | 境界 | 行頭 |
| `EmptyLineParser` | 境界 | 空行 |
| `EmptyParser` | 特殊 | 常に成功（0文字消費） |
| `LineTerminatorParser` | 区切り | 改行文字 |
| `SpaceDelimitor` | 区切り | 空白区切り |
| `WildCardCharacterParser` | ワイルドカード | 任意の1文字 |
| `WildCardStringParser` | ワイルドカード | 任意の文字列（終端まで） |
| `WildCardLineParser` | ワイルドカード | 行末まで任意の文字列 |
| `WildCardInterleaveParser` | ワイルドカード | 順不同マッチ用 |
| `WildCardStringTerninatorParser` | ワイルドカード | ワイルドカード文字列の終端 |
| `MultipleParser` | 繰り返し | 複数回マッチ |
| `ParenthesesParser` | 括弧 | `( ... )` で囲まれた内容 |
| `NamedParenthesesParser` | 括弧 | 名前付き括弧 |
| `EParser` | 記号 | `e` または `E`（指数用） |
| `IgnoreCaseWordParser` | リテラル | 大文字小文字を無視した文字列マッチ |
| `AbstractTokenParser` | 基底 | 端末パーサーの抽象基底クラス |

#### POSIX パッケージ (`org.unlaxer.parser.posix`)

| クラス名 | マッチ対象 | POSIX文字クラス |
|---------|----------|---------------|
| `DigitParser` | 数字 0-9 | `[:digit:]` |
| `AlphabetParser` | 英字 a-zA-Z | `[:alpha:]` |
| `AlphabetNumericParser` | 英数字 | `[:alnum:]` |
| `AlphabetUnderScoreParser` | 英字 + アンダースコア | -- |
| `AlphabetNumericUnderScoreParser` | 英数字 + アンダースコア | -- |
| `UpperParser` | 大文字 A-Z | `[:upper:]` |
| `LowerParser` | 小文字 a-z | `[:lower:]` |
| `SpaceParser` | 空白文字 | `[:space:]` |
| `BlankParser` | スペース・タブ | `[:blank:]` |
| `PunctuationParser` | 句読点記号 | `[:punct:]` |
| `ControlParser` | 制御文字 | `[:cntrl:]` |
| `GraphParser` | 可視文字 | `[:graph:]` |
| `PrintParser` | 印刷可能文字 | `[:print:]` |
| `AsciiParser` | ASCII文字全般 | -- |
| `XDigitParser` | 16進数字 | `[:xdigit:]` |
| `WordParser` (posix) | 英数字 + _ | `\w` |
| `ColonParser` | コロン `:` | -- |
| `CommaParser` | カンマ `,` | -- |
| `DotParser` | ドット `.` | -- |
| `HashParser` | ハッシュ `#` | -- |
| `SemiColonParser` | セミコロン `;` | -- |

#### ASCII パッケージ (`org.unlaxer.parser.ascii`)

| クラス名 | マッチ文字 |
|---------|----------|
| `PlusParser` | `+` |
| `MinusParser` | `-` |
| `PointParser` | `.` |
| `GreaterThanParser` | `>` |
| `LessThanParser` | `<` |
| `EqualParser` | `=` |
| `DivisionParser` | `/` |
| `SlashParser` | `/` |
| `BackSlashParser` | `\` |
| `DoubleQuoteParser` | `"` |
| `LeftParenthesisParser` | `(` |
| `RightParenthesisParser` | `)` |

#### clang パッケージ (`org.unlaxer.parser.clang`)

| クラス名 | 説明 |
|---------|------|
| `IdentifierParser` | C言語スタイルの識別子 `[a-zA-Z_][a-zA-Z0-9_]*` |
| `BlockComment` | `/* ... */` ブロックコメント |
| `CPPComment` | `// ...` 行コメント |
| `CStyleDelimitedLazyChain` | C言語スタイルの区切り付きChain |
| `CStyleDelimitor` | C言語スタイルの区切り文字（空白・コメント） |
| `CStyleDelimitorElements` | 区切り要素のコレクション |

---

### コンビネータパーサー (Combinator Parsers)

#### 基本コンビネータ (`org.unlaxer.parser.combinator`)

| クラス名 | Lazy版 | BNF/PEG相当 | 説明 |
|---------|--------|-------------|------|
| `Chain` | `LazyChain` | `A B C` | 順接。全ての子が順にマッチ |
| `Choice` | `LazyChoice` | `A / B / C` | 順序付き選択。最初の成功を採用 |
| `ZeroOrMore` | `LazyZeroOrMore` | `A*`, `{A}` | 0回以上の繰り返し |
| `OneOrMore` | `LazyOneOrMore` | `A+` | 1回以上の繰り返し |
| `Optional` | `LazyOptional` | `A?`, `[A]` | 0回または1回 |
| `ZeroOrOne` | `LazyZeroOrOne` | `A?` | Optional の別名 |
| `Zero` | `LazyZero` | -- | 0回（常に空で成功） |
| `Repeat` | `LazyRepeat` | `A{m,n}` | 回数指定の繰り返し |
| `Not` | -- | `!A` | 否定先読み（消費しない） |
| `MatchOnly` | -- | `&A` | 肯定先読み（消費しない） |
| `NonOrdered` | -- | interleave | 順不同マッチ |
| `Ordered` | -- | -- | 順序付きマッチ |
| `Reverse` | -- | -- | 逆順マッチ |

#### ASTフィルタリング

| クラス名 | 説明 |
|---------|------|
| `ASTNode` | このパーサーのトークンをASTに含む |
| `NotASTNode` | このパーサーのトークンをASTから除外 |
| `ASTNodeRecursive` | 再帰的にASTに含む |
| `NotASTNodeRecursive` | 再帰的にASTから除外 |
| `ASTNodeRecursiveGrandChildren` | 孫以降を再帰的にASTに含む |
| `NotASTNodeRecursiveGrandChildren` | 孫以降を再帰的にASTから除外 |
| `NotASTChildrenOnlyLazyChain` | 子ノードのみNotAST |
| `NotASTChildrenOnlyLazyChoice` | 子ノードのみNotAST |
| `NotASTLazyChain` | NotAST付きLazyChain |
| `NotASTLazyChoice` | NotAST付きLazyChoice |

#### 空白処理

| クラス名 | 説明 |
|---------|------|
| `WhiteSpaceDelimitedChain` | 空白区切りの順接（Constructed版） |
| `WhiteSpaceDelimitedLazyChain` | 空白区切りの順接（Lazy版） |

#### ラッパー・ユーティリティ

| クラス名 | 説明 |
|---------|------|
| `ParserWrapper` | パーサーを別のパーサーでラップ |
| `ParserHolder` | パーサーのホルダー（遅延参照用） |
| `TagWrapper` | パーサーにタグを付与 |
| `RecursiveTagWrapper` | 再帰的にタグを付与 |
| `ContainerParser` | パーサーのコンテナ |
| `Flatten` | ネストされたトークンを平坦化 |
| `PropagationStopper` | 伝播の停止 |
| `AllPropagationStopper` | 全ての伝播を停止 |
| `DoCounsumePropagationStopper` | 消費時の伝播停止 |
| `InvertMatchPropagationStopper` | 反転マッチ時の伝播停止 |
| `NotPropagatableSource` | 伝播不可ソース |
| `AbstractPropagatableSource` | 伝播可能ソースの基底 |
| `MappedSingleCharacterParserHolder` | マップ付き1文字パーサーのホルダー |

#### 出現回数管理

| クラス名 | 説明 |
|---------|------|
| `Occurs` | 出現回数管理（Constructed版） |
| `LazyOccurs` | 出現回数管理（Lazy版） |
| `ConstructedOccurs` | 構築済み出現回数 |
| `ChildOccursWithTerminator` | 終端付き出現回数 |

#### 収集・述語

| クラス名 | 説明 |
|---------|------|
| `SingleChildCollectingParser` | 1つの子から収集するパーサー |
| `NoneChildCollectingParser` | 子なし収集パーサー |
| `NoneChildParser` | 子なしパーサー |
| `PredicateAnyMatchForParsedParser` | 述語による任意マッチ |

#### 基底クラス

| クラス名 | 説明 |
|---------|------|
| `LazyCombinatorParser` | Lazy版コンビネータの基底 |
| `ConstructedCombinatorParser` | Constructed版コンビネータの基底 |
| `ConstructedSingleChildParser` | 1子のConstructedコンビネータ基底 |
| `ConstructedMultiChildParser` | 複数子のConstructedコンビネータ基底 |
| `ConstructedMultiChildCollectingParser` | 複数子の収集Constructedコンビネータ基底 |
| `LazyMultiChildParser` | 複数子のLazyコンビネータ基底 |
| `LazyMultiChildCollectingParser` | 複数子の収集Lazyコンビネータ基底 |
| `ChainInterface` | Chainの振る舞いインターフェース |
| `ChoiceInterface` | Choiceの振る舞いインターフェース |
| `ChoiceCommitAction` | Choice成功時のアクション |

---

### 参照パーサー (Referencer Parsers)

| クラス名 | 説明 |
|---------|------|
| `ReferenceParser` | 他のパーサーへの参照 |
| `ReferenceByNameParser` | 名前による参照 |
| `MatchedTokenParser` | マッチ済みトークンの参照 |
| `MatchedChoiceParser` | Choice でマッチした選択肢の参照 |
| `MatchedNonOrderedParser` | NonOrdered でマッチした順序の参照 |
| `OldMatchedTokenParser` | 旧式マッチ済みトークン参照 |
| `Referencer` | 参照インターフェース |

---

### コアインターフェース・クラス

| クラス名 | 説明 |
|---------|------|
| `Parser` | 全パーサーの最上位インターフェース。`parse()`, `get()`, `getChildren()` |
| `AbstractParser` | パーサーの抽象基底クラス |
| `LazyAbstractParser` | Lazy版パーサーの抽象基底 |
| `ConstructedAbstractParser` | Constructed版パーサーの抽象基底 |
| `TerminalSymbol` | 端末パーサーのマーカーインターフェース |
| `NonTerminallSymbol` | 非端末パーサーのマーカーインターフェース |
| `StaticParser` | 静的初期化されるパーサーのマーカー |
| `Parsers` | パーサーのリスト |
| `ParserInitializer` | パーサーの初期化 |
| `ParserFactoryByClass` | クラスからパーサーを生成するファクトリ |
| `ParserFactoryBySupplier` | Supplierからパーサーを生成するファクトリ |
| `ParseException` | パース例外 |
| `ParserPrinter` | パーサー階層の文字列化 |
| `RootParserIndicator` | ルートパーサーのマーカー |
| `HasChildParser` | 1つの子パーサーを持つインターフェース |
| `HasChildrenParser` | 複数の子パーサーを持つインターフェース |
| `ErrorMessageParser` | エラーメッセージ生成パーサー |
| `SuggestsCollectorParser` | 入力候補収集パーサー |
| `SuggestableParser` | 候補提供可能パーサー |
| `Suggests` | 入力候補のコレクション |
| `Suggest` | 個別の入力候補 |
| `CollectingParser` | 収集パーサーインターフェース |
| `MetaFunctionParser` | メタ関数パーサー |
| `NodeReduceMarker` | ノード還元マーカー |
| `PseudoRootParser` | 擬似ルートパーサー |
| `PositionedElements` | 位置付き要素 |

#### Lazy関連インターフェース

| クラス名 | 説明 |
|---------|------|
| `LazyInstance` | 遅延インスタンス |
| `LazyParserChildSpecifier` | 遅延子パーサー指定（単数） |
| `LazyParserChildrenSpecifier` | 遅延子パーサー指定（複数） |
| `LazyOccursParserSpecifier` | 遅延出現回数パーサー指定 |
| `ParsersSpecifier` | パーサー群の指定 |

#### 伝播関連

| クラス名 | 説明 |
|---------|------|
| `PropagatableSource` | 伝播可能ソース |
| `PropagatableDestination` | 伝播先 |
| `ChainParsers` | Chain用パーサー群 |
| `ChoiceParsers` | Choice用パーサー群 |
| `AfterParse` | パース後処理 |
| `Initializable` | 初期化可能 |
| `ChildOccurs` | 子の出現回数 |
| `GlobalScopeTree` | グローバルスコープツリー |

---

### AST 関連 (`org.unlaxer.ast`)

| クラス名 | 説明 |
|---------|------|
| `ASTMapper` | パースツリーからASTへの変換 |
| `ASTMapperContext` | AST変換のコンテキスト |
| `ASTNodeKind` | ASTノードの種類 |
| `ASTNodeKindTree` | ASTノード種別のツリー |
| `NodeKindAndParser` | ノード種別とパーサーの組 |
| `HierarcyLevel` | 階層レベル |
| `OperatorOperandPattern` | 演算子・被演算子パターン |
| `RecursiveZeroOrMoreBinaryOperator` | ZeroOrMore二項演算子の再帰処理 |
| `RecursiveZeroOrMoreOperator` | ZeroOrMore演算子の再帰処理 |

---

### 式ツリー (`org.unlaxer.expressiontree`)

tinyexpression で使用される式ツリー関連のクラス群がこのパッケージに含まれる。

---

### コンテキスト (`org.unlaxer.context`)

| クラス名 | 説明 |
|---------|------|
| `ParseContext` | パース実行コンテキスト。ソース、カーソル、トランザクションを管理 |
| `Transaction` | トランザクションインターフェース（begin/commit/rollback） |
| `ParserContextScopeTree` | パーサーコンテキストのスコープツリー |

---

### コアデータ (`org.unlaxer`)

| クラス名 | 説明 |
|---------|------|
| `Token` | パースツリーのノード |
| `TokenList` | トークンのリスト |
| `TokenKind` | トークン種別（consumed, matchOnly） |
| `TokenPrinter` | トークンツリーの文字列化 |
| `TokenPredicators` | トークン検索用述語ユーティリティ |
| `Parsed` | パース結果（成功/失敗/停止） |
| `ParsedPrinter` | パース結果の文字列化 |
| `Committed` | コミット済み状態 |
| `Source` | ソーステキストの抽象 |
| `StringSource` | 文字列ベースのソース |
| `StringSource2` | 文字列ソースの改良版 |
| `Range` | 範囲（開始・終了位置） |
| `CursorRange` | カーソル範囲 |
| `CodePointIndex` | コードポイント位置 |
| `CodePointLength` | コードポイント長 |
| `CodePointOffset` | コードポイントオフセット |
| `Name` | 名前付きオブジェクト |
| `Tag` | タグ |
| `PropagatedTag` | 伝播されるタグ |

---

**後輩**: これだけのパーサーが用意されているんですね。全体像が把握できました。

**先輩**: 全部を覚える必要はない。まずは基本の `Chain`, `Choice`, `ZeroOrMore`, `Optional` と、`WordParser`, `NumberParser`, `DigitParser` あたりから始めて、必要に応じて他のパーサーを引き出して使えばいい。

**後輩**: はい、この一覧をリファレンスとして使います。ありがとうございました、先輩！

**先輩**: いつでも聞いてくれ。パーサーの世界は奥が深いけど、unlaxer-parser があれば怖くない。

---

[← Appendix A: 用語集 →](#appendix-a-パーサー用語集) | [目次に戻る →](#目次)

---

> このチュートリアルは unlaxer-parser の学習用ドキュメントです。
> 実際のソースコードは以下のリポジトリにあります:
> - unlaxer-parser: `/home/opa/work/unlaxer-parser`
> - tinyexpression: `/home/opa/work/tinyexpression`
