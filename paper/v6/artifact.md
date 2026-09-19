# 再現artifactとソースマップ拡張の設計

対応原稿: [日本語](from-grammar-to-debugger.ja.md) / [English](from-grammar-to-debugger.en.md)。この文書と同じGit revisionのソースを使用する。基点は`21dead3`、Java 21とMaven 3.9系を使用する。外部のtinyexpression checkout、運用データ、VS Codeはこの実験の実行に不要。

## 実行

保存した実行例とテスト件数は[実行記録](results.md)を参照。

追加実験: [Rust生成バックエンドとJava/Rust適合性検証](../../rust/README.md)。同じ4段階の文法からRustのparser・AST・mapper・evaluatorを生成し、共通37入力とcompiler failureを比較する。本文のJava LSP/DAP実験とは区別し、RustのLSP/DAP対応や全面移植を主張しない。

リポジトリルートから次を実行する。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=PropagationAlgebraTest,CopyLanguageTest,LanguageEvolutionTest \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B clean test
```

結果は各moduleの`target/surefire-reports/`に出力される。言語進化の計測値は`unlaxer-dsl/target/language-evolution.tsv`、合成表は`PropagationAlgebraTest`の標準出力とXMLに出る。失敗時には完了した実験のTSVとして扱わない。`clean`は過去の計測値を削除するため、必要な結果は別途保存する。

入力fixtureは`unlaxer-dsl/src/test/resources/evolution/{0,1,2,3}/`。各段階で同じ名前の`Evolution.ubnf`と`Calculator.java.txt`を使う。後者はコンパイル入力として読み込む実際の手書きJavaコードであり、`.txt`はMavenに直接コンパイルさせないための拡張子。生成物8個（6種類＋launcher 2個）は各段階で新しく作り、JavaCompilerと一時classloaderで検証する。

| 段階 | 変更 | 正解値 | 検出する故障 |
|---|---|---|---|
| 0 | 基準の加算DSL | `2+3 = 5` | 基準生成物のコンパイル・実行 |
| 1 | `*`追加 | `2*3 = 6` | 旧evaluatorはコンパイル成功するが未知演算子で実行時失敗 |
| 2 | `Negation`型追加 | `neg(2*3) = -6` | 古いdispatchの非網羅性、古い具体evaluatorの`evalNegation`欠落 |
| 3 | `Conditional`型追加 | `if(0,neg(2*3),4+5) = 9` | 同様に`evalConditional`欠落 |

各段階で以前の入力も再実行する。テストはコンパイル失敗の有無だけでなく、`compiler.err.not.exhaustive`と`compiler.err.does.not.override.abstract`および欠けたメソッド名を検証する。無関係なJavaエラーを網羅性の証拠に数えない。

差分行数はLCSによる追加・削除行数で、空行を含む。手書きファイル数は文法と具体evaluatorのうち前段階から変わった数。基準段階は空ファイルからの追加。生成変更数は生成Javaの完全な文字列比較。計測対象外は共通テストハーネス、ジェネレータ本体、ソースマップAPIの今回の開発工数。`machine_ms`は生成・複数回のコンパイル・動作確認の経過時間であり、人間の作業時間でもベンチマークでもない。追加テスト数は4個のJUnitメソッド（代数1、コピー言語2、言語進化1）。1メソッド内に複数の検証があり、3,280入力を3,280件のJUnitテストとは数えない。

LSPは生成serverを初期化し、文書を開き、新キーワードの補完を呼び出す。DAPは生成adapterを初期化し、一時ファイルのプログラムをAST modeでlaunchし、configurationDone・stackTrace・nextを呼ぶ。JSON-RPC輸送や実エディタ操作は計測していない。DAPは構造上のAST走査であり、条件式の未選択分岐を意味評価のように飛ばす保証はない。Evaluatorの実際の評価訪問順は別途`StepCounterStrategy`で記録する。

## 拡張: 解析結果ごとのソース位置保持

問題は生成mapperの`NODE_SOURCE_SPANS`が次の`parse`/`mapParsedToken`で消えることだった。文書Aを評価中に文書Bを解析すると、AのASTの位置が引けなくなる。AST recordにフィールドを追加すると全constructorに影響するため、解析結果が所有する別のソースマップを生成する設計を選んだ。

```java
var mapped = EvolutionMapper.parseWithSourceMap("1+1");
var ast = mapped.ast();
EvolutionMapper.parse("999");
int[] span = mapped.sourceSpanOf(ast).orElseThrow();
```

新APIは`parseWithSourceMap(String)`と`mapParsedTokenWithSourceMap(Token)`、戻り値は`SourceMappedAst<T>`。従来の`parse`、`MappedAst`、`sourceSpanOf`を維持する。nodeの対応は`IdentityHashMap`で持つため、値が等しい二つの`Number("1")`の位置を区別できる。コピー時・取得時に配列を複製し、利用側の変更が内部状態に影響しない。snapshotは後続解析から独立し、参照がなくなればGC対象になる。記録されたnode数に比例する追加メモリを使う。

生成mapperの公開処理を同期し、mappingからsnapshot作成までを同じclass monitorの中で実行する。これは同じ生成mapperの公開API間の競合を防ぐもので、parserライブラリ全体のthread safetyの宣言ではない。従来の静的lookupは最新mapping専用であり、呼出間の寿命は保証しない。生成DAPは従来のlookupを使うため、複数文書の同時デバッグの保証は本拡張に含めない。

位置は半開区間`[start,end)`、単位は既存DAPと同じUnicodeコードポイント。従来の開始位置はコードポイント、長さはUTF-16という混在を修正した。Javaの`substring`やLSPのUTF-16列へは明示的に変換する。

```java
int startUtf16 = source.offsetByCodePoints(0, span[0]);
int endUtf16 = source.offsetByCodePoints(0, span[1]);
String text = source.substring(startUtf16, endUtf16);
```

構文木のtokenに対応しない合成ノードは位置がない場合がある。`Optional.empty()`をそのまま扱い、位置を捏造しない。今回のfixtureで訪問する全nodeの位置、同値の別node、後続parse後の寿命、取得配列の変更、非BMP文字を含むspanを検査する。SourceMappedAstが保持するのは位置であり、入力文字列そのものは呼出側が保持する。

## 実験で固定した制約

数値は`Digits ::= NUMBER`を介して文字列としてcaptureし、手書きevaluatorで変換する。直接の数値token captureにはprimitive型初期値・token探索に別の制約があるため、この実験から一般化しない。組込み`NumberParser`と衝突しない規則名`Literal`を用いる。演算子は各選択肢に`@op`を付ける。group全体へのcaptureとは推論型が異なる。

再現性は「与えられたfixtureに対して主張した成功と失敗が再現する」範囲。あらゆるUBNF文法の型正当性、意味論の正しさ、生産性向上、優れた性能を証明しない。
