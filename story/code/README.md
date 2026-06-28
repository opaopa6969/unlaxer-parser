# story/code — 物語に対応する「実際に動くコード」

各章の技術を、**実行可能な最小コード**で確かめられるようにする付録。物語（`story/*.ja.md`）と対で読むと、比喩が本物のコードに着地する。

## 実行のしかた
```bash
CP=$HOME/.m2/repository/org/unlaxer/unlaxer-common/3.0.11/unlaxer-common-3.0.11.jar
javac -cp "$CP" -d out story/code/Ch02Arithmetic.java
java  -cp "out:$CP" Ch02Arithmetic
```
（`unlaxer-common` をローカル `mvn -pl unlaxer-common install` 済みであること。バージョンは適宜読み替え。）

## いま入っているもの
- **Ch02Arithmetic.java** — 第二章。三階建て（Expression ⊃ Term ⊃ Factor）を**本物の Unlaxer コンビネータ**（`Chain`/`Choice`/`OneOrMore`/`ZeroOrMore`/`DigitParser`/`MultipleParser`/`PlusParser`…）で組んで `1+2*3` をパースする。コンパイル＆実行可。

### 実装メモ（正直な落とし穴）
Unlaxer の公開 AST ビュー（`Token.filteredChildren`）は、`Chain`/`Choice` などの**構造コンビネータを畳んで葉トークンだけ**を見せる（生の parse 木 `children(original)` は package-private）。そのため、手組みの無名コンビネータでは「× が + の下に入れ子」という**構造を木として可視化・評価するのが難しい**。構造を見せる／評価するには:
1. **ルールに名前を付けて AST ノードとして残す**、または
2. **UBNF から生成する経路**（第五章）を使う＝生成された構造つき AST（`TinyExpressionP4AST`）と評価器（`P4TypedAstEvaluator`）。これは tinyexpression リポジトリ側で実際に `1+2*3 → 7` を返す。

→ つまり「手で組む（第二〜四章）」と「紙から生成する（第五章〜）」の差は、**この付録のコードの書き味の差**としても体験できる。

## 拡張計画（倍以上の volume へ）
- 各章にコード付録を追加:
  - ch03: 木を歩く評価器（名前付きルール or 生成ASTで `1+2*3→7`）
  - ch05: **UBNF を一枚書いて、Parser/AST/評価器を生成**する最小デモ（CodegenMain）
  - ch06: 三項のカッコ必須を、文法と「失敗→診断」で見せる
  - ch07/08: 評価器 vs Javaコード生成（同じ式・二つの実行、出力を並べる）
  - ch10/11: 指数バックトラックの計測 → packrat memo ON/OFF の時間差を実測表示
  - ch13: 生成器（MapperGenerator）への mapToken メモ化パッチ（現実の #49 と対応）
- **新章（VSCode/LSP）**: `tinyexpression-p4-lsp-vscode` を題材に、文法から IDE が生まれるまでをコード付きで（候補・赤波線・hover）。

> いずれも「実行可能な状態」を優先。物語の各幕末に、対応するコードへのリンクを置く運用にすると、parser を知らない読者は物語だけ、手を動かしたい読者はコードへ、と二層で楽しめる。
